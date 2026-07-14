package ceui.pixiv.widgets

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ceui.lisa.R
import ceui.lisa.databinding.DialogAlertBinding
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

class AlertPurpleDialog : PixivDialog(R.layout.dialog_alert) {

    private val args by lazy { AlertArgs(requireArguments()) }

    private class AlertArgs(b: Bundle) {
        val taskUuid: String = b.getString("taskUuid").orEmpty()
        val title: String = b.getString("title").orEmpty()
    }
    private val task: CompletableDeferred<Boolean>?
        get() {
            return viewModel.alertTaskPool[args.taskUuid]
        }
    private val binding by viewBinding(DialogAlertBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.title.text = args.title
        binding.ok.setOnClick {
            task?.complete(true)
            dismissAllowingStateLoss()
        }
        binding.cancel.setOnClick {
            task?.complete(false)
            dismissAllowingStateLoss()
        }
    }

    override fun performCancel() {
        super.performCancel()
        task?.cancel()
    }
}

suspend fun Fragment.alertYesOrCancel(title: String): Boolean {
    val dialogViewModel by activityViewModels<DialogViewModel>()
    val taskUUID = UUID.randomUUID().toString()
    val task = CompletableDeferred<Boolean>()
    task.invokeOnCompletion {
        dialogViewModel.alertTaskPool.remove(taskUUID)
    }
    dialogViewModel.alertTaskPool[taskUUID] = task
    val dialog = AlertPurpleDialog().apply {
        arguments = Bundle().apply {
            putString("taskUuid", taskUUID)
            putString("title", title)
        }
    }
    childFragmentManager.beginTransaction()
        .add(dialog, "AlertPurpleDialog-${taskUUID}")
        .commitAllowingStateLoss()
    return task.await()
}
