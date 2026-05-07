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
 */
internal class UAParser {

    companion object {
        private val ANDROID_VERSION_PATTERN = Pattern.compile("Android (\\d+)")
        private val CHROME_VERSION_PATTERN = Pattern.compile("Chrome/(\\d+)")
    }

    fun parseUAtoSecCH(ua: String): SecCHHeaders {
        val brands = mutableListOf<String>()

        val isMobile = ua.contains("Mobile")
        val platform = when {
            ua.contains("Android") -> "\"Android\""
            ua.contains("Windows") -> "\"Windows\""
            ua.contains("Mac OS X") -> "\"macOS\""
            ua.contains("Linux") -> "\"Linux\""
            else -> "\"Android\""
        }

        val chromeVersion = extractVersion(ua, CHROME_VERSION_PATTERN) ?: "147"
        brands.add("\"Chromium\";v=\"$chromeVersion\"")
        brands.add("\"Not.A/Brand\";v=\"8\"")

        val model = if (ua.contains("Android")) {
            ua.substringAfter("(").substringBefore(")").split(";").lastOrNull()?.trim()?.takeIf { it != "Build" && it != "K" }
        } else {
            null
        }

        val platformVersion = when {
            ua.contains("Android") -> extractVersion(ua, ANDROID_VERSION_PATTERN)
            ua.contains("Windows NT 10.0") -> "10.0.0"
            else -> null
        }

        return SecCHHeaders(
            secCHUA = brands.joinToString(", "),
            secCHUAMobile = if (isMobile) "?1" else "?0",
            secCHUAPlatform = platform,
            secCHUAModel = model,
            secCHUAPlatformVersion = platformVersion,
            secCHUAFullVersionList = brands.joinToString(", "),
        )
    }

    private fun extractVersion(ua: String, pattern: Pattern): String? = pattern.matcher(ua)
        .takeIf { it.find() }
        ?.group(1)
}
