package ceui.loxia

import ceui.lisa.activities.Shaft
import ceui.lisa.helper.LanguageHelper
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.task.TaskPool
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class HeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            addHeader(
                chain.request().newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
        val requestNonce = RequestNonce.build()
        // 未登录 / 已登出时不带 Authorization,让服务端返回 401 由上层处理,避免拦截器抛 RuntimeException 炸 OkHttp 线程
        if (SessionManager.isLoggedIn) {
            before.addHeader(ClientManager.HEADER_AUTH, SessionManager.getBearerToken())
        }
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("app-os", "ios")
            .addHeader("app-version", "7.13.4")
            .addHeader("x-client-time", requestNonce.xClientTime)
            .addHeader("x-client-hash", requestNonce.xClientHash)
        before.addHeader("user-agent", "PixivIOSApp/7.13.4 (iOS 16.0.3; iPhone13,3)")
        return before
    }
}