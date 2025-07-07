package eu.kanade.tachiyomi.extension.id.mangatale

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class TypeFilter : Filter.Select<String>("Tipe", arrayOf("Semua", "Manga", "Manhwa", "Manhua")) {
    fun toUriPart(): String = when (state) {
        1 -> "manga"
        2 -> "manhwa"
        3 -> "manhua"
        else -> ""
    }
}

class StatusFilter : Filter.Select<String>("Status", arrayOf("Semua", "Ongoing", "Completed")) {
    fun toUriPart(): String = when (state) {
        1 -> "ongoing"
        2 -> "completed"
        else -> ""
    }
}

class Genre(val id: String, name: String) : Filter.CheckBox(name, false) // Default false

class GenreFilterList(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

fun getFilterListInternal() = FilterList(
    TypeFilter(),
    StatusFilter(),
    GenreFilterList(
        listOf(
            Genre("action", "Action"),
            Genre("adventure", "Adventure"),
            Genre("comedy", "Comedy"),
            Genre("drama", "Drama"),
            Genre("fantasy", "Fantasy"),
            Genre("martial-arts", "Martial Arts"),
            Genre("romance", "Romance"),
            Genre("school-life", "School Life"),
            Genre("sci-fi", "Sci-Fi"),
            Genre("supernatural", "Supernatural"),
            Genre("isekai", "Isekai"),
            Genre("reincarnation", "Reincarnation"),
            Genre("historical", "Historical"),
            Genre("manhua", "Manhua"),
            Genre("manhwa", "Manhwa"),
            Genre("manga", "Manga"),
        ),
    ),
)
