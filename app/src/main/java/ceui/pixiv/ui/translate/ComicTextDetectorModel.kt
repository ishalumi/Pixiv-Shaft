package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.DownloadableModel

enum class ComicTextDetectorModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    CTD_BASE(
        displayName = "Comic-Text-Detector",
        description = "漫画文本框检测专用，dmMaze/comic-text-detector，气泡级别一次到位，替代 PaddleOCR 通用检测",
        assetDir = "comic-text-detector",
        modelFiles = listOf(
            "comictextdetector.pt.onnx",
        ),
        sizeLabel = "57MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/comic-text-detector.zip",
    );
}
