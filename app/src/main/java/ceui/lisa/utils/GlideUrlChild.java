package ceui.lisa.utils;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.Headers;

import java.util.HashMap;

import ceui.lisa.http.ImageHostManager;
import ceui.lisa.http.PixivHeaders;

public class GlideUrlChild extends GlideUrl {

    public GlideUrlChild(String url) {
        this(url, formatHeader());
    }

    public GlideUrlChild(String url, Headers headers) {
        // issue #865: single choke point for image loading — rewrite the pixiv
        // image host to the user's chosen host (Pixiv / pixiv.cat / custom).
        // rewrite() is a no-op in the default PIXIV mode and for non-pximg urls,
        // and is idempotent, so wrapping here (the sink both ctors reach) is safe.
        // The rewritten url also becomes the GlideUrl cache key, so switching
        // hosts naturally uses a distinct cache entry.
        super(ImageHostManager.INSTANCE.rewrite(url), headers);
    }

    private static Headers formatHeader() {
        PixivHeaders pixivHeaders = new PixivHeaders();
        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put(Params.MAP_KEY_SMALL, Params.IMAGE_REFERER);
        hashMap.put("x-client-time", pixivHeaders.getXClientTime());
        hashMap.put("x-client-hash", pixivHeaders.getXClientHash());
        hashMap.put(Params.USER_AGENT, Params.PHONE_MODEL);
        return () -> hashMap;
    }
}
