package ceui.pixiv.ui.translate

interface Translator {
    suspend fun translate(input: String, outputLang: String): String
}
