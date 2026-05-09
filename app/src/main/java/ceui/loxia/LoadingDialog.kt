package ceui.loxia

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog

class LoadingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return QMUITipDialog.Builder(requireContext())
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .create()
    }

    companion object {
        fun show(fragment: Fragment): LoadingDialog {
            val dialog = LoadingDialog()
            dialog.show(fragment.parentFragmentManager, "loading_dialog")
            return dialog
        }
    }
}
