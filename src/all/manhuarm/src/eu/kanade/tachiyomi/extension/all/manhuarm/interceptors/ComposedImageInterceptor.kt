package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.app.Application
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
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.extension.all.manhuarm.Dialog
import eu.kanade.tachiyomi.extension.all.manhuarm.Language
import eu.kanade.tachiyomi.extension.all.manhuarm.Manhuarm.Companion.PAGE_REGEX
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream

// The Interceptor joins the dialogues and pages of the manga.
@RequiresApi(Build.VERSION_CODES.O)
class ComposedImageInterceptor(
    val language: Language,
) : Interceptor {

    private val context: Application by injectLazy()

    private val fontCache = mutableMapOf<String, Typeface>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not()) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?.filter { it.getTextBy(language).isNotBlank() }
            ?: emptyList()

        val imageRequest = request.newBuilder()
            .url(url)
            .build()

        if (dialogues.isEmpty()) {
            return chain.proceed(imageRequest)
        }

        val response = chain.proceed(imageRequest)

        if (response.isSuccessful.not()) {
            return response
        }

        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())!!
            .copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmap)

        dialogues.forEach { dialog ->
            dialog.scale = language.dialogBoxScale
            val textPaint = createTextPaint(selectFontFamily())
            val dialogBox = createDialogBox(dialog, textPaint)
            val y = getYAxis(textPaint, dialog, dialogBox)
            canvas.draw(textPaint, dialogBox, dialog, dialog.x, y)
        }

        val output = ByteArrayOutputStream()

        val ext = url.substringBefore("#")
            .substringAfterLast(".")
            .lowercase()
        val format = when (ext) {
            "png" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            "jpeg", "jpg" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
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
        if (fontCache.containsKey(fontName)) {
            fontCache[fontName]
        } else {
            Typeface.createFromAsset(context.assets, "fonts/$fontName").also {
                fontCache[fontName] = it
            }
        }
    } catch (e: Exception) {
        null
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

    private fun createDialogBox(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        var dialogBox = createBoxLayout(dialog, textPaint)

        // The best way I've found to adjust the text in the dialog box (Especially in long dialogues)
        while (dialogBox.height > dialog.height && textPaint.textSize >= 1.0f) {
            textPaint.textSize -= 0.5f
            dialogBox = createBoxLayout(dialog, textPaint)
        }

        textPaint.color = Color.BLACK
        textPaint.bgColor = Color.WHITE

        return dialogBox
    }

    private fun createBoxLayout(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        val text = dialog.getTextBy(language)

        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, dialog.width.toInt()).apply {
            setAlignment(Layout.Alignment.ALIGN_CENTER)
            setIncludePad(false)
            setLineSpacing(0f, 0.9f)
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
    }
}
