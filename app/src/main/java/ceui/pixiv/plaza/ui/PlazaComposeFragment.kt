package ceui.pixiv.plaza.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellPlazaAttachedIllustBinding
import ceui.lisa.databinding.FragmentPlazaComposeBinding
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide

/**
 * 发帖编辑器。Toolbar 右上「发布」menu + 文本框 + 已附 illust 横滑列表 + 「+ 添加」按钮。
 *
 * MVP 添加 illust 走输入 ID 弹窗 (从收藏 / 浏览历史 picker 后续补)。
 * server 已经会校验 illust 是否真存在,所以本地不做存在性校验。
 */
class PlazaComposeFragment : Fragment(R.layout.fragment_plaza_compose) {

    private val binding by viewBinding(FragmentPlazaComposeBinding::bind)
    private val viewModel by viewModels { PlazaComposeViewModel() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(getString(R.string.plaza_compose_title), showBack = true)
        binding.appBarLayout.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_send) { trySubmit(); true } else false
        }

        // 字数计数 (按 UTF-16 units 估算,提交时按 code point 严格校)
        binding.textCounter.text = getString(R.string.plaza_text_count, 0)
        binding.textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val codePoints = s?.toString()?.codePointCount(0, s.length) ?: 0
                binding.textCounter.text = getString(R.string.plaza_text_count, codePoints)
            }
        })

        // 已附 illust 横滑
        val attachedAdapter = AttachedIllustAdapter(onRemove = viewModel::removeIllust)
        binding.attachedList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.attachedList.adapter = attachedAdapter

        binding.btnAddIllust.setOnClickListener { showAddIllustDialog() }

        launchSuspend {
            viewModel.state.collect { s ->
                attachedAdapter.submit(s.attachedIllusts)
                binding.attachedList.isVisible = s.attachedIllusts.isNotEmpty()
                binding.attachedLabel.text =
                    getString(R.string.plaza_attached_count, s.attachedIllusts.size)
                // 发送中:发送按钮 disable + EditText readonly
                binding.appBarLayout.menu.findItem(R.id.action_send)?.isEnabled = !s.isSending
                binding.textInput.isEnabled = !s.isSending
                binding.btnAddIllust.isEnabled = !s.isSending
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaComposeViewModel.Event.Toast -> android.widget.Toast
                        .makeText(requireContext(), ev.message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                    PlazaComposeViewModel.Event.Sent -> {
                        // 发送成功后退出。Plaza feed 下次 onResume 时还要等用户主动
                        // 下拉刷新 —— 后续可加 LocalBroadcast 通知 PlazaFragment 刷新。
                        requireActivity().finish()
                    }
                }
            }
        }
    }

    private fun trySubmit() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            android.widget.Toast.makeText(
                requireContext(), R.string.plaza_login_required, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewModel.submit(requireContext(), binding.textInput.text?.toString() ?: "", uid)
    }

    private fun showAddIllustDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.plaza_attach_illust_id_hint)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.plaza_attach_illust_by_id)
            .setView(input)
            .setNegativeButton(R.string.plaza_delete_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val id = input.text?.toString()?.toLongOrNull() ?: return@setPositiveButton
                viewModel.attachIllust(id)
            }
            .show()
    }
}

private class AttachedIllustAdapter(
    private val onRemove: (Long) -> Unit,
) : RecyclerView.Adapter<AttachedIllustAdapter.VH>() {

    private val items = mutableListOf<Long>()

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(newItems: List<Long>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellPlazaAttachedIllustBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onRemove)
    }

    class VH(val binding: CellPlazaAttachedIllustBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(id: Long, onRemove: (Long) -> Unit) {
            // 编辑时只有用户输入的 illust id;没有 thumb_url,显示 ID 占位
            // (server 在拿到帖子后会补 meta,广场展示时就有缩略了)。
            binding.thumb.setImageDrawable(null)
            binding.placeholderId.isVisible = true
            binding.placeholderId.text = id.toString()
            binding.btnRemove.setOnClickListener { onRemove(id) }
        }
    }
}
