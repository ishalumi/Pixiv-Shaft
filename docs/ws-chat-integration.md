# Pixiv-Shaft 接入 shaft-api-v2 聊天 WebSocket 指南

服务端代码:`shaft-api-v2/src/chat/*`(@ HEAD `dbd3655`)。
客户端框架已在 `app/src/main/java/ceui/pixiv/websocket/` 落地;本文件描述的是
**服务端契约**——帧格式、鉴权、心跳、错误码、HTTP 配套——以及如何把这套契约
对接到现有 `WebSocketAuthProvider` / `RobustWebSocketClient` / `ChatHistorySource` 抽象上。

---

## 0. TL;DR

- **WS endpoint**:`ws://<host>:<port>/api/v1/chat/ws?client_id=<hex64>&ts=<unix_ms>&sig=<hmac_hex>`
- **HTTP 配套**:`GET /api/v1/chat/history`、`GET /api/v1/chat/profile`、`POST /api/v1/chat/profile`、`GET /api/v1/chat/stats`
- **鉴权**:复用 `BuildConfig.SHAFT_EVENTS_HMAC`,签名 `HMAC_SHA256(secret_ascii, "${client_id}|${ts}")`,hex 小写
- **`ts` 必须是干净的十进制整数字符串**(严格 `/^[1-9][0-9]{12,13}$/`),不接受 `1.7e12` 或带小数点
- **身份**:复用 `EventReporter.currentClientId()`(64 字符 hex),不需要新身份系统
- **明文**:复用现有 `network_security_config.xml`(`cleartextTrafficPermitted="true"`),不改
- **心跳**:服务端 30s 主动 ping,**任何**入站(RFC pong / 文本帧 / 应用层 ping)都重置 60s idle 计时;OkHttp 配 `pingInterval=30s`
- **重连**:指数退避 1s → 2s → 4s → 8s → 16s 封顶 30s + jitter(对接到 `ReconnectStrategy`)
- **限频**:每连接 5 条/5s 令牌桶;超出收 `{kind:"err", code:"rate_limited"}` 不掉线
- **消息体上限**:`text` ≤ 2048 UTF-16 单元 / 整帧 ≤ 4096 UTF-16 单元(详见 §1.2)

---

## 1. WebSocket 协议规约

### 1.0 连接生命周期总览

每条 WS 连接的 canonical 时间线:

```
客户端                                            服务端
  │                                                 │
  │ ── HTTP GET /api/v1/chat/ws?cid&ts&sig ───────→ │
  │                                                 │ 验 IP 限流 / HMAC / ts skew
  │ ←──────── 101 Switching Protocols ───────────── │ (失败时回 401/429/503,断 TCP)
  │                                                 │
  │ ←──────── {"kind":"hello", ...}     ──────────  │ ★ hello 永远是服务端发出的第一帧
  │                                                 │   (此时 UI 应该切到 Connected)
  │                                                 │
  │  ↕  msg / ping / err / pong / RFC ping/pong  ↕  │ 双向自由聊天
  │                                                 │
  │                                                 │
  │ 三种 close 路径:                                 │
  │ (A) 客户端主动 ws.close(1000) → 协商关 ─────────→│
  │ (B) 服务端 raw.terminate() 抢断 ──────────────  │  ← 心跳/背压/shutdown 走这条
  │     客户端收到 onClose(1006, "")                │
  │ (C) TCP 异常 (NAT 回收 / 断网)                   │
  │     客户端收到 onFailure(IOException)           │
```

**关键不变式**:
- ★ **hello 永远是 server 发出的第一帧**。客户端可以等收到 hello 之后才允许用户在 UI 上发消息;在那之前 send 也不会丢(OkHttp 会排队),但语义上还没"接通"
- 服务端**永远走 `terminate()`,不发 close frame**。所以异常断连(心跳超时 / 背压超限 / shutdown)都是 `onClose(code=1006, reason="")` —— 客户端通过这个特征区分"我主动关的(1000)"还是"被服务端踢的(1006)"
- **每条 WS 是独立会话,没有 session resume**。重连后服务端不会回放断连期错过的消息,要补齐就调 `/chat/history?before=<最后一条 id>`

### 1.1 握手 URL 与失败码

```
ws://<host>:<port>/api/v1/chat/ws
  ?client_id=<64位小写hex>
  &ts=<unix毫秒,严格十进制整数字符串>
  &sig=<HMAC-SHA256(secret_ascii, "${client_id}|${ts}") hex小写>
```

> ⚠️ **认证只看 query string,不读 header / cookie**。OkHttp 写 WS 时容易顺手 `addHeader("Authorization", ...)`,服务端会忽略,只认 query 里的 `sig`。

握手响应映射:

| HTTP | body                              | 含义 / 处理 |
|------|-----------------------------------|--------|
| 101  | (switching protocols)             | 握手成功,等服务端 `hello` 帧 |
| 401  | `{"error":"bad_client_id"}`       | 不是 64 小写 hex,检查 `EventReporter` 初始化 |
| 401  | `{"error":"bad_ts"}`              | ts 不是干净的 13-14 位整数字符串(科学计数法 / 浮点 / 前导空格都会触发) |
| 401  | `{"error":"ts_skew"}`             | 客户端时钟与服务端差 > 60s,**重试不解决**,提示用户校准时间 |
| 401  | `{"error":"bad_sig"}`             | 签名错。多半是密钥不一致 / ts 字符串canonical 化不一致(见 §2.1) |
| 429  | `{"error":"rate_limited","scope":"ip","retryAfterSeconds":N}` | 同 IP > 200 次/分,退避 N 秒后重试,**禁止立即重连成环** |
| 503  | `{"error":"shutting_down"}`       | 服务端 graceful shutdown 中,等 5–10s |

> 401 是 fatal 类:除了 `ts_skew` 之外,无脑重试都没意义,应进 `FatalAuth` 状态等显式触发。

### 1.2 客户端 → 服务端帧(文本帧,UTF-8 JSON)

#### `msg` — 发消息
```json
{ "kind": "msg", "text": "你好世界", "illust_id": 12345 }
```
- `text`:必填字符串,1–2048 UTF-16 单元(emoji 算 2 单元),尾部 `\r\n` 服务端会 strip 掉
- `illust_id`:可选正整数,关联一个 pixiv 作品 id(`0 < n < 10^15`)
- 整帧 ≤ **4096 UTF-16 单元**(纯 ASCII ≈ 4 KB;纯中文 ≈ 12 KB UTF-8 字节,因为中文一字 1 UTF-16 单元 = 3 字节 UTF-8)。超就回 `err.frame_too_large` 不掉线。
  > 服务端检查的是 `raw.length > 4096`(UTF-16 单元),不是 byte length。客户端先卡 `text.length ≤ 2048` 通常就够了 — 信封 JSON 加起来一般不会超 2.5x

#### `ping` — 应用层心跳(可选)
```json
{ "kind": "ping" }
```
- 服务端回 `{ "kind": "pong", "server_ts": <unix_ms> }`
- 给"RFC 6455 ping 控制帧不好发"的栈做兜底;OkHttp 走 `pingInterval` 即可,**不需要**额外发应用层 ping
- 服务端心跳判活看的是"60s 没任何入站",所以**只要在聊天就在续命**(见 §3),不必额外 ping

### 1.3 服务端 → 客户端帧

#### `hello` — 握手成功后服务端立刻推一帧
```json
{
  "kind": "hello",
  "client_id": "abc...64",
  "display_name": "匿名_abc123",
  "room": "global",
  "server_ts": 1778736246501
}
```
- 客户端拿到 `hello` 才算"真正接通",可以把 UI 从 `Connecting` 切到 `Connected(displayName)`
- `display_name` 是服务端权威值;有自定义就返回自定义,否则用 `匿名_<client_id[0:6]>` 派生
- **改名后该值不会自动刷新**(连接级缓存),客户端需要 reconnect 才会拿到新名字 — 见 §1.4 profile

#### `msg` — 消息广播(包括发送者自己也会收到)
```json
{
  "kind": "msg",
  "client_id": "abc...64",
  "display_name": "lisa",
  "text": "你好世界",
  "illust_id": 12345,
  "ts": 1778736246502
}
```
- **没有 server-assigned id**(id 只在 HTTP /history 接口出现;广播是 fire-and-forget 然后异步入库)
- 客户端按 `ts` 升序追加到 UI 列表
- 自己发的消息也会沿这条回来 — UI **应当以回声为准**而不是 optimistic 渲染,避免"发出去但服务端实际未生效"的 UI 假状态
- **没有 per-message ACK** — 客户端把"收到自己消息的回声"当作隐式 ACK 即可。N 秒内没看到回声 = 这条没成功,需要 UI 上重发(典型 N = 3–5s)
- **同 `client_id` 多端并发支持** — 用户在 A 设备和 B 设备同时开 app,服务端把两条 WS 当独立订阅者。A 发消息后,**A 自己和 B 都会各收一份回声**;UI 渲染层据此能在两个端上同步显示

#### `err` — 协议 / 限频 / 校验错误
```json
{ "kind": "err", "code": "rate_limited" }
```
服务端发完 **不会断开**,**也不会进入半死状态** —— 下一帧仍然按正常流程处理。`err` 帧本质就是"上一帧被丢弃 + 原因",连接状态机不变。完整 code 表:

| code              | 含义                              | 客户端建议处理 |
|-------------------|----------------------------------|----------------|
| `frame_too_large` | 帧 > 4 KB                        | 永久限制,客户端先卡 4 KB 别让用户发 |
| `bad_json`        | 不是合法 JSON                     | 客户端 bug,记日志 |
| `bad_envelope`    | JSON 不是对象 / 是数组             | 同上 |
| `unknown_kind`    | `kind` 不在 `msg`/`ping` 内       | 同上 |
| `bad_text`        | `text` 不是字符串                  | 同上 |
| `bad_text_length` | `text` 空 / 超 2048               | 客户端先卡 1–2048 |
| `bad_illust_id`   | `illust_id` 不是正整数 / 越界      | 同上 |
| `rate_limited`    | 5/5s 令牌桶溢出                   | 1s 后再让用户发 |

#### `pong` — 响应客户端应用层 `ping`
```json
{ "kind": "pong", "server_ts": 1778736246502 }
```

---

### 1.4 HTTP 配套

#### `GET /api/v1/chat/history?room=global&limit=50&before=<id>`

拉历史。`before` 不传时取 tail,分页时填上一页**最早**一条的 `id`。

Response:
```json
{
  "room": "global",
  "limit": 50,
  "items": [
    { "id": 1, "client_id": "...", "display_name": "lisa",
      "text": "...", "illust_id": 123, "ts": 1778736246502 }
  ]
}
```
- `items` 按 `ts` **升序**(旧 → 新)
- 下一页 cursor:`before = items[0].id`
- 空列表 = 到顶
- `limit` 默认 50、上限 200

> 对接 `ChatHistorySource`/`MessagePage`:`items` 直接映射到 `MessagePage.items`,
> `items[0].id` 作为下一页 cursor 存进 `MessagePage.nextCursor`(空列表时 cursor=null)。

#### `POST /api/v1/chat/profile` — 改昵称

Body:
```json
{ "client_id": "...", "ts": 1778736246502, "display_name": "新名字" }
```
Header:`X-Shaft-Sign: <HMAC-SHA256(secret_ascii, "${client_id}|${ts}") hex>`

约束:
- `display_name` 1–32 UTF-16 单元 / UTF-8 字节 ≤ 96(中文最多约 32 字)
- 禁 ASCII 控制字符(`\x00-\x1f`、`\x7f`)
- 服务端 `trim()` 首尾空白后再校验

Response:`{ "ok": true, "display_name": "新名字" }`

错误码:`bad_display_name_length` / `bad_display_name_chars` / `bad_display_name_bytes` / `bad_sig` / `ts_skew`

**重要**:已开着的 WS 不会自动收到自己名字变更的通知。客户端两种选择,二选一即可:
- 改名后 UI 局部 patch,自己显示新名;其他客户端要等下一条 `msg` 广播才看到(服务端每条消息从缓存读名字)
- 改名后客户端主动 reconnect,新连接的 `hello` 帧带新名,所有状态强同步

#### `GET /api/v1/chat/profile?client_id=<hex64>`

公开查任意 client_id 的当前展示名,给历史回放遇到陌生 id 时补名。

Response:`{ "client_id": "...", "display_name": "..." }`

#### `GET /api/v1/chat/stats`

```json
{ "room": "global", "online": 142, "total_messages": 893 }
```
- `online` = 当前 WS 订阅数(每条活连接订阅一次,关连接退订一次,数值等于"现在在线的人")
- `total_messages` = 该房间 chat_messages 总行数(便宜:走索引 COUNT)

---

## 2. 鉴权:HMAC 签名

### 2.1 签名规则

```
sig = HMAC_SHA256_HEX(
  key   = BuildConfig.SHAFT_EVENTS_HMAC.toByteArray(UTF_8),  // ASCII 字节,不是 hex 解码后的 32 字节
  data  = "${client_id}|${ts}".toByteArray(UTF_8),
)
```

四个关键点(每一条都踩过坑):

1. **key 是 ASCII 字节** —— 密钥 hex 字符串本身的 UTF-8 编码,**不是**把 hex 解码成 32 字节。对齐 Node `createHmac('sha256', secret)` 默认行为
2. `|` 是 ASCII pipe (0x7C)
3. **ts 用 `String(ts)` 给出的十进制串签名,也是同一个串放进 URL** —— 服务端用 query 里收到的字符串原样去算 HMAC,不会先 `Number()` 再 `toString()`。所以如果客户端在签名时用了 Number 但放 URL 时不小心带了 `.0`/科学计数法/前导空格,签名就会"算得对、传不对" → 服务端拒为 `bad_sig`
4. 输出 hex **小写**(`Buffer.from(_, 'hex')` 大小写都吃,但小写是事实标准)

### 2.2 对接 `WebSocketAuthProvider`

你已经有的 `WebSocketAuthProvider` 接口和 `BearerTokenAuthProvider` 实现,在这里只需要加一个 HMAC 风味的实现 — 比如 `ShaftHmacAuthProvider`:

```kotlin
// 关键职责:给出每次握手的 URL(因为 sig 跟 ts 强绑,不能缓存)
class ShaftHmacAuthProvider(
    private val baseHttpUrl: String,            // BuildConfig.SHAFT_EVENTS_BASE_URL
    private val secretAscii: String,            // BuildConfig.SHAFT_EVENTS_HMAC
    private val clientIdProvider: () -> String, // { EventReporter.currentClientId() }
) : WebSocketAuthProvider {

    override fun nextRequest(): Request {
        val cid = clientIdProvider()
        require(cid.isNotEmpty()) { "EventReporter not initialised yet" }
        val ts  = System.currentTimeMillis().toString()
        val sig = ShaftHmac.sign("$cid|$ts", secretAscii)
        val url = deriveWsBase(baseHttpUrl) +
            "/api/v1/chat/ws?client_id=$cid&ts=$ts&sig=$sig"
        return Request.Builder().url(url).build()
    }
}

private fun deriveWsBase(httpBase: String): String =
    httpBase.removeSuffix("/")
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
```

`ShaftHmac.sign` 与 `EventReporter.hmacSha256Hex` 是同一个算法。建议把这俩合并成一个公共工具(`app/src/main/java/ceui/pixiv/shaftapi/ShaftHmac.kt`),`EventReporter` 也改用它,免得签名规则散两处。

### 2.3 配套 HTTP 改名签名

`POST /chat/profile` 用同样的 (`cid|ts`) 公式,sig 走 `X-Shaft-Sign` header(与 `events.batch` pattern 对齐)。建议把这个签名调用做成 `ShaftHmac` 上的便捷方法:

```kotlin
suspend fun renameSelf(newName: String): Result<String> = runCatching {
    val cid = EventReporter.currentClientId()
    val ts  = System.currentTimeMillis()
    val sig = ShaftHmac.sign("$cid|$ts", BuildConfig.SHAFT_EVENTS_HMAC)
    chatApi.setProfile(sig, SetProfileBody(cid, ts, newName)).display_name
}
```

---

## 3. 心跳与活性

服务端心跳算法(`shaft-api-v2/src/chat/ws.js`)简洁地总结一下:

```
握手成功 → lastInboundTs := now,启动 30s interval(首次触发在 +30s,不是 t=0)

每次 interval 触发(t=30s, 60s, 90s, ...):
  if (now - lastInboundTs > 60s) terminate     ← 抢断,无 close frame,客户端见 1006
  else send RFC ping

收到任何入站(RFC pong / 文本帧 / 应用层 ping): lastInboundTs := now
```

含义两条:

1. **只要客户端在聊天,就在持续证明活着** —— 不需要客户端额外发任何心跳。文本 msg 帧本身就刷新计时
2. **空闲连接靠 RFC ping/pong 续命** —— 客户端 ws 库自动 pong 响应即可(OkHttp 默认行为),不需要应用层手动操心

### 3.1 OkHttp 推荐配置

```kotlin
val ws: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)         // WS 长连接,read 永不超时,靠 ping 探活
    .writeTimeout(10, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)             // 客户端也 ping,双向探活
    .retryOnConnectionFailure(true)
    .build()
```

`pingInterval=30s` 跟服务端 30s ping 频率对齐:**两端任意一边的 NAT/CGN 先回收 idle 连接时,都能被另一边的 ping 撞醒**。手机 4G/5G 的 CGN 经常 30–60s idle 就回收,所以双端都设 30s 是稳的下限。

### 3.2 不要这么做

- ❌ **不要发应用层 `{kind:"ping"}` 当心跳** —— OkHttp 已经做 RFC ping 了,应用层 ping 是给特殊客户端的兜底,正常用不到
- ❌ **不要靠 `readTimeout` 检测断连** —— WS 长连接读路径正常就是没数据,设了反而误杀

---

## 4. 重连策略

服务端没有"等你回来"的语义;每次 WS 都是全新连接,握手成功后服务端推 `hello` 表示就绪。这意味着:

1. **历史在 reconnect 期不会回放** —— 客户端要补齐这段就调 `/chat/history?before=<最后一条id>` 拉
2. **未发出去的消息**(网络断时排在客户端发送队列里的)在 reconnect 后**不要自动 flush** —— 用户已经看到自己消息发出去的"假象"了,重连后再次发送会产生重复;最佳做法是 reconnect 后让用户重新发(UI 上保留草稿)

### 4.1 退避表

| 第 N 次重连 | 基础等待 | 实际等待(加 ±20% jitter) |
|------------|---------|------------------------|
| 1          | 1s      | 0.8–1.2s              |
| 2          | 2s      | 1.6–2.4s              |
| 3          | 4s      | 3.2–4.8s              |
| 4          | 8s      | 6.4–9.6s              |
| 5+         | 16–30s  | jitter ±20%           |

收到 `hello` 帧才视为重连成功 → 退避计数清零。

### 4.2 反例 / 立即重试只在以下场景做

- `ConnectivityObserver` 报告网络从 OFFLINE → ONLINE 时,直接清退避立即重连
- App 从后台回前台 + state 仍是 Disconnected 时,立即重连
- 收到 `503 shutting_down` 时,等 5–10s 后第一次重连(服务端 graceful shutdown 大约 10s 内完成)

### 4.3 对接 `ReconnectStrategy`

你已有的 `ReconnectStrategy` + `ReconnectCoordinator` + `NetworkMonitor` + `ConnectivityObserver` 这套刚好能编排上面的策略。建议:
- `ReconnectStrategy` 内部维护退避表 + jitter
- `ReconnectCoordinator` 监听 `NetworkMonitor` 的 callback,在 `ONLINE` 触发时**重置**退避并立刻触发重连
- 收到 `hello`(从 `IncomingMessage` 流里看到)即清退避计数

---

## 5. 错误处理矩阵

整合所有上面提到的错误及推荐处理:

| 场景                                   | 客户端处理 |
|----------------------------------------|---------------|
| 握手返回 401 `bad_client_id`           | EventReporter 没初始化好,500ms 后重试一次,仍失败则 `FatalAuth` |
| 握手返回 401 `bad_ts`                  | 客户端 bug(ts 格式错),记日志、`FatalAuth` |
| 握手返回 401 `ts_skew`                 | 用户系统时钟错乱,弹"请校准系统时间"提示 |
| 握手返回 401 `bad_sig`                 | 密钥不一致(BuildConfig 配错 / fork 没改 / 老版本 app)→ `FatalAuth` |
| 握手返回 429                           | 看 `Retry-After` header 退避,否则按指数退避 |
| 握手返回 503                           | 5–10s 后重连 |
| 收到 `err.frame_too_large` / `bad_*`  | 客户端 bug,记日志,UI 上 fallback 提示"消息发送失败" |
| 收到 `err.rate_limited`                | 1s 内 disable 发送按钮 |
| WS 60s 内没任何入站(连 RFC pong 都没)  | 服务端 `terminate()` → 客户端 `onClose(1006, "")`;OkHttp `pingInterval` 设了基本不会发生 |
| `bufferedAmount > 256 KB`              | 服务端 `terminate()` → 客户端 `onClose(1006, "")`;一般是客户端读慢/UI 卡导致出站积压 |
| `onClose(1000, ...)`                   | 客户端自己关的,不要重连 |
| `onClose(1006, "")`                    | 服务端 `terminate()` 或 TCP 异常,**都走重连流程**(无法区分,也无需区分) |
| `onFailure` IOException                | 走 `ReconnectStrategy` |

---

## 6. Android 网络配置

你现有的 `app/src/main/res/xml/network_security_config.xml` 的 `base-config` 是 `cleartextTrafficPermitted="true"`,shaft 服务通过 base-config 继承到明文权限。**不用改**。

将来给 shaft 上 TLS(强烈建议聊天加 wss),把 shaft 主机改成独立 `domain-config`,系统证书或 LE 都可以:

```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="false">your.shaft.host</domain>
    <trust-anchors><certificates src="system" /></trust-anchors>
</domain-config>
```

---

## 7. 服务端契约 vs 客户端架构对照

为了让现有 `app/src/main/java/ceui/pixiv/{websocket,chat}/` 这套对接清晰,
列一份"哪个服务端帧 / 端点对应客户端哪一层"的对照表:

| 服务端契约            | 对应客户端层 / 类                                  | 备注 |
|----------------------|---------------------------------------------------|------|
| `hello` 帧            | `WebSocketState.Connected` 触发条件               | 收到 hello 才切 Connected,Open 不算 |
| `msg` 帧广播          | `ChatMessageStream.messages`(`Flow<ChatMessage>`)| 入站 msg → 解码 → emit |
| `err` 帧              | `WebSocketEvent.ProtocolError(code)`              | 不掉线,UI 层决定是否提示 |
| `pong` 帧 / RFC pong  | 内部 liveness,不向上抛                            | 只让 `RobustWebSocketClient` 用 |
| `GET /chat/history`   | `ChatHistorySource.load(cursor) → MessagePage`    | items 转 entity,cursor=items[0].id |
| `POST /chat/profile`  | 独立 use case `RenameSelfUseCase` 或直接走 VM     | 完成后选择 reconnect 与否 |
| `GET /chat/stats`     | 监控/Debug 用,主链路不依赖                        | 可在调试抽屉显示 |
| 握手 sig 校验失败 401 | `WebSocketAuthProvider` 抛 `AuthFailedException`  | 上层 `FailureContext.kind = AUTH_FATAL`,停止重连 |
| `onMessage` 解析失败  | `IncomingMessage.Unknown(raw)` 落到 dead-letter   | 不挂掉流 |

---

## 8. 调试

### 8.1 服务端实时观测

NAT 主机已配 SSH alias `shaft-v2`:

```bash
# 在线 / 总消息数
curl http://<host>:8080/api/v1/chat/stats

# 你自己当前展示名
curl 'http://<host>:8080/api/v1/chat/profile?client_id=<hex64>'

# 拉最近 5 条(确认服务端真收到了)
curl 'http://<host>:8080/api/v1/chat/history?limit=5'

# 服务端日志(每条连接生命周期都有 INFO)
ssh shaft-v2 'pm2 logs shaft-api-v2 --lines 200 | grep chat'
```

服务端 INFO 行关键字:
- `chat ws open` + `clientId` + `conns`
- `chat ws close`
- `chat ws heartbeat lost — terminating` (60s 没入站)
- `chat ws backpressure exceeded — terminating` (出站 buffer > 256KB)
- `chat batch insert failed`(基本不该发生)
- `broker subscriber threw`(单订阅者异常,扇出仍正常)

### 8.2 Android 端

```bash
adb logcat -s ChatWS:V WebSocket:V
```

OkHttp 的 WS 握手 HTTP 来回 **不会** 走 `HttpLoggingInterceptor` —— 升级成功后 OkHttp 接管 socket,interceptor 看不到。但失败的握手(401/429)会被 OkHttp 当成普通 HTTP 响应,日志可见 status code 和 body —— 调认证时这一条特别有用。

### 8.3 本地起 server 自测

```bash
cd shaft-api-v2
EVENTS_HMAC_SECRET=$(cat /etc/shaft-api-v2/events-hmac-secret) \
PORT=8080 HOST=127.0.0.1 NODE_ENV=production npm start
```

把手机 / 模拟器 `SHAFT_EVENTS_BASE_URL` 切到 `http://<电脑局域网IP>:8080/`,
模拟器走 `http://10.0.2.2:8080/`。

---

## 9. 已知限制 & 路线

| 现状                              | 解决方向 |
|-----------------------------------|----------|
| 单房 `global`                      | schema 已留 `room_id`,加 query 参数 + ACL 即可 |
| 单机部署 / in-memory broker        | 接 Redis pub/sub,`broker.js` 一文件替换 |
| 明文 ws://                         | Caddy + LE 切 wss://(NAT 也能签证书,DNS-01 或 HTTP-01) |
| 历史名字随用户改名实时变            | LEFT JOIN chat_clients 取当前名 — 想要"名字快照"语义就改 INSERT 时 denormalize |
| 没有"已读"语义                     | 客户端本地存最后已读 ts,服务端不参与 |
| 没有撤回/编辑                      | 加 `DELETE`/`UPDATE` 路由 + WS `del`/`edit` 帧 |
| 重连期消息会缺                     | 客户端调 /history 补齐;无 offset 重放是有意为之(避免 server 维护连接级 offset 状态) |
| 同 client_id 多设备并发            | 已支持(broker 把它们当独立订阅者),自己消息每个端都能收到一份 |

---

## 10. 检查清单(对接前过一遍)

- [ ] `ShaftHmac.kt` 工具抽出来,`EventReporter` 也复用它
- [ ] `ShaftHmacAuthProvider` 实现 `WebSocketAuthProvider`,`nextRequest()` 每次产新 sig
- [ ] OkHttp 单独实例:`pingInterval=30s` + `readTimeout=0`
- [ ] `RobustWebSocketClient` 接到 hello 帧后才认作 Connected
- [ ] `IncomingMessage` 解码层覆盖 5 个 kind 并把 `err.code` 透出
- [ ] `ReconnectStrategy` 实现退避表 + jitter,**收到 hello 即清零**
- [ ] `NetworkMonitor` 切到 ONLINE 时 reset 退避立即重连
- [ ] 401 类失败送 `FatalAuth`,UI 提示用户(`bad_sig` 一般是配置错,`ts_skew` 是时钟错乱)
- [ ] 客户端先卡 `text.length ≤ 2048`,**不要**等 server `err` 才知道
- [ ] 收到 `err.rate_limited` 在 UI 上 1s 内 disable 输入框
- [ ] 自己发的消息走广播回声路径渲染,**不要**双渲染(optimistic + 回声)
- [ ] 上滑加载更多时 `before=items[0].id`,空响应=到顶
- [ ] App 进聊天页 `start()`,退出 ViewModel `onCleared()` `stop()`(不要在 `onPause` 停)
- [ ] `client_id` 为空时(EventReporter 没好)retry 而不是崩

---

## 附:与 events.batch 系统的协议对照

| 维度       | events.batch                    | chat ws                          |
|-----------|--------------------------------|----------------------------------|
| 传输       | HTTP POST batch                | WebSocket text frame             |
| 鉴权 HMAC  | `HMAC(rawBody)` 走 header       | `HMAC("${cid}\|${ts}")` 走 query  |
| 客户端身份 | `client_id`                    | 同一个 `client_id`                |
| 限频       | 30/min/client + 1000/min/IP   | 5/5s/conn + 200/min upgrade/IP   |
| 存储       | `shaft-events.db`(90 天)       | `shaft-chat.db`(30 天)           |
| 实时性     | 60s batch flush                | 实时广播 + 200ms 批量入库         |
| 失败语义   | 本地存盘重试到容量满              | 重连,丢失中间消息(用 /history 补) |

`client_id` 跨两套系统共用 —— 将来想做"用户主页"(events 行为 + chat 发言)时,JOIN `client_id` 即可直连两边。
