package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl

interface UrlFilter {
    fun addToUrl(url: HttpUrl.Builder)
}

object SirenKomikFilters {

    class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed")
    ), UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            val value = values[state]
            if (value != "All") url.addQueryParameter("status", value)
        }
    }

    class TypeFilter : Filter.Select<String>(
        "Tipe",
        arrayOf("All", "Manga", "Manhwa", "Manhua")
    ), UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            val value = values[state]
            if (value != "All") url.addQueryParameter("type", value)
        }
    }

    class SortFilter : Filter.Select<String>(
        "Urutkan",
        arrayOf("latest", "popular")
    ), UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            url.addQueryParameter("sort", values[state])
        }
    }

    // Adjust genres to match site categories if needed
    class GenreFilter : Filter.Group<Filter.CheckBox>(
        "Genre",
        listOf(
            Filter.CheckBox("Action", false),
            Filter.CheckBox("Adventure", false),
            Filter.CheckBox("Comedy", false),
            Filter.CheckBox("Drama", false),
            Filter.CheckBox("Fantasy", false),
            Filter.CheckBox("Romance", false),
            Filter.CheckBox("School Life", false),
            Filter.CheckBox("Shounen", false),
            Filter.CheckBox("Shoujo", false),
            Filter.CheckBox("Slice of Life", false),
            Filter.CheckBox("Supernatural", false),
            Filter.CheckBox("Tragedy", false)
        )
    ), UrlFilter {
        override fun addToUrl(url: HttpUrl.Builder) {
            val selected = state.filter { it.state }
            selected.forEach { url.addQueryParameter("genres", it.name) }
        }
    }

    fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter()
    )
}
