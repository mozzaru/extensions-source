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

class RatingFilter : Filter.Select<String>("Rating", arrayOf("Semua", "9.0+", "8.0+", "7.0+", "6.0+")) {
    fun toUriPart(): String = when (state) {
        1 -> "9"
        2 -> "8"
        3 -> "7"
        4 -> "6"
        else -> ""
    }
}

fun getFilterListInternal() = FilterList(
    TypeFilter(),
    StatusFilter(),
    RatingFilter(), // Tambah rating filter
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
            Genre("slice-of-life", "Slice of Life"), // Tambah genre yang hilang
            Genre("psychological", "Psychological"),
            Genre("thriller", "Thriller"),
            Genre("mystery", "Mystery"),
            Genre("horror", "Horror"),
            Genre("shounen", "Shounen"),
            Genre("seinen", "Seinen"),
            Genre("josei", "Josei"),
            Genre("shoujo", "Shoujo"),
        ),
    ),
)
