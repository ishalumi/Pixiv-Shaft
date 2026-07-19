# Shaft (ishalumi fork)

基于上游 **classic** 分支（含搜索 V3 / 喜欢！数 UI）。

## 修了什么

「喜欢！数」本地 `total_bookmarks` 过滤失效：

- `RemoteRepo` 构造期缓存 `mapper()` + Kotlin 字段初始化清掉 `filterMapper`
- 导致 `updateStarSizeLimit` 永不生效，个位数喜欢仍显示

修复：每次请求调用 `mapper()` 并同步门槛。

## 签名 / CI

Secrets: `SHAFT_KEYSTORE_BASE64` `SHAFT_KEYSTORE_PASSWORD` `SHAFT_KEY_ALIAS` `SHAFT_KEY_PASSWORD`

推 `master` → debug APK artifact；打 `v*-isha.*` tag → release APK。

本地 keystore: `/opt/pixiv-shaft-secrets/isha-shaft.jks`（不进 git）
