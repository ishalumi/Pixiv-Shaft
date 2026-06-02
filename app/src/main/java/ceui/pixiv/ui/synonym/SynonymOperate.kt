package ceui.pixiv.ui.synonym

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.http.Retro
import ceui.lisa.models.NullResponse
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.db.synonym.SynonymDao
import ceui.pixiv.db.synonym.SynonymTagEntity
import ceui.pixiv.db.synonym.SynonymTargetEntity
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.Executors

/**
 * 同义词词典共享操作弹窗（issue #904）。
 *
 * 所有入口（3 个插画详情页 / 小说页 / 匹配框 / 管理页）共用这一套 QMUIDialog 流程，
 * 保证不同详情页实现之间行为一致（用户设置必须全局适配原则）。
 *
 * 弹窗一律挂 [QMUISkinManager.defaultInstance] 跟随日夜皮肤。
 * DB 写入后由 Room LiveData 驱动各处 UI 自动刷新，不需要手动回调链。
 *
 * 线程模型：全表级查询（getRecentTargets）放 [dbExecutor]；单行索引查询（getTargetByName /
 * getSynonymInTarget 等，毫秒级）沿用项目 PixivOperate 的主线程同步模式。
 */
object SynonymOperate {

    /** 「添加为同义词」菜单最多列出的目标标签数（词典可能数千个目标，全列菜单不可用） */
    private const val MENU_TARGET_LIMIT = 20

    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synonym-operate-db").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dao(context: Context): SynonymDao =
        AppDatabase.getAppDatabase(context).synonymDao()

    private fun skin(context: Context): QMUISkinManager =
        QMUISkinManager.defaultInstance(context)

    /** 后台查询回主线程弹窗前，确认宿主 Activity 还活着（避免 BadTokenException）。
     *  view 的 context 可能是 ContextThemeWrapper，要沿 wrapper 链找到底层 Activity 再判活。 */
    private fun canShowDialog(context: Context): Boolean {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) {
                return !c.isFinishing && !c.isDestroyed
            }
            c = c.baseContext
        }
        return true
    }

    // ────────────────────────────────────────────────────────────────
    // 入口 1：长按作品标签 →「添加为同义词标签」
    // ────────────────────────────────────────────────────────────────

    /**
     * issue：点击后显示 新建目标标签 与 目标标签列表作为选项；
     * 不论如何选择，长按标签存在 tag 译文时，备注自动填入译文。
     * 目标过多时只列最近 [MENU_TARGET_LIMIT] 个（全量在管理页里）。
     *
     * @param workId 当前作品 id（>0 时新建目标标签会自动把该作品收藏进同名收藏标签，issue 要求的闭环）
     * @param workType [Params.TYPE_ILLUST] / [Params.TYPE_NOVEL]，与 workId 配套
     */
    @JvmStatic
    @JvmOverloads
    fun showAddAsSynonymDialog(
        context: Context,
        tagName: String,
        translatedName: String?,
        workId: Long = 0,
        workType: String? = null,
        onDone: (() -> Unit)? = null,
    ) {
        dbExecutor.execute {
            val targets = dao(context).getRecentTargets(MENU_TARGET_LIMIT)
            mainHandler.post {
                if (!canShowDialog(context)) return@post
                showAddAsSynonymMenu(context, tagName, translatedName, targets, workId, workType, onDone)
            }
        }
    }

    private fun showAddAsSynonymMenu(
        context: Context,
        tagName: String,
        translatedName: String?,
        targets: List<SynonymTargetEntity>,
        workId: Long,
        workType: String?,
        onDone: (() -> Unit)?,
    ) {
        // 菜单结构：[新建目标标签] + 最近 N 个目标 + [手动输入目标标签名…]
        // 最后一项兜底覆盖「目标很多、想加进第 N+1 个旧目标」的场景（issue 要求所有目标可选）
        val labels = ArrayList<String>(targets.size + 2)
        labels.add(context.getString(R.string.synonym_new_target))
        targets.forEach { labels.add(it.name) }
        labels.add(context.getString(R.string.synonym_pick_target_by_name))

        QMUIDialog.MenuDialogBuilder(context)
            .setSkinManager(skin(context))
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        // 新建目标标签，建好后把当前长按标签挂进去；
                        // issue：新建目标标签时默认把该作品收藏进同名收藏标签
                        showCreateTargetDialog(context) { target ->
                            addSynonymChecked(context, target, tagName, translatedName, onDone)
                            if (workId > 0 && workType != null) {
                                autoBookmarkWork(context, workId, workType, target.name)
                            }
                        }
                    }
                    labels.size - 1 -> {
                        // 手动输入已有目标标签名（不在最近列表里的旧目标）
                        showPickTargetByNameDialog(context, tagName, translatedName, onDone)
                    }
                    else -> {
                        addSynonymChecked(context, targets[which - 1], tagName, translatedName, onDone)
                    }
                }
            }
            .show()
    }

    /**
     * issue 闭环：新建目标标签时，把当前作品收藏进同名收藏标签。
     *
     * v2/xxx/bookmark/add 是全量覆盖 tags —— 必须先查作品现有收藏标签做合并，
     * 否则已收藏作品的旧标签会被清掉。
     */
    private fun autoBookmarkWork(
        context: Context,
        workId: Long,
        workType: String,
        targetName: String,
    ) {
        val isNovel = workType == Params.TYPE_NOVEL
        val defaultRestrict = if (Shaft.sSettings.isPrivateStar) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC
        val detailApi = if (isNovel) {
            Retro.getAppApi().getNovelBookmarkTags(workId.toInt())
        } else {
            Retro.getAppApi().getIllustBookmarkTags(workId.toInt())
        }

        detailApi
            .subscribeOn(Schedulers.newThread())
            .flatMap { detail ->
                // 合并已注册标签 + 新目标标签
                val registered = detail.bookmark_detail?.tags
                    ?.filter { it.isIs_registered }
                    ?.mapNotNull { it.name }
                    .orEmpty()
                val merged = (registered + targetName).distinct().toTypedArray()
                val restrict = detail.bookmark_detail?.restrict ?: defaultRestrict
                if (isNovel) {
                    Retro.getAppApi().postLikeNovelWithTags(workId.toInt(), restrict, *merged)
                } else {
                    Retro.getAppApi().postLikeIllustWithTags(workId.toInt(), restrict, *merged)
                }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : ErrorCtrl<NullResponse>() {
                override fun next(response: NullResponse) {
                    Common.showToast(context.getString(R.string.synonym_auto_bookmarked, targetName))
                    // 通知列表页刷新该作品的收藏状态（与 FragmentSB.setFollowed 同款广播）
                    val action = if (isNovel) Params.LIKED_NOVEL else Params.LIKED_ILLUST
                    val intent = Intent(action)
                    intent.putExtra(Params.ID, workId.toInt())
                    intent.putExtra(Params.IS_LIKED, true)
                    LocalBroadcastManager.getInstance(context.applicationContext).sendBroadcast(intent)
                }
            })
    }

    /** 输入已有目标标签名，把当前标签挂进去（目标数超过菜单上限时的兜底入口） */
    private fun showPickTargetByNameDialog(
        context: Context,
        tagName: String,
        translatedName: String?,
        onDone: (() -> Unit)?,
    ) {
        val builder = QMUIDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.synonym_pick_target_by_name)
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                val target = dao(context).getTargetByName(name)
                if (target == null) {
                    Common.showToast(context.getString(R.string.synonym_target_not_found, name))
                    return@addAction
                }
                dialog.dismiss()
                addSynonymChecked(context, target, tagName, translatedName, onDone)
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 入口 2：新建目标标签
    // ────────────────────────────────────────────────────────────────

    /**
     * issue：自定义的目标标签中不能有空格（Pixiv 使用空格作为标签间的分隔符）。
     */
    @JvmStatic
    @JvmOverloads
    fun showCreateTargetDialog(
        context: Context,
        onCreated: ((SynonymTargetEntity) -> Unit)? = null,
    ) {
        val builder = QMUIDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.synonym_new_target)
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name.contains(' ') || name.contains('　')) {
                    Common.showToast(context.getString(R.string.synonym_target_name_invalid))
                    return@addAction
                }
                if (dao(context).getTargetByName(name) != null) {
                    Common.showToast(context.getString(R.string.synonym_target_exists))
                    return@addAction
                }
                val target = SynonymTargetEntity(name = name)
                var id = dao(context).insertTarget(target)
                if (id == -1L) {
                    // IGNORE 策略下唯一索引冲突（并发写入等罕见情况）→ 复用已存在的行
                    id = dao(context).getTargetByName(name)?.id ?: return@addAction
                }
                dialog.dismiss()
                onCreated?.invoke(target.copy(id = id))
            }
            .show()
    }

    /** 重命名目标标签 */
    @JvmStatic
    fun showRenameTargetDialog(context: Context, target: SynonymTargetEntity) {
        val builder = QMUIDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.synonym_rename_target_title, target.name))
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_new_target_hint))
            .setDefaultText(target.name)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name.contains(' ') || name.contains('　')) {
                    Common.showToast(context.getString(R.string.synonym_target_name_invalid))
                    return@addAction
                }
                val existing = dao(context).getTargetByName(name)
                if (existing != null && existing.id != target.id) {
                    Common.showToast(context.getString(R.string.synonym_target_exists))
                    return@addAction
                }
                dao(context).renameTarget(target.id, name)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 删除目标标签（issue：删除要有二次确认；不同步删除用户已收藏的标签） */
    @JvmStatic
    fun showDeleteTargetDialog(context: Context, target: SynonymTargetEntity, synonymCount: Int) {
        QMUIDialog.MessageDialogBuilder(context)
            .setTitle(target.name)
            .setSkinManager(skin(context))
            .setMessage(
                context.getString(R.string.synonym_delete_target_confirm, target.name, synonymCount)
            )
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.synonym_delete)) { dialog, _ ->
                dao(context).deleteTargetWithSynonyms(target.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 长按目标标签 → 管理菜单（匹配框 / 管理页共用） */
    @JvmStatic
    fun showTargetMenu(context: Context, target: SynonymTargetEntity, synonymCount: Int) {
        val labels = arrayOf(
            context.getString(R.string.synonym_add_synonym),
            context.getString(R.string.synonym_rename),
            context.getString(R.string.synonym_delete),
            context.getString(R.string.synonym_new_target),
        )
        QMUIDialog.MenuDialogBuilder(context)
            .setSkinManager(skin(context))
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showAddSynonymToTargetDialog(context, target)
                    1 -> showRenameTargetDialog(context, target)
                    2 -> showDeleteTargetDialog(context, target, synonymCount)
                    3 -> showCreateTargetDialog(context)
                }
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 同义词标签操作
    // ────────────────────────────────────────────────────────────────

    /** 给指定目标手动添加同义词（先输名字，再输备注，两步 EditTextDialog） */
    @JvmStatic
    fun showAddSynonymToTargetDialog(context: Context, target: SynonymTargetEntity) {
        val nameBuilder = QMUIDialog.EditTextDialogBuilder(context)
        nameBuilder.setTitle(R.string.synonym_add_synonym)
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_synonym_name_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = nameBuilder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Common.showToast(context.getString(R.string.synonym_synonym_name_invalid))
                    return@addAction
                }
                dialog.dismiss()
                // 第二步：备注（可空，留空 = 无备注）。「取消」是真取消，整个添加操作中止。
                val remarkBuilder = QMUIDialog.EditTextDialogBuilder(context)
                remarkBuilder.setTitle(name)
                    .setSkinManager(skin(context))
                    .setPlaceholder(context.getString(R.string.synonym_remark_hint))
                    .setInputType(InputType.TYPE_CLASS_TEXT)
                    .addAction(context.getString(R.string.cancel)) { d2, _ -> d2.dismiss() }
                    .addAction(context.getString(R.string.add)) { d2, _ ->
                        val remark = remarkBuilder.editText.text?.toString()?.trim()
                            ?.ifEmpty { null }
                        addSynonymChecked(context, target, name, remark, null)
                        d2.dismiss()
                    }
                    .show()
            }
            .show()
    }

    /** 长按同义词 → 管理菜单（匹配框 / 管理页共用） */
    @JvmStatic
    fun showSynonymMenu(context: Context, synonym: SynonymTagEntity) {
        val hasRemark = !synonym.remark.isNullOrBlank()
        val labels = ArrayList<String>(5)
        val actions = ArrayList<() -> Unit>(5)

        labels.add(context.getString(R.string.synonym_rename))
        actions.add { showRenameSynonymDialog(context, synonym) }

        labels.add(context.getString(R.string.synonym_edit_remark))
        actions.add { showEditRemarkDialog(context, synonym) }

        if (hasRemark) {
            // issue：将备注添加为同义词（在该同义词所属的目标标签下）
            labels.add(context.getString(R.string.synonym_remark_to_synonym))
            actions.add {
                val target = dao(context).getTargetById(synonym.targetId)
                if (target != null) {
                    addSynonymChecked(context, target, synonym.remark!!.trim(), null, null)
                }
            }
            // issue：点击备注 → 搜索备注（管理页单击整行搜的是原文，备注搜索从这里走）
            labels.add(context.getString(R.string.synonym_search_remark))
            actions.add {
                val intent = android.content.Intent(context, ceui.lisa.activities.SearchActivity::class.java)
                intent.putExtra(ceui.lisa.utils.Params.KEY_WORD, synonym.remark!!.trim())
                intent.putExtra(ceui.lisa.utils.Params.INDEX, 0)
                context.startActivity(intent)
            }
        }

        labels.add(context.getString(R.string.synonym_delete))
        actions.add { showDeleteSynonymDialog(context, synonym) }

        QMUIDialog.MenuDialogBuilder(context)
            .setSkinManager(skin(context))
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].invoke()
            }
            .show()
    }

    /** 重命名同义词（issue：重命名时一定要显示原本的同义词；备注输入框默认值填入已有备注） */
    @JvmStatic
    fun showRenameSynonymDialog(context: Context, synonym: SynonymTagEntity) {
        val builder = QMUIDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.synonym_rename_synonym_title, synonym.name))
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_synonym_name_hint))
            .setDefaultText(synonym.name)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Common.showToast(context.getString(R.string.synonym_synonym_name_invalid))
                    return@addAction
                }
                dao(context).updateSynonym(synonym.id, name, synonym.remark)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 编辑备注（增 / 改 / 清空即删） */
    @JvmStatic
    fun showEditRemarkDialog(context: Context, synonym: SynonymTagEntity) {
        val builder = QMUIDialog.EditTextDialogBuilder(context)
        builder.setTitle(synonym.name)
            .setSkinManager(skin(context))
            .setPlaceholder(context.getString(R.string.synonym_remark_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
        if (!synonym.remark.isNullOrBlank()) {
            builder.setDefaultText(synonym.remark)
        }
        builder
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val remark = builder.editText.text?.toString()?.trim()?.ifEmpty { null }
                dao(context).updateSynonymRemark(synonym.id, remark)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 删除同义词（二次确认） */
    @JvmStatic
    fun showDeleteSynonymDialog(context: Context, synonym: SynonymTagEntity) {
        QMUIDialog.MessageDialogBuilder(context)
            .setTitle(synonym.name)
            .setSkinManager(skin(context))
            .setMessage(context.getString(R.string.synonym_delete_synonym_confirm, synonym.name))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.synonym_delete)) { dialog, _ ->
                dao(context).deleteSynonymById(synonym.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 内部：带重复检测的添加
    // ────────────────────────────────────────────────────────────────

    /**
     * issue 重复检测要求：
     * - 同一目标下已存在 → toast 提示
     * - 同一同义词已被其他目标标签使用 → 提示，但给出按钮允许强制添加
     */
    private fun addSynonymChecked(
        context: Context,
        target: SynonymTargetEntity,
        name: String,
        remark: String?,
        onDone: (() -> Unit)?,
    ) {
        val dao = dao(context)
        if (dao.getSynonymInTarget(target.id, name) != null) {
            Common.showToast(context.getString(R.string.synonym_already_in_target))
            return
        }
        val usedElsewhere = dao.getSynonymsByName(name)
            .firstOrNull { it.targetId != target.id }
        if (usedElsewhere != null) {
            val otherTarget = dao.getTargetById(usedElsewhere.targetId)
            QMUIDialog.MessageDialogBuilder(context)
                .setTitle(name)
                .setSkinManager(skin(context))
                .setMessage(
                    context.getString(
                        R.string.synonym_in_other_target_confirm,
                        name, otherTarget?.name ?: "?", target.name
                    )
                )
                .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                .addAction(context.getString(R.string.synonym_force_add)) { dialog, _ ->
                    doInsertSynonym(context, target, name, remark)
                    dialog.dismiss()
                    onDone?.invoke()
                }
                .create()
                .show()
            return
        }
        doInsertSynonym(context, target, name, remark)
        onDone?.invoke()
    }

    private fun doInsertSynonym(
        context: Context,
        target: SynonymTargetEntity,
        name: String,
        remark: String?,
    ) {
        dao(context).insertSynonym(
            SynonymTagEntity(targetId = target.id, name = name, remark = remark)
        )
        Common.showToast(context.getString(R.string.synonym_added_to, target.name))
    }
}
