package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * OkHttp Interceptor that adds Client Hints headers based on User-Agent
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
                secCHHeaders.secCHUAArchitecture?.let {
                    header("Sec-CH-UA-Arch", "\"$it\"")
                }
                secCHHeaders.secCHUABitness?.let {
                    header("Sec-CH-UA-Bitness", "\"$it\"")
                }
                secCHHeaders.secCHUAModel?.let {
                    header("Sec-CH-UA-Model", "\"$it\"")
                }
                secCHHeaders.secCHUAPlatformVersion?.let {
                    header("Sec-CH-UA-Platform-Version", "\"$it\"")
                }
                secCHHeaders.secCHUAFullVersionList?.let {
                    header("Sec-CH-UA-Full-Version-List", it)
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
    val secCHUAArchitecture: String? = null,
    val secCHUABitness: String? = null,
    val secCHUAModel: String? = null,
    val secCHUAPlatformVersion: String? = null,
    val secCHUAFullVersionList: String? = null,
)

/**
 * User-Agent parser
 * Parses User-Agent string into corresponding Client Hints headers
 *
 * Uses precompiled regular expressions for performance optimization
 */
internal class UAParser {

    companion object {
        private const val UNKNOWN_VERSION = "147"
        private const val NOT_A_BRAND_VERSION = "8"

        // Precompiled regular expressions
        private val MAC_OS_VERSION_PATTERN = Pattern.compile("Mac OS X (\\d+[._]\\d+)")
        private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
        private val IOS_VERSION_PATTERN = Pattern.compile("OS (\\d+[._]\\d+)")
        private val EDGE_VERSION_PATTERN = Pattern.compile("Edg/(\\d+)")
        private val OPERA_VERSION_PATTERN = Pattern.compile("OPR/(\\d+)")
        private val CHROME_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)")
        private val FIREFOX_VERSION_PATTERN = Pattern.compile("Firefox/(\\d+)")
        private val SAFARI_VERSION_PATTERN = Pattern.compile("Version/(\\d+)")
    }

    fun parseUAtoSecCH(ua: String): SecCHHeaders {
        val brands = mutableListOf<String>()

        // Detect platform and mobile device
        val (platform, isMobile, platformVersion) = detectPlatform(ua)

        // Detect browser brands and versions
        detectBrowserBrands(ua, brands)

        // Add obfuscation brand (prevent browser fingerprinting)
        brands.add("\"Not.A/Brand\";v=\"$NOT_A_BRAND_VERSION\"")

        return SecCHHeaders(
            secCHUA = brands.joinToString(", "),
            secCHUAMobile = if (isMobile) "?1" else "?0",
            secCHUAPlatform = platform,
            secCHUAArchitecture = detectArchitecture(ua),
            secCHUABitness = detectBitness(ua),
            secCHUAModel = detectModel(ua),
            secCHUAPlatformVersion = platformVersion,
            secCHUAFullVersionList = brands.joinToString(", "),
        )
    }

    private fun detectPlatform(ua: String): Triple<String, Boolean, String?> = when {
        ua.contains("Windows NT 10.0") ->
            Triple("\"Windows\"", false, "10.0")

        ua.contains("Windows NT 6.3") ->
            Triple("\"Windows\"", false, "8.1")

        ua.contains("Windows NT 6.2") ->
            Triple("\"Windows\"", false, "8")

        ua.contains("Windows NT 6.1") ->
            Triple("\"Windows\"", false, "7")

        ua.contains("Macintosh") || ua.contains("Mac OS X") -> {
            val version = extractVersion(ua, MAC_OS_VERSION_PATTERN)?.replace("_", ".")
            Triple("\"macOS\"", false, version)
        }

        ua.contains("Android") -> {
            val version = extractVersion(ua, ANDROID_VERSION_PATTERN)
            Triple("\"Android\"", true, version)
        }

        ua.contains("iPhone") || ua.contains("iPad") -> {
            val version = extractVersion(ua, IOS_VERSION_PATTERN)?.replace("_", ".")
            val isMobile = ua.contains("iPhone") || ua.contains("Mobile")
            Triple("\"iOS\"", isMobile, version)
        }

        ua.contains("Linux") ->
            Triple("\"Linux\"", ua.contains("Mobile"), null)

        else ->
            Triple("\"Windows\"", ua.contains("Mobile"), null)
    }

    private fun detectBrowserBrands(ua: String, brands: MutableList<String>) {
        when {
            ua.contains("Edg/") -> {
                // Microsoft Edge (Chromium-based)
                val version = extractVersion(ua, EDGE_VERSION_PATTERN) ?: UNKNOWN_VERSION
                val chromeVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$chromeVersion\"")
                brands.add("\"Microsoft Edge\";v=\"$version\"")
            }

            ua.contains("OPR/") -> {
                // Opera (Chromium-based)
                val version = extractVersion(ua, OPERA_VERSION_PATTERN) ?: UNKNOWN_VERSION
                val chromeVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$chromeVersion\"")
                brands.add("\"Opera\";v=\"$version\"")
            }

            ua.contains("Chrome") -> {
                // Chrome browser (must be detected after Edge and Opera)
                val version = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$version\"")
                brands.add("\"Google Chrome\";v=\"$version\"")
            }

            ua.contains("Firefox") -> {
                // Firefox
                val version = extractVersion(ua, FIREFOX_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Firefox\";v=\"$version\"")
            }

            ua.contains("Safari") -> {
                // Safari (must be detected after Chrome)
                val version = extractVersion(ua, SAFARI_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Safari\";v=\"$version\"")
            }

            else -> {
                // Unknown browser, default to Chromium
                brands.add("\"Chromium\";v=\"$UNKNOWN_VERSION\"")
                brands.add("\"Not.A/Brand\";v=\"$UNKNOWN_VERSION\"")
            }
        }
    }

    private fun detectArchitecture(ua: String): String? = when {
        ua.contains("x86_64") || ua.contains("Win64") || ua.contains("x64") -> "x86"
        ua.contains("arm64") || ua.contains("aarch64") -> "arm"
        else -> null
    }

    private fun detectBitness(ua: String): String? = when {
        ua.contains("x86_64") || ua.contains("Win64") || ua.contains("x64") || ua.contains("arm64") || ua.contains("aarch64") -> "64"
        else -> null
    }

    private fun detectModel(ua: String): String? {
        if (!ua.contains("Android")) return null
        // Force Redmi Note 9 as requested by user
        return "Redmi Note 9"
    }

    private fun extractVersion(ua: String, pattern: Pattern): String? = pattern.matcher(ua)
        .takeIf { it.find() }
        ?.group(1)
}
