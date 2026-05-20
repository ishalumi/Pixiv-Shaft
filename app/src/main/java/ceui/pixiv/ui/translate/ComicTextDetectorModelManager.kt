package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.ModelDownloadManager

object ComicTextDetectorModelManager : ModelDownloadManager() {

    override val storageSubDir = "comic-text-detector-models"
    override val logTag = "CTDModel"
    override val readTimeoutSeconds = 120L
}
