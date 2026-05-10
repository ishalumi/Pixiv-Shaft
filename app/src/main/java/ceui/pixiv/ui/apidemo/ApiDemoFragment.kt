package ceui.pixiv.ui.apidemo

import android.view.View
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentApiDemoBinding
import ceui.lisa.fragments.SwipeFragment
import ceui.lisa.network.ShaftApiV2Client
import com.google.gson.JsonSyntaxException
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiDemoFragment : SwipeFragment<FragmentApiDemoBinding>() {

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun initLayout() {
        mLayoutID = R.layout.fragment_api_demo
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout = baseBind.refreshLayout
    override fun enableRefresh(): Boolean = false
    override fun enableLoadMore(): Boolean = false

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.toolbarTitle.text = getString(R.string.api_demo_entry)
        baseBind.baseUrlText.text = ShaftApiV2Client.BASE_URL

        baseBind.btnHealth.setOnClickListener {
            run("GET /health") { ShaftApiV2Client.service.health().toString() }
        }
        baseBind.btn404.setOnClickListener {
            run("GET /does-not-exist") { ShaftApiV2Client.service.probe404().toString() }
        }
        baseBind.btnParseFail.setOnClickListener {
            run("GET /ping") { ShaftApiV2Client.service.pingAsHealth().toString() }
        }
        baseBind.btnClear.setOnClickListener {
            baseBind.outputText.text = ""
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        appendLine("[${tsFormat.format(Date())}] $label  ...")
        viewLifecycleOwner.lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val result = block()
                val ms = System.currentTimeMillis() - t0
                appendLine("  ok ${ms}ms  $result")
            } catch (e: HttpException) {
                val ms = System.currentTimeMillis() - t0
                appendLine("  HTTP ${e.code()} ${ms}ms  ${e.message()}")
            } catch (e: JsonSyntaxException) {
                val ms = System.currentTimeMillis() - t0
                appendLine("  parse fail ${ms}ms  ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                appendLine("  ${e.javaClass.simpleName} ${ms}ms  ${e.message ?: ""}")
            }
        }
    }

    private fun appendLine(s: String) {
        val cur = baseBind.outputText.text?.toString().orEmpty()
        baseBind.outputText.text = if (cur.isEmpty()) s else "$cur\n$s"
        baseBind.outputScroll.post {
            baseBind.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
