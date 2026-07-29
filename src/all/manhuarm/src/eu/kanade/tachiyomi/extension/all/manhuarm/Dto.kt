package eu.kanade.tachiyomi.extension.all.manhuarm

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

@Serializable
class PageDto(
    @SerialName("image")
    val imageUrl: String,
    @SerialName("texts")
    @Serializable(with = DialogListSerializer::class)
    val dialogues: List<Dialog> = emptyList(),
)

@Serializable
data class Dialog(
    val x: Float,
    val y: Float,
    private val _width: Float,
    private val _height: Float,
    val angle: Float = 0f,
    val textByLanguage: Map<String, String> = emptyMap(),
    /**
     * The source/origin language text captured at parse time. This is set by
     * [DialogListSerializer] when the dialog is built so the renderer always
     * has a non-empty fallback even if the upstream [textByLanguage] map does
     * not contain the requested language key.
     */
    val sourceText: String = "",
) {
    var scale: Float = 1F
    val height: Float get() = scale * _height
    val width: Float get() = scale * _width

    /**
     * Returns the raw text. Tries the new-format "text" key, the legacy
     * language-keyed map (origin language) and finally the captured
     * [sourceText]. Cleaned to ensure error messages like [TERJEMAHAN GAGAL]
     * are never returned.
     */
    val text: String get() = (
        textByLanguage["text"]
            ?: textByLanguage[LANGUAGE_ORIGIN_FALLBACK]
            ?: sourceText
        ).cleanTranslationFailure()

    /**
     * Returns the text to render for the given language. The behaviour is:
     * 1. If the language has native translation disabled, look for the
     *    target language key (native translation in the OCR response).
     * 2. Otherwise look for the origin language key (source text used by the
     *    translation interceptor).
     * 3. Fall back to the new-format "text" key.
     * 4. Fall back to the captured [sourceText].
     * 5. Return an empty string if nothing is available - this prevents the
     *    entire request from failing when the OCR response is missing a key.
     * Always strips translation failure watermarks like [TERJEMAHAN GAGAL].
     */
    fun getTextBy(language: Language): String {
        val key = if (language.disableTranslator) language.target else language.origin
        return (
            textByLanguage[key]
                ?: textByLanguage["text"]
                ?: sourceText
            ).cleanTranslationFailure()
    }

    /**
     * Replace the rendered text while preserving the captured source text.
     */
    fun replaceText(value: String): Dialog {
        val cleanedValue = value.cleanTranslationFailure()
        return this.copy(
            textByLanguage = buildMap {
                putAll(textByLanguage)
                put("text", cleanedValue)
            },
            sourceText = if (sourceText.isNotBlank()) sourceText.cleanTranslationFailure() else cleanedValue,
        )
    }

    val centerY get() = height / 2 + y
    val centerX get() = width / 2 + x

    companion object {
        // Used as a last-ditch fallback when the JSON is missing language keys.
        const val LANGUAGE_ORIGIN_FALLBACK = "en"
    }
}

private object DialogListSerializer :
    JsonTransformingSerializer<List<Dialog>>(ListSerializer(Dialog.serializer())) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonArray) {
            return JsonArray(emptyList())
        }

        if (element.jsonArray.isEmpty()) {
            return JsonArray(emptyList())
        }

        var parsedCount = 0
        var skippedCount = 0
        var oldFormatCount = 0
        var newFormatCount = 0
        var unknownFormatCount = 0

        val result = JsonArray(
            element.jsonArray.mapNotNull { jsonElement ->
                try {
                    parsedCount++

                    // If the element is already a Dialog object (e.g. the JSON
                    // has been re-serialised by us), pass it through as-is so
                    // the round-trip works. The original OCR shape is
                    // [[x,y,w,h], "text"] or {"box":[...], "en":"..."} - in
                    // both cases we transform.
                    if (jsonElement is JsonObject &&
                        "x" in jsonElement &&
                        "y" in jsonElement &&
                        "_width" in jsonElement &&
                        "_height" in jsonElement
                    ) {
                        newFormatCount++
                        jsonElement
                    } else {
                        val coordinates = getCoordinates(jsonElement) ?: run {
                            skippedCount++
                            return@mapNotNull null
                        }
                        val (textByLanguage, sourceText) = getDialogs(jsonElement)

                        // Validate coordinates array has at least 4 elements
                        if (coordinates.size < 4) {
                            skippedCount++
                            return@mapNotNull null
                        }

                        val cleanedSourceText = sourceText.cleanTranslationFailure()

                        if (jsonElement is JsonArray) oldFormatCount++ else newFormatCount++

                        buildJsonObject {
                            put("x", coordinates[0])
                            put("y", coordinates[1])
                            put("_width", coordinates[2])
                            put("_height", coordinates[3])
                            put("textByLanguage", textByLanguage)
                            if (cleanedSourceText.isNotEmpty()) {
                                put("sourceText", JsonPrimitive(cleanedSourceText))
                            }
                        }
                    }
                } catch (_: Exception) {
                    unknownFormatCount++
                    null
                }
            },
        )

        if (skippedCount > 0 || unknownFormatCount > 0) {
            Log.w(
                TAG,
                "DialogListSerializer: parsed=$parsedCount, skipped=$skippedCount, " +
                    "oldFormat=$oldFormatCount, newFormat=$newFormatCount, " +
                    "unknownFormat=$unknownFormatCount",
            )
        }

        return result
    }

    private fun getCoordinates(element: JsonElement): JsonArray = when (element) {
        is JsonArray -> element.jsonArray[0].jsonArray

        else -> element.jsonObject["box"]?.jsonArray
            ?: throw IOException("Dialog box position not found")
    }

    /**
     * Returns a pair of (textByLanguage map, sourceText).
     *
     * Supported shapes:
     * - Old format: [[[x, y, w, h]], "text"] - the "text" string is stored
     *   under the "text" key and also captured as sourceText.
     * - New format: {"box": [...], "en": "...", "id": "...", "text": "..."}
     *   - All string values are kept in the map (so native translations
     *     survive the round trip), cleaned of failure markers.
     */
    private fun getDialogs(element: JsonElement): Pair<JsonObject, String> = when (element) {
        is JsonArray -> {
            val textRaw = element.jsonArray[1].contentOrNull ?: ""
            val textClean = textRaw.cleanTranslationFailure()
            buildJsonObject { put("text", JsonPrimitive(textClean)) } to textClean
        }

        else -> {
            val map = buildJsonObject {
                element.jsonObject.entries
                    .filter { it.value.isString }
                    .forEach { (key, value) ->
                        val rawStr = value.contentOrNull ?: ""
                        val cleanStr = rawStr.cleanTranslationFailure()
                        put(key, JsonPrimitive(cleanStr))
                    }
            }
            val sourceRaw = element.jsonObject["en"]?.contentOrNull
                ?: element.jsonObject["text"]?.contentOrNull
                ?: ""
            val sourceClean = sourceRaw.cleanTranslationFailure()
            map to sourceClean
        }
    }

    private val JsonElement.contentOrNull: String?
        get() = if (this.isString) jsonPrimitive.content else null

    private val JsonElement.isArray get() = this is JsonArray
    private val JsonElement.isObject get() = this is JsonObject
    private val JsonElement.isString get() = this.isObject.not() && this.isArray.not() && this.jsonPrimitive.isString

    private const val TAG = "Manhuarm.Dto"
}
