package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.loxia.Comment
import ceui.loxia.ObjectPool

sealed class ArtworkDetailItem {

    data class Hero(val illust: IllustsBean) : ArtworkDetailItem()

    data class Series(val illust: IllustsBean) : ArtworkDetailItem()

    data class Desc(val caption: String) : ArtworkDetailItem()

    data class Stats(val illust: IllustsBean) : ArtworkDetailItem()

    data class Tags(val illust: IllustsBean) : ArtworkDetailItem()

    data class Artist(
        val illust: IllustsBean,
        val isFollowed: Boolean = resolveIsFollowed(illust)
    ) : ArtworkDetailItem() {
        companion object {
            // illust.user is just a snapshot. When the author page (UActivity /
            // UserActivityV3) opens, it calls ObjectPool.updateUser with a fresh
            // UserBean — replacing the pool entry rather than mutating it. After
            // that, illust.user is an orphan: a follow toggle on the author page
            // mutates the new pooled instance but not illust.user. Read the
            // canonical state from the pool first, fall back only when the pool
            // has no entry.
            private fun resolveIsFollowed(illust: IllustsBean): Boolean {
                val user = illust.user ?: return false
                return ObjectPool.get<UserBean>(user.id.toLong()).value?.isIs_followed
                    ?: user.isIs_followed
            }
        }
    }

    data class DetailPanel(val illust: IllustsBean) : ArtworkDetailItem()

    data class Comments(
        val liveData: LiveData<List<Comment>?>,
        val illustId: Int,
        val illustTitle: String,
        val illustAuthorId: Int
    ) : ArtworkDetailItem()

    data class AuthorWorks(
        val liveData: LiveData<List<IllustsBean>?>,
        val authorName: String,
        val userId: Int
    ) : ArtworkDetailItem()

    data class RelatedHeader(
        val liveData: LiveData<Boolean?>,
        val illustId: Int,
        val illustTitle: String
    ) : ArtworkDetailItem()
}
