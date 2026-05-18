package ceui.pixiv.ui.translate

object NoOpTranslator : Translator {
    override suspend fun translate(input: String, outputLang: String): String = input
}
