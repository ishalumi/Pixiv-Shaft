package ceui.pixiv.ui.apidemo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.network.ShaftApiV2Client
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiDemoFragment : Fragment(R.layout.fragment_api_demo) {

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var outputText: TextView
    private lateinit var outputScroll: NestedScrollView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { activity?.finish() }
        view.findViewById<TextView>(R.id.baseUrlText).text =
            ShaftApiV2Client.BASE_URL

        outputText = view.findViewById(R.id.outputText)
        outputScroll = view.findViewById(R.id.outputScroll)

        view.findViewById<Button>(R.id.btnHealth).setOnClickListener {
            runApi("GET /health") { ShaftApiV2Client.service.health().toString() }
        }
        view.findViewById<Button>(R.id.btn404).setOnClickListener {
            runApi("GET /does-not-exist") { ShaftApiV2Client.service.probe404().toString() }
        }
        view.findViewById<Button>(R.id.btnParseFail).setOnClickListener {
            runApi("GET /ping") { ShaftApiV2Client.service.pingAsHealth().toString() }
        }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener {
            outputText.text = ""
        }
    }

    private fun runApi(label: String, block: suspend () -> String) {
        appendLine("[${tsFormat.format(Date())}] $label  ...")
        viewLifecycleOwner.lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val result = block()
                appendLine("  ok ${System.currentTimeMillis() - t0}ms  $result")
            } catch (e: HttpException) {
                appendLine("  HTTP ${e.code()} ${System.currentTimeMillis() - t0}ms  ${e.message()}")
            } catch (e: JsonSyntaxException) {
                appendLine("  parse fail ${System.currentTimeMillis() - t0}ms  ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                appendLine("  ${e.javaClass.simpleName} ${System.currentTimeMillis() - t0}ms  ${e.message ?: ""}")
            }
        }
    }

    private fun appendLine(s: String) {
        val cur = outputText.text?.toString().orEmpty()
        outputText.text = if (cur.isEmpty()) s else "$cur\n$s"
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
