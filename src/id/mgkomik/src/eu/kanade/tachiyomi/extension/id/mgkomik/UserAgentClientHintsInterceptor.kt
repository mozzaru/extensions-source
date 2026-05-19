package eu.kanade.tachiyomi.extension.id.mgkomik

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class UserAgentClientHintsInterceptor : Interceptor {

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
            .header("Sec-CH-UA-Arch", "\"\"")
            .header("Sec-CH-UA-Bitness", "\"\"")
            .header("Sec-CH-UA-Model", "\"\"")
            .apply {
                secCHHeaders.secCHUAPlatformVersion?.let {
                    header("Sec-CH-UA-Platform-Version", "\"$it\"")
                }
                secCHHeaders.secCHUAFullVersion?.let {
                    header("Sec-CH-UA-Full-Version", "\"$it\"")
                }
                secCHHeaders.secCHUAFullVersionList?.let {
                    header("Sec-CH-UA-Full-Version-List", it)
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
    val secCHUAFullVersion: String? = null,
    val secCHUAFullVersionList: String? = null,
)

internal class UAParser {

    companion object {
        private const val UNKNOWN_VERSION = "119"
        private const val NOT_A_BRAND_VERSION = "24"

        private val MAC_OS_VERSION_PATTERN = Pattern.compile("Mac OS X (\\d+[._]\\d+)")
        private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
        private val IOS_VERSION_PATTERN = Pattern.compile("OS (\\d+[._]\\d+)")
        private val EDGE_VERSION_PATTERN = Pattern.compile("Edg/(\\d+)")
        private val OPERA_VERSION_PATTERN = Pattern.compile("OPR/(\\d+)")
        private val CHROME_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)")
        private val CHROME_FULL_VERSION_PATTERN = Pattern.compile("Chrome/([\\d.]+)")
        private val FIREFOX_VERSION_PATTERN = Pattern.compile("Firefox/(\\d+)")
        private val SAFARI_VERSION_PATTERN = Pattern.compile("Version/(\\d+)")
    }

    fun parseUAtoSecCH(ua: String): SecCHHeaders {
        val brands = mutableListOf<String>()

        val (platform, isMobile, platformVersion) = detectPlatform(ua)
        val fullVersion = extractVersion(ua, CHROME_FULL_VERSION_PATTERN)

        detectBrowserBrands(ua, brands)
        brands.add("\"Not?A_Brand\";v=\"$NOT_A_BRAND_VERSION\"")

        return SecCHHeaders(
            secCHUA = brands.joinToString(", "),
            secCHUAMobile = if (isMobile) "?1" else "?0",
            secCHUAPlatform = platform,
            secCHUAPlatformVersion = platformVersion,
            secCHUAFullVersion = fullVersion,
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
                val version = extractVersion(ua, EDGE_VERSION_PATTERN) ?: UNKNOWN_VERSION
                val chromeVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$chromeVersion\"")
                brands.add("\"Microsoft Edge\";v=\"$version\"")
            }

            ua.contains("OPR/") -> {
                val version = extractVersion(ua, OPERA_VERSION_PATTERN) ?: UNKNOWN_VERSION
                val chromeVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$chromeVersion\"")
                brands.add("\"Opera\";v=\"$version\"")
            }

            ua.contains("Chrome") -> {
                val version = extractVersion(ua, CHROME_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Chromium\";v=\"$version\"")
                brands.add("\"Google Chrome\";v=\"$version\"")
            }

            ua.contains("Firefox") -> {
                val version = extractVersion(ua, FIREFOX_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Firefox\";v=\"$version\"")
            }

            ua.contains("Safari") -> {
                val version = extractVersion(ua, SAFARI_VERSION_PATTERN) ?: UNKNOWN_VERSION
                brands.add("\"Safari\";v=\"$version\"")
            }

            else -> {
                brands.add("\"Chromium\";v=\"$UNKNOWN_VERSION\"")
                brands.add("\"Not_A Brand\";v=\"$UNKNOWN_VERSION\"")
            }
        }
    }

    private fun extractVersion(ua: String, pattern: Pattern): String? = pattern.matcher(ua)
        .takeIf { it.find() }
        ?.group(1)
}
