package ceui.pixiv.chat.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import ceui.lisa.R
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.widget.BaseBottomSheetDialogFragment
import ceui.lisa.databinding.ChatSheetMessageActionsBinding

/**
 * Bottom sheet presenting contextual actions for a chat message:
 * copy, reply, forward, delete.
 *
 * Communicates the chosen action back to the host fragment via
 * the Fragment Result API ([REQUEST_KEY] / [RESULT_ACTION]).
 */
class MessageActionsSheet : BaseBottomSheetDialogFragment(R.layout.chat_sheet_message_actions) {

    private val binding by viewBinding(ChatSheetMessageActionsBinding::bind)

    private val messageId: Long get() = requireArguments().getLong(ARG_MESSAGE_ID)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPreview.text = arguments?.getString(ARG_CONTENT).orEmpty()

        binding.actionCopy.setOnClickListener { finish(ACTION_COPY) }
        binding.actionReply.setOnClickListener { finish(ACTION_REPLY) }
        binding.actionForward.setOnClickListener { finish(ACTION_FORWARD) }
        binding.actionDelete.setOnClickListener { finish(ACTION_DELETE) }
    }

    private fun finish(action: String) {
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to action, RESULT_MESSAGE_ID to messageId))
        dismiss()
    }

    companion object {
        const val TAG = "MessageActionsSheet"
        const val REQUEST_KEY = "MessageActionsSheet:result"
        const val RESULT_ACTION = "action"
        const val RESULT_MESSAGE_ID = "messageId"

        const val ACTION_COPY = "copy"
        const val ACTION_REPLY = "reply"
        const val ACTION_FORWARD = "forward"
        const val ACTION_DELETE = "delete"

        private const val ARG_MESSAGE_ID = "messageId"
        private const val ARG_CONTENT = "content"

        fun newInstance(messageId: Long, content: String?): MessageActionsSheet =
            MessageActionsSheet().apply {
                arguments = bundleOf(ARG_MESSAGE_ID to messageId, ARG_CONTENT to content)
            }
    }
}
