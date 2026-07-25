package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MGKomik : Madara() {
    override val dateFormat = SimpleDateFormat("dd MMM yy", Locale.US)

    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val mangaSubString = "komik"
    override val chapterUrlSuffix = ""

    override fun headersBuilder() = super.headersBuilder().apply {
        val ua = get("User-Agent").orEmpty()
        val chromeVersion = Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1)

        if (chromeVersion != null) {
            set(
                "Sec-CH-UA",
                "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$chromeVersion\", " +
                    "\"Chromium\";v=\"$chromeVersion\"",
            )
            set(
                "Sec-CH-UA-Full-Version-List",
                "\"Not(A:Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"$chromeVersion.0.0.0\", " +
                    "\"Chromium\";v=\"$chromeVersion.0.0.0\"",
            )
            set("Sec-CH-UA-Full-Version", "\"$chromeVersion.0.0.0\"")
        }

        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-CH-UA-Platform-Version", "\"14.0.0\"")
        set("Sec-CH-UA-Model", "\"\"")
        set("Sec-CH-UA-Arch", "\"arm\"")
        set("Sec-CH-UA-Bitness", "\"64\"")
        set("Upgrade-Insecure-Requests", "1")
        set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7",
        )
        set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}?m_orderby=trending"
        return GET(url, headers)
    }

    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content p"

    override fun parseGenres(document: Document): List<Genre> = document.select("div.checkbox-group div.checkbox")
        .mapNotNull { cb ->
            val label = cb.selectFirst("label")?.text() ?: return@mapNotNull null
            val value = cb.selectFirst("input[type=checkbox]")?.`val`() ?: return@mapNotNull null
            if (value.matches(Regex("""^\d+[kKmM]?$"""))) return@mapNotNull null
            Genre(label, value)
        }

    // PROJECT FILTER
    class ProjectFilter : Filter.CheckBox(" Project Only", false)

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        val base = super.getFilterList().list.toMutableList()
        base.add(0, ProjectFilter())
        base.add(1, Filter.Separator())
        return FilterList(base)
    }

    override fun searchLoadMoreRequest(page: Int, query: String, filters: FilterList): Request {
        val projectChecked = filters.filterIsInstance<ProjectFilter>().firstOrNull()?.state == true
        if (!projectChecked) return super.searchLoadMoreRequest(page, query, filters)

        val taxQueryIdx = filters.count { filter ->
            when (filter) {
                is AuthorFilter -> filter.state.isNotBlank()
                is ArtistFilter -> filter.state.isNotBlank()
                is YearFilter -> filter.state.isNotBlank()
                is GenreList -> filter.state.any { it.state }
                else -> false
            }
        }

        val superRequest = super.searchLoadMoreRequest(page, query, filters)
        val oldBody = superRequest.body as FormBody

        val newBody = FormBody.Builder().apply {
            for (i in 0 until oldBody.size) add(oldBody.name(i), oldBody.value(i))
            add("vars[tax_query][$taxQueryIdx][taxonomy]", "wp-manga-tag")
            add("vars[tax_query][$taxQueryIdx][field]", "slug")
            add("vars[tax_query][$taxQueryIdx][terms][0]", "project")
        }.build()

        return superRequest.newBuilder().post(newBody).build()
    }
}
