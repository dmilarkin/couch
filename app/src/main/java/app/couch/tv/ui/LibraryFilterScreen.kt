package app.couch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.FilterOptions
import kotlinx.coroutines.launch

/**
 * Choose what's in your library: switch off whole languages (say, Greek) or individual
 * provider categories. Hidden things vanish from home, browse, search and live TV.
 */
@Composable
fun LibraryFilterScreen(app: CouchApp, nav: Nav) {
    var opts by remember { mutableStateOf<FilterOptions?>(null) }
    var tab by remember { mutableStateOf("lang") }
    val cfg = app.prefs.config.value
    var hiddenLangs by remember { mutableStateOf(cfg.hiddenLangs) }
    var hiddenCats by remember { mutableStateOf(cfg.hiddenCats) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstTab = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        opts = app.repo.filterOptions()
        firstTab.tryFocus(120)
    }

    fun saveAndClose() {
        if (saving) return
        if (!dirty) {
            // Nothing changed here; don't overwrite changes made from the phone meanwhile.
            nav.pop()
            return
        }
        saving = true
        scope.launch {
            app.repo.setFilter(hiddenLangs, hiddenCats)
            nav.homeRows = null
            nav.filters.clear()
            nav.pop()
        }
    }
    androidx.activity.compose.BackHandler { saveAndClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = ScreenPad, vertical = 28.dp)) {
        Text("Library filter", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Switch off languages or categories you never watch. Changes apply when you leave this screen. " +
                "It's quicker from your phone, which has checkboxes and a search box.",
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp).width(760.dp),
        )
        Row(Modifier.padding(top = 18.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Languages", Modifier.focusRequester(firstTab), small = true, selected = tab == "lang") { tab = "lang" }
            ActionButton("Movie categories", small = true, selected = tab == "movie") { tab = "movie" }
            ActionButton("Series categories", small = true, selected = tab == "series") { tab = "series" }
            ActionButton("Live categories", small = true, selected = tab == "live") { tab = "live" }
            Spacer(Modifier.width(24.dp))
            ActionButton(if (saving) "Saving…" else "Done", small = true, primary = true) { saveAndClose() }
        }
        val o = opts
        if (o == null) {
            Centered { Spinner() }
        } else if (tab == "lang") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                ActionButton("Show all", small = true) {
                    hiddenLangs = emptySet()
                    dirty = true
                }
                ActionButton("Only ${cfg.prefLang}", small = true) {
                    hiddenLangs = o.langs.map { it.first }.filter { it != cfg.prefLang && it != "?" }.toSet()
                    dirty = true
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(o.langs, key = { it.first }) { (lang, n) ->
                    val on = !hiddenLangs.contains(lang)
                    ToggleRow(
                        label = if (lang == "?") "No language tag" else lang,
                        detail = "%,d titles and channels".format(n),
                        on = on,
                    ) {
                        hiddenLangs = if (on) hiddenLangs + lang else hiddenLangs - lang
                        dirty = true
                    }
                }
            }
        } else {
            val cats = o.categories[tab].orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                ActionButton("Show all", small = true) {
                    hiddenCats = hiddenCats.filterNot { it.startsWith("$tab:") }.toSet()
                    dirty = true
                }
                ActionButton("Hide all", small = true) {
                    hiddenCats = hiddenCats + cats.map { "$tab:${it.first.id}" }
                    dirty = true
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                items(cats, key = { it.first.id }) { (cat, n) ->
                    val key = "$tab:${cat.id}"
                    val on = !hiddenCats.contains(key)
                    val langHidden = cat.lang != null && hiddenLangs.contains(cat.lang)
                    ToggleRow(
                        label = cat.name,
                        detail = listOfNotNull("%,d".format(n), cat.lang, if (langHidden) "language hidden" else null).joinToString(" · "),
                        on = on,
                    ) {
                        hiddenCats = if (on) hiddenCats + key else hiddenCats - key
                        dirty = true
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
