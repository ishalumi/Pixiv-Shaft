package ceui.lisa.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;
import okhttp3.Dns;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HttpDns implements Dns {

    private static final String[] DOH_ENDPOINTS = {
            CloudFlareDNSService.Companion.getCLOUDFLARE_DOH_POINT(),
            CloudFlareDNSService.Companion.getDNSSB_DOH_POINT(),
    };

    private static final String[] DOMAINS = {
            "app-api.pixiv.net",
            "oauth.secure.pixiv.net",
    };

    // Pixiv API/OAuth 已迁移至 Cloudflare CDN (2026-04)，与 CronetInterceptor 共享
    private static final String[] FALLBACK_API_IPS = {
            CronetInterceptor.CF_IP_PRIMARY,
            CronetInterceptor.CF_IP_SECONDARY,
    };

    // 图片服务器还在旧 Pixiv 基础设施
    private static final String[] FALLBACK_IMAGE_IPS = {
            "210.140.139.134",
            "210.140.139.133",
            "210.140.139.131",
    };

    private final Map<String, List<InetAddress>> resolvedHosts = new ConcurrentHashMap<>();
    private static volatile HttpDns sHttpDns = null;
    private List<InetAddress> fallbackApiAddresses;
    private List<InetAddress> fallbackImageAddresses;

    private HttpDns() {
        fallbackApiAddresses = new ArrayList<>();
        for (String ip : FALLBACK_API_IPS) {
            try {
                fallbackApiAddresses.add(InetAddress.getByName(ip));
            } catch (UnknownHostException ignored) {
            }
        }
        fallbackImageAddresses = new ArrayList<>();
        for (String ip : FALLBACK_IMAGE_IPS) {
            try {
                fallbackImageAddresses.add(InetAddress.getByName(ip));
            } catch (UnknownHostException ignored) {
            }
        }
        if (isSecureDnsEnabled()) {
            for (String domain : DOMAINS) {
                resolveViaDoH(domain, 0);
            }
        }
    }

    public static HttpDns getInstance() {
        if (sHttpDns == null) {
            synchronized (HttpDns.class) {
                if (sHttpDns == null) {
                    sHttpDns = new HttpDns();
                }
            }
        }
        return sHttpDns;
    }

    // 设置项切换时调用：Glide 等持有的 OkHttpClient 仍引用同一个 HttpDns 实例，
    // 所以这里清空已缓存的 DoH 结果，并按当前设置重新预热，让切换立即生效。
    public static void invalidate() {
        HttpDns instance = sHttpDns;
        if (instance == null) {
            return;
        }
        instance.resolvedHosts.clear();
        if (isSecureDnsEnabled()) {
            for (String domain : DOMAINS) {
                instance.resolveViaDoH(domain, 0);
            }
        }
    }

    private static boolean isSecureDnsEnabled() {
        return Shaft.sSettings != null && Shaft.sSettings.isUseSecureDns();
    }

    private void resolveViaDoH(String hostname, int endpointIndex) {
        if (endpointIndex >= DOH_ENDPOINTS.length) {
            Common.showLog("HttpDns all DoH failed for " + hostname + ", will use fallback IPs");
            return;
        }
        try {
            CloudFlareDNSService service = CloudFlareDNSService.Companion.invoke(DOH_ENDPOINTS[endpointIndex]);
            service.query(hostname, "A").enqueue(new Callback<CloudFlareDNSResponse>() {
                @Override
                public void onResponse(Call<CloudFlareDNSResponse> call, Response<CloudFlareDNSResponse> response) {
                    CloudFlareDNSResponse body = response.body();
                    if (body != null && !Common.isEmpty(body.getAnswer())) {
                        List<InetAddress> addresses = new ArrayList<>();
                        for (CloudFlareDNSResponse.DNSAnswer answer : body.getAnswer()) {
                            try {
                                if (answer.getType() == 1) {
                                    addresses.add(InetAddress.getByName(answer.getData()));
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (!addresses.isEmpty()) {
                            resolvedHosts.put(hostname, addresses);
                            Common.showLog("HttpDns resolved " + hostname + " -> " + addresses);
                        } else {
                            resolveViaDoH(hostname, endpointIndex + 1);
                        }
                    } else {
                        resolveViaDoH(hostname, endpointIndex + 1);
                    }
                }

                @Override
                public void onFailure(Call<CloudFlareDNSResponse> call, Throwable t) {
                    Common.showLog("HttpDns DoH failed for " + hostname + ": " + t.getMessage());
                    resolveViaDoH(hostname, endpointIndex + 1);
                }
            });
        } catch (Exception e) {
            resolveViaDoH(hostname, endpointIndex + 1);
        }
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        long start = System.nanoTime();
        if (isSecureDnsEnabled()) {
            // DoH 开：优先用 DoH 缓存的解析结果
            List<InetAddress> cached = resolvedHosts.get(hostname);
            if (cached != null && !cached.isEmpty()) {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                Common.showLog("HttpDns lookup " + hostname + " → DoH cached " + cached + " [" + elapsed + "ms]");
                return cached;
            }
        } else {
            // DoH 关：用户主动表示本地 DNS 可信，先走系统 DNS（issue #616 的核心诉求）
            try {
                List<InetAddress> systemResult = Dns.SYSTEM.lookup(hostname);
                if (!systemResult.isEmpty()) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    Common.showLog("HttpDns lookup " + hostname + " → system " + systemResult + " [" + elapsed + "ms]");
                    return systemResult;
                }
            } catch (UnknownHostException ignored) {
                // 系统 DNS 失败再落到下面的硬编码 fallback
            }
        }
        // 图片域名用旧 Pixiv 服务器 IP，API 域名用 Cloudflare IP
        List<InetAddress> result;
        String source;
        if (hostname.endsWith("pximg.net")) {
            result = fallbackImageAddresses;
            source = "fallback-image";
        } else {
            result = fallbackApiAddresses;
            source = "fallback-api";
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        Common.showLog("HttpDns lookup " + hostname + " → " + source + " " + result + " [" + elapsed + "ms]");
        return result;
    }
}
