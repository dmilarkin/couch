package app.couch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.BrowseOptions
import app.couch.tv.data.Filter
import app.couch.tv.data.Item
import app.couch.tv.data.Sort

private enum class Picker { Category, Language, Decade, Genre, Sort }

/** Movies or Series tab: a poster grid with filters for category, language, decade, genre and sort order. */
@Composable
fun BrowseScreen(app: CouchApp, nav: Nav, kind: String) {
    var filter by remember(kind) { mutableStateOf(nav.filters[kind] ?: Filter(kind)) }
    var options by remember(kind) { mutableStateOf<BrowseOptions?>(null) }
    val list = remember(kind) { mutableStateListOf<Item>() }
    var loading by remember(kind) { mutableStateOf(false) }
    var endReached by remember(kind) { mutableStateOf(false) }
    var picker by remember(kind) { mutableStateOf<Picker?>(null) }
    val gridState = nav.grid("grid-$kind")
    val sync by app.sync.state.collectAsState()

    LaunchedEffect(kind, sync.finishedAt) { options = app.repo.browseOptions(kind) }

    suspend fun loadMore() {
        if (loading || endReached) return
        loading = true
        val page = app.repo.browse(filter, list.size, 120)
        val seen = list.mapTo(HashSet()) { it.id }
        list.addAll(page.filter { seen.add(it.id) })
        if (page.size < 120) endReached = true
        loading = false
    }

    LaunchedEffect(filter, sync.finishedAt) {
        nav.filters[kind] = filter
        list.clear()
        endReached = false
        loading = false
        loadMore()
    }

    LaunchedEffect(gridState, filter) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last > list.size - 30) loadMore() }
    }

    val catName = options?.categories?.firstOrNull { it.id == filter.categoryId }?.name
    val firstFocus = remember { FocusRequester() }
    val memoryKey = "grid-$kind"
    val remembered = nav.focusMemory[memoryKey]
    LaunchedEffect(list.isNotEmpty()) { if (list.isNotEmpty() && remembered != null) firstFocus.tryFocus(100) }

    Column(Modifier.fillMaxSize().padding(top = TopBarHeight)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton("Category: ${catName ?: "All"}", small = true, selected = filter.categoryId != null) { picker = Picker.Category }
            ActionButton("Language: ${filter.lang ?: "All"}", small = true, selected = filter.lang != null) { picker = Picker.Language }
            ActionButton("Decade: ${filter.decade?.let { "${it}s" } ?: "All"}", small = true, selected = filter.decade != null) { picker = Picker.Decade }
            ActionButton("Genre: ${filter.genre ?: "All"}", small = true, selected = filter.genre != null) { picker = Picker.Genre }
            ActionButton(filter.sort.label, small = true) { picker = Picker.Sort }
            if (filter != Filter(kind)) {
                ActionButton("Clear", small = true) { filter = Filter(kind) }
            }
            Spacer(Modifier.weight(1f))
            Text(if (endReached) "${list.size} titles" else "", fontSize = 12.sp, color = Muted)
        }

        if (list.isEmpty() && !loading) {
            MessageBox("Nothing here", "Try another filter, or check the library filter in Settings.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenPad, end = ScreenPad, top = 8.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(list, key = { _, it -> it.id }) { _, item ->
                    Box(Modifier.width(118.dp)) {
                        PosterCard(
                            title = item.title,
                            image = item.poster,
                            modifier = if (item.id == remembered) Modifier.focusRequester(firstFocus) else Modifier,
                            width = 118.dp,
                            badge = if (item.quality == "4K") "4K" else null,
                            subcaption = app.repo.subtitleOf(item),
                            onFocus = { nav.focusMemory[memoryKey] = item.id },
                            onClick = { nav.open(item.id) },
                        )
                    }
                }
            }
        }
    }

    val o = options
    when (picker) {
        Picker.Category -> SidePanel(
            title = "Category",
            options = listOf(Opt<String?>(null, "All categories")) + (o?.categories ?: emptyList()).map { Opt<String?>(it.id, it.name, it.lang) },
            isSelected = { it == filter.categoryId },
            onSelect = {
                filter = filter.copy(categoryId = it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        Picker.Language -> SidePanel(
            title = "Language",
            options = listOf(Opt<String?>(null, "All languages")) + (o?.langs ?: emptyList()).map { Opt<String?>(it, it) },
            isSelected = { it == filter.lang },
            onSelect = {
                filter = filter.copy(lang = it)
                picker = null
            },
            onDismiss = { picker = null },
            message = "Languages come from the provider's tags like \"EN\" or \"FR\". Hide languages for good in Settings > Library filter.",
        )
        Picker.Decade -> SidePanel(
            title = "Decade",
            options = listOf(Opt<Int?>(null, "Any decade")) + (o?.decades ?: emptyList()).sortedDescending().map { Opt<Int?>(it, "${it}s") },
            isSelected = { it == filter.decade },
            onSelect = {
                filter = filter.copy(decade = it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        Picker.Genre -> SidePanel(
            title = "Genre",
            options = listOf(Opt<String?>(null, "All genres")) + (o?.genres ?: emptyList()).map { Opt<String?>(it, it) },
            isSelected = { it == filter.genre },
            onSelect = {
                filter = filter.copy(genre = it)
                picker = null
            },
            onDismiss = { picker = null },
            message = if (kind == "movie") "Movie genres are guessed from category names unless the provider lists them." else null,
        )
        Picker.Sort -> SidePanel(
            title = "Sort by",
            options = Sort.entries.map { Opt(it, it.label) },
            isSelected = { it == filter.sort },
            onSelect = {
                filter = filter.copy(sort = it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}
