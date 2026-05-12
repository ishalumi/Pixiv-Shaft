package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.databinding.FragmentEventHistoryBinding
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.events.EventReporter
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.viewBinding
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 操作记录 — 调 shaft-api-v2 /api/v1/events/history 拉当前 client_id 自己的事件流。
 *
 * 不分类型 (全部 bookmark/download/follow 一起按时间倒序);后续要分类可以再加 tab。
 * 服务端按 id DESC 排,翻页用 next_before 游标。
 *
 * 没事件时显示 empty 占位。client_id 还没生成 (EventReporter.init 没跑完) 时同样 empty。
 */
class FragmentEventHistory : Fragment(R.layout.fragment_event_history) {

    private val binding by viewBinding(FragmentEventHistoryBinding::bind)
    private val items = mutableListOf<ListItemHolder>()
    private var nextBefore: Long? = null
    private var loading = false
    private var exhausted = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.event_history)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        // Debug 包右上角放一个复制 client_id 的入口 (release 不挂菜单,资源也不浪费)
        if (BuildConfig.IS_DEBUG_MODE) {
            binding.toolbar.inflateMenu(R.menu.event_history_menu)
            binding.toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_copy_client_id) {
                    val cid = EventReporter.currentClientId()
                    if (cid.isEmpty()) {
                        Common.showToast(getString(R.string.event_history_client_id_not_ready))
                    } else {
                        ClipBoardUtils.putTextIntoClipboard(requireContext(), cid, false)
                        // 完整 64 字符 hex 当 toast 太长,只回显前 12 位让用户确认是哪个 client
                        Common.showToast(getString(
                            R.string.event_history_client_id_copied, cid.take(12)
                        ))
                    }
                    true
                } else false
            }
        }

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            loadFirst { adapter.submitList(items.toList()) }
        }
        binding.refreshLayout.setOnLoadMoreListener {
            loadMore { adapter.submitList(items.toList()) }
        }

        loadFirst { adapter.submitList(items.toList()) }
    }

    private fun loadFirst(onDone: () -> Unit) {
        val cid = EventReporter.currentClientId()
        if (cid.isEmpty()) {
            // client_id 没初始化好,直接展示 empty,不去打服务端 (会被 400 bad_client_id 拒)
            items.clear()
            nextBefore = null
            exhausted = true
            updateEmpty()
            binding.refreshLayout.finishRefresh(true)
            onDone()
            return
        }
        if (loading) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            // 注意:UI 更新只在 try / catch 里做,不在 finally 里。viewLifecycleOwner
            // 的 scope 在 view 销毁时会取消,这时再访问 binding 会因 ViewBindingDelegate
            // 检查 lifecycle.currentState 抛 IllegalStateException。
            // CancellationException 单独识别 → 直接 return,不动 UI 也不 log。
            try {
                val resp = ShaftApiV2Client.service.eventsHistory(
                    clientId = cid, limit = 50, eventType = null, before = null,
                )
                items.clear()
                items.addAll(resp.items.map { EventHistoryHolder(it) })
                nextBefore = resp.next_before
                exhausted = resp.next_before == null
                updateEmpty()
                binding.refreshLayout.finishRefresh(true)
                binding.refreshLayout.setNoMoreData(exhausted)
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // view 已销毁,binding 访问会炸,这里啥也不做
            } catch (e: Exception) {
                Timber.tag("EventHistory").w(e, "loadFirst failed")
                binding.refreshLayout.finishRefresh(false)
                onDone()
            } finally {
                loading = false
            }
        }
    }

    private fun loadMore(onDone: () -> Unit) {
        val cid = EventReporter.currentClientId()
        val before = nextBefore
        if (cid.isEmpty() || before == null || exhausted) {
            binding.refreshLayout.finishLoadMoreWithNoMoreData()
            onDone()
            return
        }
        if (loading) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = ShaftApiV2Client.service.eventsHistory(
                    clientId = cid, limit = 50, eventType = null, before = before,
                )
                items.addAll(resp.items.map { EventHistoryHolder(it) })
                nextBefore = resp.next_before
                exhausted = resp.next_before == null
                updateEmpty()
                if (exhausted) binding.refreshLayout.finishLoadMoreWithNoMoreData()
                else binding.refreshLayout.finishLoadMore(true)
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // view 已销毁,跳过 UI 更新
            } catch (e: Exception) {
                Timber.tag("EventHistory").w(e, "loadMore failed")
                binding.refreshLayout.finishLoadMore(false)
                onDone()
            } finally {
                loading = false
            }
        }
    }

    private fun updateEmpty() {
        binding.emptyLayout.isVisible = items.isEmpty()
    }
}
