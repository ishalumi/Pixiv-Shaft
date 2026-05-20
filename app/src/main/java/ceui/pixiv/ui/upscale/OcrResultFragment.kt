package ceui.pixiv.ui.upscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.blankj.utilcode.util.BarUtils

class OcrResultFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ocr_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // EdgeToEdge 模式下 toolbar 不能 fitsSystemWindows="true",改 runtime padding
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        toolbar.setNavigationOnClickListener { activity?.finish() }

        val content = view.findViewById<LinearLayout>(R.id.ocr_content)
        val texts = arguments?.getStringArrayList(KEY_TEXTS) ?: return

        for (jaText in texts) {
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_ocr_text, content, false)
            card.findViewById<TextView>(R.id.ocr_text).text = jaText
            card.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR", jaText))
                Common.showToast(R.string.msg_copied)
            }
            content.addView(card)
        }
    }

    companion object {
        const val KEY_TEXTS = "ocr_texts"

        @JvmStatic
        fun newInstance(texts: ArrayList<String>): OcrResultFragment {
            return OcrResultFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(KEY_TEXTS, texts)
                }
            }
        }
    }
}
