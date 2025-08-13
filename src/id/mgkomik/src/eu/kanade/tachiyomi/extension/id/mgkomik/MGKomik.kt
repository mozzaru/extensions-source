package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://id.mgkomik.cc",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override val id = 5845004992097969882

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random())) // added for webview, and removed in interceptor for normal use
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    // ================================== Popular ======================================

    // overriding to change title selector and manga url selector
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        // Try several selectors that match MGKomik HTML and similar Madara sites.
        // Don't use !! to avoid NullPointerException if structure changes.
        val link = element.selectFirst(
            "div.item-thumb a, div.popular-img a, a[href*='/$mangaSubString/']"
        )

        if (link != null) {
            // Prefer absolute href, but setUrlWithoutDomain expects a path without domain.
            val absHref = link.attr("abs:href").ifEmpty { link.attr("href") }
            val cleanedHref = if (absHref.startsWith(baseUrl)) absHref.removePrefix(baseUrl) else absHref
            manga.setUrlWithoutDomain(cleanedHref)

            // Prefer title attribute, fallback to link text
            val title = link.attr("title").ifEmpty { link.text().trim() }
            manga.title = if (title.isNotBlank()) title else "Unknown"
        } else {
            // Fallback safe defaults
            manga.setUrlWithoutDomain("/")
            manga.title = "Unknown"
        }

        // Thumbnail: pick nearest img inside the element (if any). Wrap in runCatching to avoid crashing on odd attributes.
        element.selectFirst("img")?.let { img ->
            manga.thumbnail_url = runCatching { imageFromElement(img) }.getOrNull()
        }

        return manga
    }

    // ================================ Chapters ================================

    override val chapterUrlSuffix = ""

    // ================================ Filters ================================

    override fun getFilterList(): FilterList {
        // Try to populate genres in background if empty (keep original behavior but guarded)
        try {
            if (genresList.isEmpty()) {
                launchIO { fetchGenres() }
            }
        } catch (_: Throwable) {
            // ignore - if launchIO is not available or fails, we still return base filters
        }

        val filters = super.getFilterList().list.toMutableList()

        filters += if (genresList.isNotEmpty()) {
            listOf(
                Filter.Separator(),
                GenreContentFilter(
                    title = intl["genre_filter_title"],
                    options = genresList.map { it.name to it.id },
                ),
            )
        } else {
            listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_missing_warning"]),
            )
        }

        return FilterList(filters)
    }

    private class GenreContentFilter(title: String, options: List<Pair<String, String>>) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    override fun genresRequest() = GET("$baseUrl/$mangaSubString", headers)

    override fun parseGenres(document: Document): List<Genre> {
        val genres = mutableListOf<Genre>()
        genres += Genre("All", "")

        // Try multiple selectors — sites can place genres in different widgets.
        val candidates = document.select(
            "a[href*='/genres/'], a[href*='/tags/'], .genres a, .tags a, .genre-list a, .tagscloud a, .genres_wrap a"
        )

        val seen = mutableSetOf<String>()
        candidates.forEach { a ->
            val href = a.absUrl("href").ifEmpty { a.attr("href") }.trim()
            if (href.isNotEmpty() && seen.add(href)) {
                val name = a.text().trim().ifEmpty { href.substringAfterLast("/").removeSuffix("/") }
                // Keep the id as the absolute or relative href (Madara base expects a string id)
                genres += Genre(name, href)
            }
        }

        return genres
    }

    // =============================== Utilities ==============================

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}
