package ceui.pixiv.ui.account

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.lisa.models.UserModel
import ceui.lisa.utils.Local
import ceui.loxia.AccountResponse
import ceui.loxia.BindConfirmReq
import ceui.loxia.Client
import ceui.loxia.EmailReq
import ceui.loxia.RestoreConfirmReq
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber

/**
 * Email-bound account backup / restore. Talks to pixshaft-api's account
 * endpoints (signed automatically by the OkHttp interceptor). This holds ALL of the
 * feature's behaviour — the fragment only renders [state] and reacts to
 * [effects]; it carries no business logic of its own.
 *
 *  - BACKUP  (logged-in): request code → confirm → upload the current
 *    [AccountResponse] (encrypted at rest server-side).
 *  - RESTORE (login page): request code → confirm → server returns the stored
 *    account JSON, which we persist exactly like a fresh OAuth login, then ask
 *    the UI to relaunch.
 */
class EmailBackupV3ViewModel(initialMode: Mode) : ViewModel() {

    enum class Mode { BACKUP, RESTORE }

    data class UiState(
        val mode: Mode,
        val email: String = "",
        val code: String = "",
        val codeSent: Boolean = false,
        val resendCountdown: Int = 0,
        val loading: Boolean = false,
        val status: String? = null,
    ) {
        val canRequestCode: Boolean
            get() = !loading && resendCountdown == 0 && isValidEmail(email)
        val canSubmit: Boolean
            get() = !loading && codeSent && code.length == 6
    }

    sealed class Effect {
        data class Toast(val msg: String) : Effect()
        object Finish : Effect()       // backup done → close the page
        object RestartApp : Effect()   // restore done → relaunch as logged-in
    }

    private val gson = Gson()

    private val _state = MutableLiveData(UiState(mode = initialMode))
    val state: LiveData<UiState> = _state

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var countdownJob: Job? = null

    private fun cur() = _state.value!!
    private fun update(block: (UiState) -> UiState) {
        _state.value = block(cur())
    }

    fun setMode(mode: Mode) {
        if (cur().mode == mode) return
        countdownJob?.cancel()
        _state.value = UiState(mode = mode) // mode switch resets the whole flow
    }

    fun onEmailChanged(value: String) = update { it.copy(email = value.trim()) }
    fun onCodeChanged(value: String) = update { it.copy(code = value.trim()) }

    fun requestCode() {
        val st = cur()
        if (!st.canRequestCode) return
        if (st.mode == Mode.BACKUP && !SessionManager.isLoggedIn) {
            emit(Effect.Toast("请先登录 Pixiv 账号后再备份"))
            return
        }
        update { it.copy(loading = true, status = null) }
        viewModelScope.launch {
            try {
                if (st.mode == Mode.BACKUP) {
                    Client.pixshaft.bindRequest(EmailReq(st.email))
                    onCodeSent(st.email)
                } else {
                    val ack = Client.pixshaft.restoreRequest(EmailReq(st.email))
                    if (ack.found) {
                        onCodeSent(st.email)
                    } else {
                        update { it.copy(loading = false, status = "该邮箱还没有账号备份") }
                    }
                }
            } catch (e: Exception) {
                update { it.copy(loading = false, status = errorText(e)) }
            }
        }
    }

    fun submit() {
        val st = cur()
        if (!st.canSubmit) return
        update { it.copy(loading = true, status = null) }
        viewModelScope.launch {
            try {
                if (st.mode == Mode.BACKUP) doBackup(st) else doRestore(st)
            } catch (e: Exception) {
                update { it.copy(loading = false, status = errorText(e)) }
            }
        }
    }

    private suspend fun doBackup(st: UiState) {
        val account = SessionManager.loggedInAccount.value
        if (account?.access_token == null) {
            update { it.copy(loading = false, status = "登录状态已失效，请重新登录") }
            return
        }
        Client.pixshaft.bindConfirm(BindConfirmReq(st.email, st.code, account))
        // Clear loading before the one-shot effects so the UI never stays stuck
        // on the spinner if the page was backgrounded and missed Finish.
        update { it.copy(loading = false, status = null) }
        emit(Effect.Toast("已备份到 ${st.email}"))
        emit(Effect.Finish)
    }

    private suspend fun doRestore(st: UiState) {
        val resp = Client.pixshaft.restoreConfirm(RestoreConfirmReq(st.email, st.code))
        val account = resp.account
        if (account?.access_token == null) {
            update { it.copy(loading = false, status = "备份数据异常，无法恢复") }
            return
        }
        withContext(Dispatchers.IO) { persistLogin(account) }
        update { it.copy(loading = false, status = "恢复成功") }
        emit(Effect.Toast("恢复成功，正在重新登录"))
        emit(Effect.RestartApp)
    }

    /**
     * Reconstruct a fully-logged-in state from a restored [AccountResponse],
     * mirroring the canonical OAuth path in OutWakeActivity: legacy prefs +
     * MMKV session + the account-switcher Room row. [AccountResponse] and
     * [UserModel] are field-compatible, so a gson round-trip bridges them.
     */
    private fun persistLogin(account: AccountResponse) {
        val userModel = gson.fromJson(gson.toJson(account), UserModel::class.java)
        userModel.user?.setIs_login(true)
        Local.saveUser(userModel)
        // postUpdateSession (postValue), NOT updateSession (setValue): we run on
        // Dispatchers.IO and setValue off the main thread would throw. It writes
        // MMKV synchronously first, so the upcoming Common.restart() reads it back.
        SessionManager.postUpdateSession(userModel)
        val entity = UserEntity().apply {
            loginTime = System.currentTimeMillis()
            userID = userModel.user?.id ?: 0
            userGson = Shaft.sGson.toJson(Local.getUser())
        }
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertUser(entity)
    }

    private fun onCodeSent(email: String) {
        update { it.copy(loading = false, codeSent = true, status = "验证码已发送至 $email") }
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (n in RESEND_SECONDS downTo 1) {
                update { it.copy(resendCountdown = n) }
                delay(1000)
            }
            update { it.copy(resendCountdown = 0) }
        }
    }

    private fun emit(effect: Effect) {
        _effects.tryEmit(effect)
    }

    private fun errorText(e: Throwable): String {
        if (e is HttpException) {
            val code = e.code()
            val key = try {
                e.response()?.errorBody()?.string()?.let {
                    gson.fromJson(it, ErrorBody::class.java)?.error
                }
            } catch (_: Exception) {
                null
            }
            return when (key) {
                "bad_code" -> "验证码不正确"
                "expired" -> "验证码已过期，请重新获取"
                "too_many_attempts" -> "尝试次数过多，请重新获取验证码"
                "no_code" -> "请先获取验证码"
                "not_found" -> "该邮箱没有可恢复的备份"
                "bad_email" -> "邮箱格式不正确"
                "account_required" -> "登录状态异常，无法备份"
                "send_failed" -> "验证码发送失败，请稍后再试"
                "rate_limited" -> "操作过于频繁，请稍后再试"
                else -> if (code == 429) "操作过于频繁，请稍后再试" else "请求失败（$code）"
            }
        }
        Timber.w(e, "email-backup request failed")
        return "网络异常，请检查连接后重试"
    }

    private data class ErrorBody(val error: String? = null)
}

private const val RESEND_SECONDS = 60
private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private fun isValidEmail(e: String): Boolean =
    e.length in 3..254 && EMAIL_RE.matches(e)
