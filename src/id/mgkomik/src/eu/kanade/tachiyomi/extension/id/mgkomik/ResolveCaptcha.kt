package eu.kanade.tachiyomi.extension.id.mgkomik

// Tidak perlu implementasi jika tidak ada CAPTCHA
object ResolveCaptcha {
    fun solve(url: String): Boolean {
        return true // Dummy
    }
}