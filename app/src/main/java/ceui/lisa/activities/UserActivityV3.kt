package ceui.lisa.activities

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ActivityUserV3Binding
import ceui.lisa.databinding.ItemV3NavTagBinding
import ceui.lisa.fragments.FragmentUserIllust
import ceui.lisa.fragments.FragmentUserManga
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.models.UserBean
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.V3Palette
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressTextButton
import ceui.loxia.WebUserDetail
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MenuDialogBuilder
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch
import java.text.NumberFormat

private const val KEY_HAS_MANGA_TAB = "user_v3_has_manga_tab"

class UserActivityV3 : BaseActivity<ActivityUserV3Binding>() {

    private var userId = 0
    private lateinit var mUserViewModel: UserViewModel
    private lateinit var palette: V3Palette

    private enum class TabKind { ILLUST, MANGA, INFO }

    // 漫画 tab 是否插入要等 getUserDetail 返回后才知道 (profile.total_manga),
    // 所以先 2 tab 起步,有漫画再 notifyItemInserted 把它塞中间。
    // 旋转 / 进程重建时,从 savedInstanceState 提前恢复 MANGA,避免 FragmentStateAdapter
    // 把旋转前保存的 MANGA fragment state 当成「已废弃」清掉。
    private val tabKinds = mutableListOf(TabKind.ILLUST, TabKind.INFO)
    private var pagerAdapter: FragmentStateAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前预插 MANGA,这样 BaseActivity.onCreate 里跑 setupViewPager 时
        // FragmentStateAdapter 看到的 itemId 集合就包含 MANGA,旋转前保存的 fragment state
        // 才会被恢复而不是当成「已废弃」被清掉。
        if (savedInstanceState?.getBoolean(KEY_HAS_MANGA_TAB, false) == true) {
            if (!tabKinds.contains(TabKind.MANGA)) tabKinds.add(1, TabKind.MANGA)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HAS_MANGA_TAB, tabKinds.contains(TabKind.MANGA))
    }

    override fun initLayout(): Int = R.layout.activity_user_v3

    override fun initBundle(bundle: Bundle) {
        userId = bundle.getInt(Params.USER_ID)
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(this)[UserViewModel::class.java]
        mUserViewModel.user.observe(this) { data -> displayUser(data) }
        mUserViewModel.webUserDetail.observe(this) { detail ->
            if (detail != null) displayWebUserDetail(detail)
        }

        val entity = AppDatabase.getAppDatabase(this).searchDao().getUserMuteEntityByID(userId)
        mUserViewModel.isUserMuted.value = entity != null
        val block = AppDatabase.getAppDatabase(this).searchDao().getBlockMuteEntityByID(userId)
        mUserViewModel.isUserBlocked.value = block != null

        ObjectPool.get<UserBean>(userId.toLong()).observe(this) { user ->
            updateFollowState(user)
        }
    }

    override fun initView() {
        palette = V3Palette.from(this)
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
        baseBind.toolbar.setNavigationOnClickListener { finish() }

        // Apply theme-colored drawables to follow/unfollow buttons
        val density = resources.displayMetrics.density
        baseBind.follow.background = palette.pillPrimary(999f * density)
        baseBind.unfollow.background = palette.pillSecondary(999f * density, (1 * density).toInt())
        baseBind.unfollow.setTextColor(palette.textSecondary)

        // Toolbar alpha transition on scroll
        baseBind.toolbarLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val offset =
                    baseBind.toolbarLayout.height - Shaft.statusHeight - Shaft.toolbarHeight
                baseBind.appBar.addOnOffsetChangedListener { _, verticalOffset ->
                    val abs = Math.abs(verticalOffset)
                    when {
                        abs < 15 -> {
                            baseBind.profileHeader.alpha = 1.0f
                            baseBind.toolbarTitle.alpha = 0.0f
                        }

                        offset - abs < 15 -> {
                            baseBind.profileHeader.alpha = 0.0f
                            baseBind.toolbarTitle.alpha = 1.0f
                        }

                        else -> {
                            baseBind.profileHeader.alpha = 1 + verticalOffset.toFloat() / offset
                            baseBind.toolbarTitle.alpha = -verticalOffset.toFloat() / offset
                        }
                    }
                }
                baseBind.toolbarLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        setupViewPager()
    }

    override fun initData() {
        baseBind.progress.visibility = View.VISIBLE
        Retro.getAppApi().getUserDetail(userId)
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : NullCtrl<UserDetailResponse>() {
                override fun success(userResponse: UserDetailResponse) {
                    ObjectPool.updateUser(userResponse.user)
                    mUserViewModel.user.value = userResponse
                    // Record user visit history
                    runCatching {
                        val loxiaUser = Shaft.sGson.fromJson(Shaft.sGson.toJson(userResponse.user), ceui.loxia.User::class.java)
                        (application as? ceui.loxia.ServicesProvider)?.entityWrapper?.visitUser(this@UserActivityV3, loxiaUser)
                    }
                    Shaft.appViewModel.updateFollowUserStatus(
                        userId,
                        if (userResponse.user.isIs_followed)
                            AppLevelViewModel.FollowUserStatus.FOLLOWED
                        else
                            AppLevelViewModel.FollowUserStatus.NOT_FOLLOW
                    )
                }

                override fun must() {
                    baseBind.progress.visibility = View.INVISIBLE
                }
            })
        Retro.getAppApi().getFollowDetail(userId)
            .subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : NullCtrl<UserFollowDetail>() {
                override fun success(userFollowDetail: UserFollowDetail) {
                    var followStatus = AppLevelViewModel.FollowUserStatus.NOT_FOLLOW
                    if (userFollowDetail.isPublicFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC
                    } else if (userFollowDetail.isPrivateFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PRIVATE
                    } else if (userFollowDetail.isFollow) {
                        followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED
                    }
                    Shaft.appViewModel.updateFollowUserStatus(userId, followStatus)
                }
            })

        // Fetch supplementary data from Web API (bio HTML, badges, social links)
        lifecycleScope.launch {
            try {
                val resp = Client.webApi.getWebUserDetail(userId.toLong())
                resp.body?.let { mUserViewModel.webUserDetail.value = it }
            } catch (e: Exception) {
                timber.log.Timber.d(e, "Web user detail fetch failed for user=$userId")
            }
        }
    }

    override fun hideStatusBar(): Boolean = true

    private fun setupViewPager() {
        pagerAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabKinds.size

            override fun createFragment(position: Int): Fragment = when (tabKinds[position]) {
                TabKind.ILLUST -> FragmentUserIllust.newInstance(userId, false)
                TabKind.MANGA -> FragmentUserManga.newInstance(userId, false)
                TabKind.INFO -> UserV3InfoFragment()
            }

            // 稳定 id 让 notifyItemInserted 不会把已建的 fragment 推倒重来
            override fun getItemId(position: Int): Long = tabKinds[position].ordinal.toLong()

            override fun containsItem(itemId: Long): Boolean =
                tabKinds.any { it.ordinal.toLong() == itemId }
        }
        baseBind.viewPager.adapter = pagerAdapter
        // Tab 离屏保活,左右切换时不重建 view,体验更稳
        baseBind.viewPager.offscreenPageLimit = (tabKinds.size - 1).coerceAtLeast(1)

        TabLayoutMediator(baseBind.tabLayout, baseBind.viewPager) { tab, position ->
            tab.text = when (tabKinds[position]) {
                TabKind.ILLUST -> getString(R.string.string_246)
                TabKind.MANGA -> getString(R.string.string_233)
                TabKind.INFO -> getString(R.string.v3_label_profile_details)
            }
        }.attach()
    }

    private fun ensureMangaTab(totalManga: Int) {
        if (totalManga <= 0 || tabKinds.contains(TabKind.MANGA)) return
        // 保留用户当前所在 tab,别因为插入新 tab 把人「踢」到 MANGA
        val currentId = baseBind.viewPager.currentItem
            .takeIf { it in tabKinds.indices }
            ?.let { tabKinds[it].ordinal.toLong() }
        tabKinds.add(1, TabKind.MANGA)
        pagerAdapter?.notifyItemInserted(1)
        baseBind.viewPager.offscreenPageLimit = (tabKinds.size - 1).coerceAtLeast(1)
        // TabLayoutMediator 自身监听 adapter dataset 变化,会自动 re-populate tabs
        if (currentId != null) {
            val restored = tabKinds.indexOfFirst { it.ordinal.toLong() == currentId }
            if (restored >= 0 && restored != baseBind.viewPager.currentItem) {
                baseBind.viewPager.setCurrentItem(restored, false)
            }
        }
    }

    private fun updateFollowState(user: UserBean) {
        if (baseBind == null) return
        if (user.isIs_followed) {
            baseBind.follow.isVisible = false
            baseBind.unfollow.isVisible = true
            baseBind.unfollow.setOnClick { unfollowUser(it, userId) }
            baseBind.unfollow.setOnLongClickListener { true }
        } else {
            baseBind.unfollow.isVisible = false
            baseBind.follow.isVisible = true
            baseBind.follow.setOnClick { followUser(it, userId, Params.TYPE_PUBLIC) }
            baseBind.follow.setOnLongClickListener {
                followUser(it as ProgressTextButton, userId, Params.TYPE_PRIVATE)
                true
            }
        }
    }

    private fun displayUser(data: UserDetailResponse) {
        val isSelf = userId.toLong() == SessionManager.loggedInUid
        val profile = data.profile
        val user = data.user

        // 跟 UActivity 一致:有漫画作品才在中间插一个漫画 tab
        ensureMangaTab(profile.total_manga)

        // Banner
        val bannerUrl = profile.background_image_url
        if (!bannerUrl.isNullOrEmpty()) {
            baseBind.bannerImage.visibility = View.VISIBLE
            // 40% 黑色 overlay 贴在图片像素上 — 用 colorFilter 而不是单独 scrim view，
            // 和 CollapsingToolbarLayout 的 parallax + contentScrim 不会打架。
            baseBind.bannerImage.colorFilter = android.graphics.PorterDuffColorFilter(
                0x66000000.toInt(),
                android.graphics.PorterDuff.Mode.SRC_ATOP,
            )
            Glide.with(mContext).load(GlideUtil.getUrl(bannerUrl)).into(baseBind.bannerImage)
            baseBind.bannerImage.setOnClickListener {
                openImageDetail(bannerUrl, "user_${user.id}_profile_banner")
            }
        }

        // Avatar
        Glide.with(mContext).load(GlideUtil.getHead(user)).into(baseBind.userAvatar)
        val avatarUrl = user.profile_image_urls?.getMaxImage()
        if (!avatarUrl.isNullOrEmpty()) {
            baseBind.userAvatar.setOnClickListener {
                openImageDetail(avatarUrl, "user_${user.id}_avatar")
            }
        }

        // Premium
        if (user.isIs_premium) {
            baseBind.premiumRing.visibility = View.VISIBLE
            baseBind.premiumBadge.visibility = View.VISIBLE
        }

        // Name, handle
        baseBind.userName.text = user.name
        baseBind.userHandle.text = "@${user.account}"
        baseBind.toolbarTitle.text = user.name

        baseBind.userName.setOnClickListener { Common.copy(mContext, user.id.toString()) }
        baseBind.userName.setOnLongClickListener {
            Common.copy(mContext, user.name)
            true
        }

        // Follow layout
        if (isSelf) {
            baseBind.followLayout.visibility = View.GONE
        }

        // More menu
        baseBind.moreAction.visibility = View.VISIBLE
        baseBind.moreAction.setOnClickListener { showMoreMenu(data, isSelf) }

        // Stats row (following + mypixiv)
        val fmt = NumberFormat.getInstance()
        baseBind.statFollowingNum.text = fmt.format(profile.total_follow_users)
        baseBind.statMypixivNum.text = fmt.format(profile.total_mypixiv_users)

        baseBind.statFollowing.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "正在关注")
            startActivity(intent)
        }
        baseBind.statMypixiv.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "好P友")
            startActivity(intent)
        }

        // Navigation tags
        setupNavTags(data, isSelf)
    }

    private fun displayWebUserDetail(detail: WebUserDetail) {
        val dp = resources.displayMetrics.density
        val isSelf = userId.toLong() == SessionManager.loggedInUid

        // ── Badges row ───────────────────────────────────────────────
        var showBadges = false

        // "互相关注" badge — followedBack means the user follows us back
        if (!isSelf && detail.followedBack == true) {
            baseBind.badgeFollowsYou.isVisible = true
            baseBind.badgeFollowsYou.background = makeBadgeBg(dp, palette.alpha20)
            showBadges = true
        }

        // 好P友 badge
        if (detail.isMypixiv == true) {
            baseBind.badgeMypixiv.isVisible = true
            baseBind.badgeMypixiv.background = makeBadgeBg(dp, palette.alpha20)
            showBadges = true
        }

        // Official badge — pinned next to the name, not in badges_row.
        if (detail.official == true) {
            baseBind.badgeOfficial.isVisible = true
        }

        if (showBadges) {
            baseBind.badgesRow.isVisible = true
        }

        // ── Message button ───────────────────────────────────────────
        // 1v1 chat over shaft-api-v2 (anonymous of pixiv; identity = uid only,
        // see docs/ws-chat-integration.md). Show only when:
        //   - not myself
        //   - I'm logged in (ShaftHmacAuthProvider needs SessionManager.loggedInUid > 0)
        //   - pixiv's `canSendMessage` flag is true (preserves existing UX guard)
        if (!isSelf && detail.canSendMessage == true && ceui.pixiv.session.SessionManager.isLoggedIn) {
            baseBind.msgBtn.isVisible = true
            baseBind.msgBtn.background = makeBadgeBg(dp, palette.alpha20)
            baseBind.msgBtn.imageTintList = android.content.res.ColorStateList.valueOf(palette.textAccent)
            baseBind.msgBtn.setOnClick {
                val intent = android.content.Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天室")
                intent.putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, userId.toLong())
                startActivity(intent)
            }
        }
    }

    private fun makeBadgeBg(dp: Float, strokeColor: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f * dp
            setColor(0x0AFFFFFF)
            setStroke((1 * dp).toInt(), strokeColor)
        }
    }

    private fun setupNavTags(data: UserDetailResponse, isSelf: Boolean) {
        val profile = data.profile
        val tags = mutableListOf<Pair<String, String>>()  // label -> fragment/count
        val counts = mutableListOf<Int>()

        if (profile.total_illusts > 0) {
            tags.add(getString(R.string.string_246) to "插画作品")
            counts.add(profile.total_illusts)
        }
        if (profile.total_manga > 0) {
            tags.add(getString(R.string.string_233) to "漫画作品")
            counts.add(profile.total_manga)
        }
        if (profile.total_illust_series > 0) {
            tags.add(getString(R.string.string_230) to "漫画系列作品")
            counts.add(profile.total_illust_series)
        }
        if (profile.total_novels > 0) {
            tags.add(getString(R.string.string_237) to "小说作品")
            counts.add(profile.total_novels)
        }
        if (profile.total_novel_series > 0) {
            tags.add(getString(R.string.string_257) to "小说系列作品")
            counts.add(profile.total_novel_series)
        }
        if (profile.total_illust_bookmarks_public > 0 || isSelf) {
            tags.add(getString(R.string.string_164) to "插画/漫画收藏")
            counts.add(profile.total_illust_bookmarks_public)
        }
        tags.add(getString(R.string.string_192) to "小说收藏")
        counts.add(0)
        tags.add(getString(R.string.string_436) to "相关用户")
        counts.add(0)

        if (tags.isNotEmpty()) {
            baseBind.navLabel.visibility = View.VISIBLE
            baseBind.navTags.visibility = View.VISIBLE

            baseBind.navTags.adapter = object : TagAdapter<Pair<String, String>>(tags) {
                override fun getView(
                    parent: FlowLayout,
                    position: Int,
                    item: Pair<String, String>?
                ): View {
                    val binding = ItemV3NavTagBinding.inflate(
                        LayoutInflater.from(mContext), parent, false
                    )
                    binding.tagName.text = item?.first ?: ""
                    // Themed pill border
                    val dp = resources.displayMetrics.density
                    binding.root.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 999f * dp
                        setColor(0x08FFFFFF)
                        setStroke((1 * dp).toInt(), palette.alpha20)
                    }
                    val count = counts.getOrNull(position) ?: 0
                    if (count > 0) {
                        binding.tagCount.visibility = View.VISIBLE
                        binding.tagCount.text = NumberFormat.getInstance().format(count)
                        binding.tagCount.setTextColor(palette.textAccent)
                        binding.tagCount.background = palette.tagCountBg(999f * dp)
                    }
                    return binding.root
                }
            }
            baseBind.navTags.setOnTagClickListener { _, position, _ ->
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.USER_ID, data.user.userId)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, tags[position].second)
                startActivity(intent)
                true
            }
        }
    }

    private fun showMoreMenu(data: UserDetailResponse, isSelf: Boolean) {
        val isMuted = mUserViewModel.isUserMuted.value == true
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (data.profile.total_illusts > 0) {
            labels.add("跳转到插画…")
            actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.ILLUST, "插画作品") }
        }
        if (data.profile.total_manga > 0) {
            labels.add("跳转到漫画…")
            actions.add { jumpTo(data.user.id, UserIllustJumpHelper.Kind.MANGA, "漫画作品") }
        }
        if (data.profile.total_illusts > 0) {
            labels.add(getString(R.string.bulk_user_menu_download_all_illust))
            actions.add {
                startBatchFetch(
                    userIdLong = data.user.id.toLong(),
                    type = ceui.pixiv.db.queue.WorkType.ILLUST,
                    authorName = data.user.name ?: "user",
                )
            }
        }
        if (data.profile.total_manga > 0) {
            labels.add(getString(R.string.bulk_user_menu_download_all_manga))
            actions.add {
                startBatchFetch(
                    userIdLong = data.user.id.toLong(),
                    type = ceui.pixiv.db.queue.WorkType.MANGA,
                    authorName = data.user.name ?: "user",
                )
            }
        }
        labels.add(getString(R.string.bulk_user_menu_open_download_manager))
        actions.add {
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理") // route key
            startActivity(intent)
        }
        if (!isSelf) {
            labels.add(
                if (isMuted) getString(R.string.cancel_block_this_users_work)
                else getString(R.string.block_this_users_work)
            )
            actions.add {
                // mute switch lives in UserV3InfoFragment now; just push state via shared
                // UserViewModel and the fragment's observer keeps the switch in sync.
                if (isMuted) {
                    PixivOperate.unMuteUser(data.user)
                    mUserViewModel.isUserMuted.value = false
                } else {
                    PixivOperate.muteUser(data.user)
                    mUserViewModel.isUserMuted.value = true
                }
                mUserViewModel.refreshEvent.value = Event(100, 0L)
            }
        }
        if (labels.isEmpty()) return

        MenuDialogBuilder(mActivity)
            .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
            .addItems(labels.toTypedArray()) { dialog: DialogInterface, which: Int ->
                dialog.dismiss()
                actions.getOrNull(which)?.invoke()
            }
            .show()
    }

    private fun startBatchFetch(userIdLong: Long, type: String, authorName: String) {
        val typeLabel = getString(
            if (type == ceui.pixiv.db.queue.WorkType.MANGA) R.string.bulk_type_manga
            else R.string.bulk_type_illust
        )
        val source = ceui.pixiv.ui.bulk.AuthorWorksSource(
            userId = userIdLong,
            type = type,
        )
        val taskName = getString(R.string.bulk_task_name, authorName, typeLabel)
        ceui.pixiv.ui.bulk.FetchProgressDialog.show(
            supportFragmentManager,
            ceui.pixiv.ui.bulk.bulkEnqueueIllusts(source, taskName),
        )
        // 不在这里 notifyNewItems —— 等 fetcher 全部抓完才统一唤醒消费者
    }

    private fun jumpTo(userID: Int, kind: UserIllustJumpHelper.Kind, fragmentTag: String) {
        UserIllustJumpHelper.showJumpDialog(this, userID, kind) { offset, pickedDate ->
            if (isFinishing || isDestroyed) return@showJumpDialog
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, fragmentTag)
            intent.putExtra(Params.USER_ID, userID)
            intent.putExtra(Params.INITIAL_OFFSET, offset)
            if (pickedDate != null) intent.putExtra(Params.TARGET_DATE, pickedDate)
            startActivity(intent)
        }
    }

    private fun openImageDetail(imageUrl: String, saveName: String) {
        startActivity(Intent(mContext, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情")
            putExtra(Params.URL, imageUrl)
            putExtra(Params.TITLE, saveName)
        })
    }
}
