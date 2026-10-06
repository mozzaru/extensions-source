package eu.kanade.tachiyomi.extension.id.ikiru

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SortFilter(selection: Int = 0) : Filter.Sort(
    name = "Sort",
    values = sortBy.map { it.first }.toTypedArray(),
    state = Selection(selection, false),
) {
    val sort get() = sortBy[state?.index ?: 0].second
    val isAscending get() = state?.ascending ?: false

    companion object {
        private val sortBy = listOf(
            "Popular" to "popular",
            "Rating" to "rating",
            "Updated" to "updated",
            "Title" to "title",
            "Newest" to "created",
        )

        val popular = FilterList(SortFilter(0))
        val latest = FilterList(SortFilter(2))
    }
}

class TypeFilter : Filter.Group<CheckBoxFilter>(
    "Type",
    listOf(
        CheckBoxFilter("Manga", "MANGA"),
        CheckBoxFilter("Manhwa", "MANHWA"),
        CheckBoxFilter("Manhua", "MANHUA"),
    ),
) {
    val checked get() = state.filter { it.state }.map { it.value }
}

class StatusFilter : Filter.Group<CheckBoxFilter>(
    "Status",
    listOf(
        CheckBoxFilter("Ongoing", "ONGOING"),
        CheckBoxFilter("Completed", "COMPLETED"),
        CheckBoxFilter("Hiatus", "HIATUS"),
        CheckBoxFilter("Cancelled", "CANCELLED"),
    ),
) {
    val checked get() = state.filter { it.state }.map { it.value }
}

class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)

class ProjectFilter : Filter.CheckBox("Project only")

/**
 * The API matches author and artist names exactly and case-sensitively, so these are typed out
 * instead of picked from a list.
 */
class AuthorFilter : Filter.Text("Author")

class ArtistFilter : Filter.Text("Artist")

class GenreFilter(genres: List<Pair<String, String>>) : Filter.Group<CheckBoxFilter>(
    "Genre",
    genres.map { CheckBoxFilter(it.first, it.second) },
) {
    val included get() = state.filter { it.state }.map { it.value }
}
