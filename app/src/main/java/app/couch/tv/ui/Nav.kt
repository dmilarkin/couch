package app.couch.tv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.couch.tv.data.Filter
import app.couch.tv.data.PlayRequest
import app.couch.tv.data.Row

enum class Tab(val label: String) {
    Home("Home"),
    Movies("Movies"),
    Series("Series"),
    Live("Live TV"),
    Search("Search"),
    Settings("Settings"),
}

sealed interface Screen {
    data object Main : Screen
    data class Movie(val id: String) : Screen
    data class Show(val id: String) : Screen
    data class Player(val req: PlayRequest) : Screen
    data object LibraryFilter : Screen
}

/** A tiny back stack plus the bits of UI state worth keeping when you come back to a screen. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Main)
    var tab by mutableStateOf(Tab.Home)

    val top: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun open(itemId: String) {
        when {
            itemId.startsWith("movie:") -> push(Screen.Movie(itemId))
            itemId.startsWith("series:") -> push(Screen.Show(itemId))
        }
    }

    fun play(req: PlayRequest) {
        if (top is Screen.Player) stack[stack.lastIndex] = Screen.Player(req) else push(Screen.Player(req))
    }

    // Remembered UI state
    var homeRows: List<Row>? = null
    val listStates = HashMap<String, LazyListState>()
    val gridStates = HashMap<String, LazyGridState>()
    val focusMemory = HashMap<String, String>()
    val filters = HashMap<String, Filter>()
    var liveCategory: String? = null
    var lastQuery: String = ""

    fun list(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }
    fun grid(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }
}
