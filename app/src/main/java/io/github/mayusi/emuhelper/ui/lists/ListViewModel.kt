package io.github.mayusi.emuhelper.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.GameList
import io.github.mayusi.emuhelper.data.storage.GameListStore
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(
    private val store: GameListStore,
    private val scanState: ScanStateHolder
) : ViewModel() {

    val lists: StateFlow<List<GameList>> =
        store.lists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Games staged by the picker (BUILD mode), shown on the Save-list screen. */
    val pendingGames: StateFlow<List<CuratedGame>> = scanState.pendingListGames

    /** One-shot UI message (import errors etc.). */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    fun consumeMessage() { _message.value = "" }

    /** Build a list object (not yet persisted). */
    fun buildList(name: String, games: List<CuratedGame>): GameList = GameList(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "Untitled list" },
        createdAt = System.currentTimeMillis(),
        games = games
    )

    /** Persist a list, clear the staging area, then run [onSaved]. */
    fun persist(list: GameList, onSaved: () -> Unit) {
        if (list.games.isEmpty()) return
        viewModelScope.launch {
            store.save(list)
            scanState.pendingListGames.value = emptyList()
            onSaved()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { store.rename(id, trimmed) }
    }

    /** Load a saved list into the download queue (all games), then the preview screen filters. */
    fun loadForDownload(list: GameList) {
        scanState.downloadQueue.value = list.games
    }

    /** Serialize a list for file export. */
    fun encodeForExport(list: GameList): String = store.encodeOne(list)

    /** Parse and add an imported list (fresh id). Returns success. */
    fun importFromText(text: String) {
        val parsed = store.decodeOne(text)
        if (parsed == null) {
            _message.value = "Couldn't read that list file."
            return
        }
        val copy = parsed.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        viewModelScope.launch {
            store.save(copy)
            _message.value = "Imported \"${copy.name}\" (${copy.count} games)."
        }
    }
}
