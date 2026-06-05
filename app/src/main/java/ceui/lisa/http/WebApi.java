package ceui.lisa.http;

import ceui.loxia.UserTagIllustBody;
import ceui.loxia.WebResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * www.pixiv.net 网页 ajax 接口(RxJava 版),配合 {@link Retro#getWebApi()} 使用。
 * 走 {@link ceui.loxia.WebHeaderInterceptor} 带上已同步的网页 cookie ——
 * 公开作品无 cookie 也能拿,R-18/私密作品需用户在设置里同步过 cookie。
 */
public interface WebApi {

    /**
     * issue #569: 按 Tag 筛选某画师的插画作品。offset/limit 翻页,limit 固定 48 跟网页一致。
     * sensitiveFilterMode=userSetting 跟随账号的敏感内容设置。
     */
    @GET("ajax/user/{userId}/illusts/tag")
    Observable<WebResponse<UserTagIllustBody>> getUserIllustsByTag(
            @Path("userId") long userId,
            @Query("tag") String tag,
            @Query("offset") int offset,
            @Query("limit") int limit,
            @Query("sensitiveFilterMode") String sensitiveFilterMode,
            @Query("lang") String lang);
}
