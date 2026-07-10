package eu.kanade.tachiyomi.extension.id.soulscans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getSoulScansFilterList(genres: List<Pair<String, String>>): FilterList {
    val filters = mutableListOf<Filter<*>>(
        ProjectFilter(),
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    if (genres.isNotEmpty()) {
        filters += listOf(
            Filter.Separator(),
            GenreFilter(genres.map { Genre(it.first, it.second) }),
        )
    } else {
        filters += listOf(
            Filter.Separator(),
            Filter.Header("Tekan 'Reset' untuk memuat daftar genre"),
        )
    }

    return FilterList(filters)
}

class ProjectFilter : Filter.CheckBox("Tampilkan project resmi saja")

class SortFilter :
    Filter.Select<String>(
        "Urutkan",
        arrayOf("Terbaru", "Terpopuler", "Judul (A-Z)", "Judul (Z-A)", "Rating"),
    ) {
    fun toQuery(): Pair<String, String> = when (state) {
        0 -> "latest" to "desc"
        1 -> "views" to "desc"
        2 -> "title" to "asc"
        3 -> "title" to "desc"
        4 -> "rating" to "desc"
        else -> "latest" to "desc"
    }
}

class TypeFilter :
    Filter.Select<String>(
        "Tipe",
        arrayOf(SHOW_ALL, "Manga", "Manhwa", "Manhua"),
    ) {
    fun selectedValue(): String? = values.takeIf { state != 0 }?.get(state)?.uppercase()
}

class StatusFilter :
    Filter.Select<String>(
        "Status",
        arrayOf(SHOW_ALL, "Ongoing", "Completed", "Hiatus"),
    ) {
    fun selectedValue(): String? = values.takeIf { state != 0 }?.get(state)?.uppercase()
}

class Genre(name: String, val slug: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

private const val SHOW_ALL = "Semua"
