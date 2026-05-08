package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * OkHttp Interceptor that adds Client Hints headers based on User-Agent
 * Specialized for MG Komik to include Device Model.
 */
class UserAgentClientHintsInterceptor : Interceptor {

    private val parser = UAParser()

    // Thread-safe UA parsing result cache (max 50 UAs)
    private val cache = ConcurrentHashMap<String, SecCHHeaders>(16, 0.75f)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val userAgent = originalRequest.header("User-Agent")

        // Skip if no User-Agent header
        if (userAgent.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        // Get from cache or parse UA and generate Client Hints headers
        val secCHHeaders = cache.getOrPut(userAgent) {
            parser.parseUAtoSecCH(userAgent).also {
                // Simple LRU: if cache exceeds limit, clear oldest entries
                if (cache.size > 50) {
                    cache.keys.take(cache.size - 40).forEach { key -> cache.remove(key) }
                }
            }
        }

        // Build new request with Sec-CH-UA related headers
        val newRequest = originalRequest.newBuilder()
            .header("Sec-CH-UA", secCHHeaders.secCHUA)
            .header("Sec-CH-UA-Mobile", secCHHeaders.secCHUAMobile)
            .header("Sec-CH-UA-Platform", secCHHeaders.secCHUAPlatform)
            .apply {
                secCHHeaders.secCHUAPlatformVersion?.let {
                    header("Sec-CH-UA-Platform-Version", "\"$it\"")
                }
                secCHHeaders.secCHUAArch?.let {
                    header("Sec-CH-UA-Arch", "\"$it\"")
                }
                secCHHeaders.secCHUABitness?.let {
                    header("Sec-CH-UA-Bitness", "\"$it\"")
                }
                secCHHeaders.secCHUAFullVersion?.let {
                    header("Sec-CH-UA-Full-Version", "\"$it\"")
                }
                secCHHeaders.secCHUAFullVersionList?.let {
                    header("Sec-CH-UA-Full-Version-List", it)
                }
                secCHHeaders.secCHUAModel?.let {
                    header("Sec-CH-UA-Model", "\"$it\"")
                }
            }
            .build()

        return chain.proceed(newRequest)
    }
}

/**
 * Data class for Sec-CH-UA Client Hints headers
 */
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

/**
 * User-Agent parser
 * Parses User-Agent string into corresponding Client Hints headers
 */
internal class UAParser {

    companion object {
        private const val UNKNOWN_CHROME_VERSION = "147"
        private const val NOT_A_BRAND_VERSION = "8"

        // Precompiled regular expressions
        private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
        private val CHROME_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)(\\.\\d+){3}")
        private val CHROME_MAJOR_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)")
        private val MODEL_PATTERN = Pattern.compile("Android \\d+; ([^;)]+)")
    }

    fun parseUAtoSecCH(ua: String): SecCHHeaders {
        val brands = mutableListOf<String>()

        // Detect platform and mobile device
        val (platform, isMobile, platformVersion) = detectPlatform(ua)

        // Detect model
        val model = extractModel(ua)

        // Detect browser brands and versions
        val chromeFullVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: "$UNKNOWN_CHROME_VERSION.0.0.0"
        val chromeMajorVersion = extractVersion(ua, CHROME_MAJOR_VERSION_PATTERN) ?: UNKNOWN_CHROME_VERSION

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
            secCHUAArch = if (ua.contains("x86_64") || ua.contains("win64") || ua.contains("aarch64")) "x86" else "",
            secCHUABitness = if (ua.contains("x86_64") || ua.contains("win64") || ua.contains("aarch64")) "64" else "",
            secCHUAFullVersion = chromeFullVersion,
            secCHUAFullVersionList = brandsFull.joinToString(", "),
            secCHUAModel = model ?: "",
        )
    }

    private fun detectPlatform(ua: String): Triple<String, Boolean, String?> = when {
        ua.contains("Android") -> {
            val version = extractVersion(ua, ANDROID_VERSION_PATTERN)
            Triple("\"Android\"", true, version)
        }
        ua.contains("iPhone") || ua.contains("iPad") -> {
            Triple("\"iOS\"", true, null)
        }
        ua.contains("Windows") -> {
            Triple("\"Windows\"", false, null)
        }
        else -> Triple("\"Android\"", true, null)
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
