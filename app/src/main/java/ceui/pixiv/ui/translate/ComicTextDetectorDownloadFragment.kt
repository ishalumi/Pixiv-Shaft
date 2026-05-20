package ceui.pixiv.ui.translate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class ComicTextDetectorDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: ComicTextDetectorModel.CTD_BASE.name
        return ComicTextDetectorModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = ComicTextDetectorModelManager
    override fun titleRes() = R.string.string_ctd_download_title
    override fun subtitleRes() = R.string.string_ctd_download_subtitle
    override fun doneTextRes() = R.string.string_ctd_download_done

    companion object {
        private const val ARG_MODEL_NAME = "ctd_model_name"

        @JvmStatic
        fun newInstance(modelName: String): ComicTextDetectorDownloadFragment {
            return ComicTextDetectorDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
