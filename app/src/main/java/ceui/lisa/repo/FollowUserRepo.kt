package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListUser
import io.reactivex.Observable

class FollowUserRepo(
    private val userID: Int,
    private val starType: String?,
) : RemoteRepo<ListUser>() {

    private var startOffset: Int = 0

    fun setStartOffset(offset: Int) {
        startOffset = offset.coerceAtLeast(0)
    }

    override fun initApi(): Observable<ListUser> {
        val offsetParam: Int? = if (startOffset > 0) startOffset else null
        return Retro.getAppApi().getFollowUser(userID, starType, offsetParam)
    }

    override fun initNextApi(): Observable<ListUser> {
        return Retro.getAppApi().getNextUser(nextUrl)
    }
}
