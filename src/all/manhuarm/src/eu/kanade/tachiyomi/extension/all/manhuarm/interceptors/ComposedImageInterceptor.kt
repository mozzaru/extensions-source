package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import eu.kanade.tachiyomi.extension.all.manhuarm.Dialog
import eu.kanade.tachiyomi.extension.all.manhuarm.Language
import eu.kanade.tachiyomi.extension.all.manhuarm.Manhuarm.Companion.PAGE_REGEX
import eu.kanade.tachiyomi.extension.all.manhuarm.cleanTranslationFailure
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// The Interceptor joins the dialogues and pages of the manga.
class ComposedImageInterceptor(
    val language: Language,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not()) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?: emptyList()

        val imageRequest = request.newBuilder()
            .url(url)
            .build()

        val response = chain.proceed(imageRequest)

        if (response.isSuccessful.not()) {
            return response
        }

        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())!!
            .copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmap)

        val composeStarted = System.currentTimeMillis()
        var drawnCount = 0
        var skippedCount = 0

        dialogues.forEach { dialog ->
            dialog.scale = language.dialogBoxScale
            val text = dialog.getTextBy(language).cleanUp().cleanTranslationFailure()
            // Skip empty dialogs. Drawing an empty box would just show a
            // white rectangle with no text inside, which is what the user
            // sees when the OCR response or translation API is missing
            // the text for a particular bubble or had failure markers like [TERJEMAHAN GAGAL].
            if (text.isBlank()) {
                skippedCount++
                return@forEach
            }
            val textPaint = createTextPaint(selectFontFamily())
            val dialogBox = createDialogBox(dialog, text, textPaint)
            val y = getYAxis(textPaint, dialog, dialogBox)
            canvas.draw(textPaint, dialogBox, dialog, dialog.x, y)
            drawnCount++
        }

        val composeElapsed = System.currentTimeMillis() - composeStarted
        val now = System.currentTimeMillis()
        if (dialogues.isNotEmpty()) {
            val pageNum = extractPageNumber(url)
            val chapterKey = extractChapterKey(url)
            val gapSinceLast = chapterKey?.let { key ->
                lastRenderTime[key]?.let { prev -> now - prev }
            }
            chapterKey?.let { lastRenderTime[it] = now }

            val timingInfo = buildString {
                append("drew=$drawnCount, skipped=$skippedCount")
                if (pageNum != null) append(", page=$pageNum")
                if (gapSinceLast != null) append(", gap=${gapSinceLast}ms")
                append(", took=${composeElapsed}ms (lang=${language.lang})")
            }
            Log.d(TAG, "Composed image: $timingInfo")
            if (drawnCount == 0) {
                Log.w(
                    TAG,
                    "All ${dialogues.size} dialogs were empty for ${language.lang}! " +
                        "Check the OCR data and translation pipeline.",
                )
            }
        }

        val output = ByteArrayOutputStream()

        val ext = url.substringBefore("#")
            .substringAfterLast(".")
            .lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.WEBP
        }

        bitmap.compress(format, 100, output)

        val responseBody = output.toByteArray().toResponseBody(mediaType)

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private fun createTextPaint(font: Typeface?): TextPaint {
        val defaultTextSize = language.fontSize.pt
        return TextPaint().apply {
            color = Color.BLACK
            textSize = defaultTextSize
            font?.let {
                typeface = it
            }
            isAntiAlias = true
        }
    }

    private fun selectFontFamily(): Typeface? {
        if (language.disableFontSettings) {
            return null
        }
        return loadFont("${language.fontName}.ttf")
    }

    /**
     * Loads font from the `assets/fonts` directory within the APK
     *
     * @param fontName The name of the font to load.
     * @return A `Typeface` instance of the loaded font or `null` if an error occurs.
     *
     * Example usage:
     * <pre>{@code
     *   val typeface: TypeFace? = loadFont("filename.ttf")
     * }</pre>
     */
    private fun loadFont(fontName: String): Typeface? = try {
        this::class.java.classLoader!!
            .getResourceAsStream("assets/fonts/$fontName")
            .toTypeface(fontName)
    } catch (_: Exception) {
        null
    }

    private fun InputStream.toTypeface(fontName: String): Typeface? {
        val fontFile = File.createTempFile(fontName, fontName.substringAfter("."))
        this.copyTo(FileOutputStream(fontFile))
        return Typeface.createFromFile(fontFile)
    }

    /**
     * Adjust the text to the center of the dialog box when feasible.
     */
    private fun getYAxis(textPaint: TextPaint, dialog: Dialog, dialogBox: StaticLayout): Float {
        val fontHeight = textPaint.fontMetrics.let { it.bottom - it.top }

        val dialogBoxLineCount = dialog.height / fontHeight

        // Centers text in y for dialogues smaller than the dialog box
        return when {
            dialogBox.lineCount < dialogBoxLineCount -> dialog.centerY - dialogBox.lineCount / 2f * fontHeight
            else -> dialog.y
        }
    }

    private fun createDialogBox(dialog: Dialog, text: String, textPaint: TextPaint): StaticLayout {
        var dialogBox = createBoxLayout(dialog, text, textPaint)

        // The best way I've found to adjust the text in the dialog box (Especially in long dialogues)
        while (dialogBox.height > dialog.height) {
            textPaint.textSize -= 0.5f
            dialogBox = createBoxLayout(dialog, text, textPaint)
        }

        textPaint.color = Color.BLACK
        textPaint.bgColor = Color.WHITE

        return dialogBox
    }

    private fun createBoxLayout(dialog: Dialog, text: String, textPaint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, dialog.width.toInt()).apply {
            setAlignment(Layout.Alignment.ALIGN_CENTER)
            setIncludePad(language.disableFontSettings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (language.disableWordBreak) {
                    setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                    setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    return@apply
                }
                setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
            }
        }.build()
    }

    private fun String.cleanUp(): String = Jsoup.parse(this).text()

    private fun Canvas.draw(textPaint: TextPaint, layout: StaticLayout, dialog: Dialog, x: Float, y: Float) {
        save()
        translate(x, y)
        rotate(dialog.angle)
        drawTextOutline(textPaint, layout)
        drawText(textPaint, layout)
        restore()
    }

    private fun Canvas.drawText(textPaint: TextPaint, layout: StaticLayout) {
        textPaint.style = Paint.Style.FILL
        layout.draw(this)
    }

    private fun Canvas.drawTextOutline(textPaint: TextPaint, layout: StaticLayout) {
        val foregroundColor = textPaint.color
        val style = textPaint.style

        textPaint.strokeWidth = 5F
        textPaint.color = textPaint.bgColor
        textPaint.style = Paint.Style.FILL_AND_STROKE

        layout.draw(this)

        textPaint.color = foregroundColor
        textPaint.style = style
    }

    // https://pixelsconverter.com/pt-to-px
    private val Int.pt: Float get() = this / SCALED_DENSITY

    companion object {
        // w3: Absolute Lengths [...](https://www.w3.org/TR/css3-values/#absolute-lengths)
        const val SCALED_DENSITY = 0.75f // 1px = 0.75pt
        val mediaType = "image/png".toMediaType()
        private const val TAG = "Manhuarm.Render"
        private val lastRenderTime = ConcurrentHashMap<String, Long>()
        private val PAGE_NUM_REGEX = Regex("""/(\d+)\.[a-z]+(?:\?|#|$)""", RegexOption.IGNORE_CASE)

        /** URL up to (excluding) the last path segment + fragment, e.g.
         *  `https://site.com/wp-content/uploads/2023/01/001.jpg#...` → `https://site.com/wp-content/uploads/2023/01/` */
        fun extractChapterKey(url: String): String? {
            val cleanUrl = url.substringBefore("#")
            val slashIdx = cleanUrl.lastIndexOf('/')
            return if (slashIdx > 0) cleanUrl.substring(0, slashIdx + 1) else null
        }

        fun extractPageNumber(url: String): Int? {
            val cleanUrl = url.substringBefore("#")
            return PAGE_NUM_REGEX.find(cleanUrl)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
