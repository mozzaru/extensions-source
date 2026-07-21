package eu.kanade.tachiyomi.extension.all.manhuarm

class MachineTranslationsFactoryUtils

data class Language(
    val lang: String,
    val target: String = lang,
    val origin: String = "en",
    val fontSize: Int = 28,
    val dialogBoxScale: Float = 1f,
    val disableFontSettings: Boolean = false,
    val disableWordBreak: Boolean = false,
    val disableTranslator: Boolean = false,
    val translateSynopsis: Boolean = false,
    val supportNativeTranslation: Boolean = false,
    val fontName: String = "comic_neue_bold",
)

val TRANSLATION_FAILED_REGEX = Regex(
    """(?i)\[?\s*(?:TERJEMA\s*HAN|TERJEMAHAN|TRANSLAT(?:ION|ED|E)|TRADUCT(?:ION)?|TRADUCC(?:IÓ|I)N|FALHA\s*NA\s*TRADUÇ[ÃA]O|TRANSLATE|翻译)\s*(?:GAGAL|FAILED|FAILURE|ÉCHOUÉE|ECHOUEE|FALLIDA|失败)\s*\]?|\[\s*(?:TERJEMA\s*HAN|TERJEMAHAN|TRANSLAT(?:ION|ED|E)|TRADUCT(?:ION)?|TRADUCC(?:IÓ|I)N|FALHA\s*NA\s*TRADUÇ[ÃA]O|TRANSLATE|翻译)\s*\]""",
)

fun String.cleanTranslationFailure(): String {
    if (this.isBlank()) return ""
    var cleaned = this.replace(TRANSLATION_FAILED_REGEX, "").trim()
    cleaned = cleaned.replace(Regex("""^[\s:\-–—|]+"""), "").trim()
    return cleaned
}
