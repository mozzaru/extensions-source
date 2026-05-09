package keiyoushi.lib.clienthints

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Advanced OkHttp Interceptor that adds Client Hints headers based on User-Agent.
 * Designed to mimic real browser fingerprints.
 */
class ClientHintsInterceptor : Interceptor {

    private val parser = UAParser()
    private val cache = ConcurrentHashMap<String, SecCHHeaders>(16, 0.75f)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val userAgent = originalRequest.header("User-Agent")

        if (userAgent.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val secCHHeaders = cache.getOrPut(userAgent) {
            parser.parseUAtoSecCH(userAgent).also {
                if (cache.size > 50) {
                    cache.keys.take(cache.size - 40).forEach { key -> cache.remove(key) }
                }
            }
        }

        val newRequest = originalRequest.newBuilder()
            .header("Sec-CH-UA", secCHHeaders.secCHUA)
            .header("Sec-CH-UA-Mobile", secCHHeaders.secCHUAMobile)
            .header("Sec-CH-UA-Platform", secCHHeaders.secCHUAPlatform)
            .apply {
                secCHHeaders.secCHUAPlatformVersion?.let { header("Sec-CH-UA-Platform-Version", "\"$it\"") }
                secCHHeaders.secCHUAArch?.let { header("Sec-CH-UA-Arch", "\"$it\"") }
                secCHHeaders.secCHUABitness?.let { header("Sec-CH-UA-Bitness", "\"$it\"") }
                secCHHeaders.secCHUAFullVersion?.let { header("Sec-CH-UA-Full-Version", "\"$it\"") }
                secCHHeaders.secCHUAFullVersionList?.let { header("Sec-CH-UA-Full-Version-List", it) }
                secCHHeaders.secCHUAModel?.let { header("Sec-CH-UA-Model", "\"$it\"") }

                // Common browser headers to improve fingerprint
                if (originalRequest.header("Accept-Language") == null) {
                    header("Accept-Language", "en-US,en;q=0.9")
                }
                if (originalRequest.header("Upgrade-Insecure-Requests") == null) {
                    header("Upgrade-Insecure-Requests", "1")
                }
            }
            .build()

        return chain.proceed(newRequest)
    }
}

internal data class SecCHHeaders(
    val secCHUA: String,
    val secCHUAMobile: String,
    val secCHUAPlatform: String,
    val secCHUAPlatformVersion: String? = null,
    val secCHUAArch: String? = null,
    val secCHUABitness: String? = null,
    val secCHUAFullVersion: String? = null,
    val secCHUAFullVersionList: String? = null,
    val secCHUAModel: String? = null,
)

internal class UAParser {
    companion object {
        private const val DEFAULT_CHROME_VERSION = "124"
        private const val NOT_A_BRAND_VERSION = "8"

        private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
        private val CHROME_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)(\\.\\d+){3}")
        private val CHROME_MAJOR_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)")
        private val MODEL_PATTERN = Pattern.compile("Android \\d+; ([^;)]+)")
        private val WINDOWS_NT_PATTERN = Pattern.compile("Windows NT (\\d+\\.\\d+)")
    }

    fun parseUAtoSecCH(ua: String): SecCHHeaders {
        val brands = mutableListOf<String>()
        val (platform, isMobile, platformVersion) = detectPlatform(ua)
        val model = extractModel(ua)

        val chromeFullVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: "$DEFAULT_CHROME_VERSION.0.0.0"
        val chromeMajorVersion = extractVersion(ua, CHROME_MAJOR_VERSION_PATTERN) ?: DEFAULT_CHROME_VERSION

        brands.add("\"Chromium\";v=\"$chromeMajorVersion\"")
        brands.add("\"Not.A/Brand\";v=\"$NOT_A_BRAND_VERSION\"")

        val brandsFull = mutableListOf<String>()
        brandsFull.add("\"Chromium\";v=\"$chromeFullVersion\"")
        brandsFull.add("\"Not.A/Brand\";v=\"$NOT_A_BRAND_VERSION.0.0.0\"")

        return SecCHHeaders(
            secCHUA = brands.joinToString(", "),
            secCHUAMobile = if (isMobile) "?1" else "?0",
            secCHUAPlatform = platform,
            secCHUAPlatformVersion = platformVersion,
            secCHUAArch = detectArch(ua),
            secCHUABitness = detectBitness(ua),
            secCHUAFullVersion = chromeFullVersion,
            secCHUAFullVersionList = brandsFull.joinToString(", "),
            secCHUAModel = model ?: "",
        )
    }

    private fun detectPlatform(ua: String): Triple<String, Boolean, String?> = when {
        ua.contains("Android") -> Triple("\"Android\"", true, extractVersion(ua, ANDROID_VERSION_PATTERN))
        ua.contains("Windows") -> Triple("\"Windows\"", false, extractVersion(ua, WINDOWS_NT_PATTERN))
        ua.contains("Macintosh") || ua.contains("Mac OS X") -> Triple("\"macOS\"", false, null)
        ua.contains("iPhone") || ua.contains("iPad") -> Triple("\"iOS\"", true, null)
        ua.contains("Linux") -> Triple("\"Linux\"", ua.contains("Mobile"), null)
        else -> Triple("\"Unknown\"", ua.contains("Mobile"), null)
    }

    private fun detectArch(ua: String): String = when {
        ua.contains("x86_64") || ua.contains("win64") -> "x86"
        ua.contains("aarch64") || ua.contains("arm64") -> "arm"
        else -> ""
    }

    private fun detectBitness(ua: String): String = when {
        ua.contains("x86_64") || ua.contains("win64") || ua.contains("aarch64") || ua.contains("arm64") -> "64"
        else -> ""
    }

    private fun extractModel(ua: String): String? {
        val matcher = MODEL_PATTERN.matcher(ua)
        return if (matcher.find()) matcher.group(1).trim() else null
    }

    private fun extractVersion(ua: String, pattern: Pattern): String? {
        val matcher = pattern.matcher(ua)
        return if (matcher.find()) matcher.group(1) else null
    }
}
