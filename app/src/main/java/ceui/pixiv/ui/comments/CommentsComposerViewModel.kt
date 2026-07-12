package ceui.pixiv.ui.comments

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.ObjectType
import ceui.pixiv.feeds.FeedItem

/**
 * 评论输入框状态 + 网络动作 + 「网络结果如何编辑列表」的纯函数。列表本身仍归
 * [ceui.pixiv.feeds.FeedViewModel] 持有（两个 VM 不互相持有引用，不产生循环依赖）——
 * Fragment 只做一件事：把 apply* 的返回值转手扔给 `feedViewModel.mutateItems { ... }`，
 * 「顶层插入 / 挂进回复线程 / 过滤删除」这些分支判断全部收口在本 VM，不散落进 Fragment。
 */
class CommentsComposerViewModel(private val args: CommentsFragmentArgs) : ViewModel() {

    val editingComment = MutableLiveData("")
    val replyToComment = MutableLiveData<Comment?>(null)
    val replyParentComment = MutableLiveData<Long?>(null)

    fun startReply(comment: Comment, parentCommentId: Long) {
        replyToComment.value = comment
        replyParentComment.value = parentCommentId
    }

    fun cancelReply() {
        replyToComment.value = null
        replyParentComment.value = null
    }

    suspend fun showMoreReply(commentId: Long): List<Comment> {
        val resp = Client.appApi.getIllustReplyComments(args.objectType, commentId)
        return filterSpamComments(resp.comments)
    }

    /** 成功发出返回 (parentCommentId, 新评论)；parentCommentId<=0 表示顶层新评论。文本为空/请求无返回体时 null。 */
    suspend fun sendComment(): Pair<Long, Comment>? {
        val content = editingComment.value.orEmpty()
        if (content.isBlank()) return null
        val parentCommentId = replyParentComment.value?.takeIf { it > 0L }
            ?: (replyToComment.value?.id ?: 0L)
        val resp = if (parentCommentId > 0L) {
            if (args.objectType == ObjectType.ILLUST) {
                Client.appApi.postIllustComment(args.objectId, content, parentCommentId)
            } else {
                Client.appApi.postNovelComment(args.objectId, content, parentCommentId)
            }
        } else {
            if (args.objectType == ObjectType.ILLUST) {
                Client.appApi.postIllustComment(args.objectId, content)
            } else {
                Client.appApi.postNovelComment(args.objectId, content)
            }
        }
        cancelReply()
        editingComment.value = ""
        val comment = resp.comment ?: return null
        return parentCommentId to comment
    }

    suspend fun deleteComment(commentId: Long) {
        Client.appApi.deleteComment(args.objectType, commentId)
    }

    /** 发评论成功后如何编辑列表：parentCommentId<=0 顶层插入，否则挂进对应主评论的回复线程。 */
    fun applySentComment(
        items: List<FeedItem>,
        parentCommentId: Long,
        comment: Comment,
        illustArthurId: Long,
    ): List<FeedItem> {
        return if (parentCommentId > 0L) {
            items.map { item ->
                if (item is CommentFeedItem && item.comment.id == parentCommentId) {
                    item.copy(childComments = listOf(comment) + item.childComments)
                } else item
            }
        } else {
            listOf<FeedItem>(CommentFeedItem(comment, illustArthurId)) + items
        }
    }

    /** 删评论成功后如何编辑列表：顶层评论直接摘除，子评论从所属回复线程里过滤掉。 */
    fun applyDeletedComment(items: List<FeedItem>, commentId: Long, parentCommentId: Long): List<FeedItem> {
        return if (parentCommentId > 0L) {
            items.map { item ->
                if (item is CommentFeedItem && item.comment.id == parentCommentId) {
                    val remaining = item.childComments.filterNot { it.id == commentId }
                    item.copy(
                        comment = item.comment.copy(has_replies = remaining.isNotEmpty()),
                        childComments = remaining,
                    )
                } else item
            }
        } else {
            items.filterNot { it is CommentFeedItem && it.comment.id == commentId }
        }
    }

    /** 「查看回复」展开成功后，把拉到的子评论塞进对应主评论。 */
    fun applyExpandedReplies(items: List<FeedItem>, commentId: Long, children: List<Comment>): List<FeedItem> {
        return items.map { item ->
            if (item is CommentFeedItem && item.comment.id == commentId) {
                item.copy(childComments = children)
            } else item
        }
    }
}
