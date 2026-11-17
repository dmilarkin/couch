package app.couch.tv.data

/** Another copy of the same movie from the provider (4K, FHD, another language...). */
data class Source(val id: String, val label: String, val url: String)

/** Everything the player needs. Built by Repo so the TV and the phone remote share one code path. */
data class PlayRequest(
    val kind: String, // movie | episode | live
    val itemId: String, // movie:123 | series:45 | live:678
    val progressKey: String?, // mg:<group> | ep:<episodeId> | null for live
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val startMs: Long = 0L,
    val year: Int = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeId: String? = null,
    val tmdbId: String? = null,
    val sources: List<Source> = emptyList(),
    val sourceId: String? = null,
    val queue: List<PlayRequest> = emptyList(), // following episodes
    val channels: List<String> = emptyList(), // live zapping order
)

data class Card(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String?,
    val wide: Boolean = false,
    val progress: Float? = null,
    val badge: String? = null,
    val item: Item? = null,
    val resume: Progress? = null,
)

data class Row(val key: String, val title: String, val cards: List<Card>, val wide: Boolean = false)

enum class Sort(val label: String) {
    Recent("Recently added"),
    Rating("Top rated"),
    Newest("Newest releases"),
    Oldest("Oldest releases"),
    Az("A to Z"),
}

data class Filter(
    val kind: String,
    val categoryId: String? = null,
    val lang: String? = null,
    val decade: Int? = null,
    val genre: String? = null,
    val sort: Sort = Sort.Recent,
)

data class BrowseOptions(
    val categories: List<Category>,
    val langs: List<String>,
    val decades: List<Int>,
    val genres: List<String>,
)

data class MovieDetails(
    val item: Item,
    val versions: List<Item>,
    val info: VodInfo?,
    val meta: MetaInfo?,
    val progress: Progress?,
    val favorite: Boolean,
    val similar: List<Item>,
)

data class ResumeTarget(val episode: EpisodeInfo, val positionMs: Long, val label: String)

data class SeriesDetails(
    val item: Item,
    val info: SeriesInfo,
    val meta: MetaInfo?,
    val progress: Map<String, Progress>,
    val resume: ResumeTarget?,
    val favorite: Boolean,
)

data class SearchResult(val movies: List<Item>, val series: List<Item>, val live: List<Item>) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && live.isEmpty()
}

data class Peek(val backdrop: String?, val plot: String?, val genre: String?, val rating: Double, val runtimeMin: Int)

/** What the library filter screens show: every language and category with item counts. */
data class FilterOptions(
    val langs: List<Pair<String, Int>>, // "?" = untagged
    val categories: Map<String, List<Pair<Category, Int>>>, // kind -> categories
)
