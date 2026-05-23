package ceui.pixiv.ui.account

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.utils.Common
import kotlinx.coroutines.launch

/**
 * V3 账号备份 / 恢复页。承载在 [ceui.lisa.activities.TemplateActivity]（"邮箱备份"）。
 * 纯渲染 + 转发点击，所有行为都在 [EmailBackupV3ViewModel]。
 */
class EmailBackupV3Fragment : Fragment() {

    private val initialMode: EmailBackupV3ViewModel.Mode
        get() = if (arguments?.getString(ARG_MODE) == MODE_RESTORE) {
            EmailBackupV3ViewModel.Mode.RESTORE
        } else {
            EmailBackupV3ViewModel.Mode.BACKUP
        }

    private val viewModel by viewModels<EmailBackupV3ViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EmailBackupV3ViewModel(initialMode) as T
        }
    }

    private lateinit var segBackup: TextView
    private lateinit var segRestore: TextView
    private lateinit var description: TextView
    private lateinit var emailInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var btnRequestCode: TextView
    private lateinit var btnSubmit: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    /** Guards the text watchers while we push VM state back into the fields. */
    private var syncing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_email_backup_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { requireActivity().finish() }

        segBackup = view.findViewById(R.id.seg_backup)
        segRestore = view.findViewById(R.id.seg_restore)
        description = view.findViewById(R.id.description)
        emailInput = view.findViewById(R.id.email_input)
        codeInput = view.findViewById(R.id.code_input)
        btnRequestCode = view.findViewById(R.id.btn_request_code)
        btnSubmit = view.findViewById(R.id.btn_submit)
        statusText = view.findViewById(R.id.status_text)
        progress = view.findViewById(R.id.progress)

        segBackup.setOnClickListener { viewModel.setMode(EmailBackupV3ViewModel.Mode.BACKUP) }
        segRestore.setOnClickListener { viewModel.setMode(EmailBackupV3ViewModel.Mode.RESTORE) }
        emailInput.addTextChangedListener(afterChanged { if (!syncing) viewModel.onEmailChanged(it) })
        codeInput.addTextChangedListener(afterChanged { if (!syncing) viewModel.onCodeChanged(it) })
        btnRequestCode.setOnClickListener { viewModel.requestCode() }
        btnSubmit.setOnClickListener { viewModel.submit() }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { onEffect(it) }
            }
        }
    }

    private fun render(st: EmailBackupV3ViewModel.UiState) {
        val isBackup = st.mode == EmailBackupV3ViewModel.Mode.BACKUP
        styleSegment(segBackup, isBackup)
        styleSegment(segRestore, !isBackup)
        description.setText(
            if (isBackup) R.string.email_backup_desc_backup else R.string.email_backup_desc_restore
        )

        setTextIfDiffers(emailInput, st.email)

        val codeVisible = st.codeSent
        codeInput.visibility = if (codeVisible) View.VISIBLE else View.GONE
        btnSubmit.visibility = if (codeVisible) View.VISIBLE else View.GONE
        setTextIfDiffers(codeInput, st.code)

        btnRequestCode.text = when {
            st.resendCountdown > 0 -> getString(R.string.email_backup_resend_in, st.resendCountdown)
            st.codeSent -> getString(R.string.email_backup_resend)
            else -> getString(R.string.email_backup_get_code)
        }
        setEnabled(btnRequestCode, st.canRequestCode)

        btnSubmit.setText(
            if (isBackup) R.string.email_backup_do_backup else R.string.email_backup_do_restore
        )
        setEnabled(btnSubmit, st.canSubmit)

        statusText.visibility = if (st.status.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusText.text = st.status.orEmpty()
        progress.visibility = if (st.loading) View.VISIBLE else View.GONE
    }

    private fun onEffect(e: EmailBackupV3ViewModel.Effect) {
        when (e) {
            is EmailBackupV3ViewModel.Effect.Toast -> Common.showToast(e.msg)
            EmailBackupV3ViewModel.Effect.Finish -> requireActivity().finish()
            EmailBackupV3ViewModel.Effect.RestartApp -> {
                requireActivity().finish()
                Common.restart()
            }
        }
    }

    private fun styleSegment(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_v3_pill_primary else 0)
        tv.setTextColor(
            ContextCompat.getColor(requireContext(), if (selected) R.color.white else R.color.v3_text_2)
        )
    }

    private fun setEnabled(tv: TextView, enabled: Boolean) {
        tv.isEnabled = enabled
        tv.alpha = if (enabled) 1f else 0.45f
    }

    private fun setTextIfDiffers(field: EditText, value: String) {
        if (field.text.toString() == value) return
        syncing = true
        field.setText(value)
        field.setSelection(value.length)
        syncing = false
    }

    private inline fun afterChanged(crossinline onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
    }

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_BACKUP = "backup"
        const val MODE_RESTORE = "restore"
    }
}
