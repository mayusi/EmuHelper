package io.github.mayusi.emuhelper.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop (Windows/JVM) saved-game-list persistence — the desktop analogue of the Android
 * [io.github.mayusi.emuhelper.data.storage.GameListStore] (DataStore + Context bound, so :app-only).
 *
 * The Android [io.github.mayusi.emuhelper.data.model.GameList] / `CuratedGame` models live in :app
 * (they mix in `java.io.Serializable` and a SAF `customFolderUri`), so this store defines desktop-local
 * @Serializable MIRRORS with the SAME field names and JSON shape. That means a `.json` list exported
 * from the Android app (or hand-authored) decodes here unchanged — `customFolderUri` (a SAF tree URI)
 * is accepted-and-ignored on desktop (folders are chosen with the native picker instead).
 *
 * Contract mirrors the desktop history store: one JSON blob under `~/.emuhelper/game_lists.json`,
 * newest-first, decode-error tolerant (a corrupt file yields an empty list + [decodeError] = true),
 * best-effort writes (a failure keeps the in-memory list for the session).
 *
 * SCOPE for this batch: view saved lists + load one into the download flow, plus delete (cheap, safe)
 * and import-from-file (parse a `.json` list picked with the native chooser). Create / rename / export
 * / per-list-folder-override are DEFERRED to a later batch (they depend on the picker/build flow that
 * isn't ported to desktop yet), matching the "at minimum: view + download-from" requirement.
 */
class DesktopListStore(private val file: File) {

    /** Desktop-local mirror of the Android `CuratedGame` (same field names / JSON shape). */
    @Serializable
    data class DesktopCuratedGame(
        val name: String,
        val filename: String = "",
        val size: Long = 0,
        val identifier: String = "",
        val source: String = "built_in",
        val console: String = "",
        val md5: String = "",
    ) {
        val key: String get() = "$identifier/$filename"
    }

    /** Desktop-local mirror of the Android `GameList` (same field names / JSON shape). */
    @Serializable
    data class DesktopGameList(
        val id: String,
        val name: String,
        val createdAt: Long,
        val games: List<DesktopCuratedGame>,
        /** SAF tree URI on Android; accepted-and-ignored on desktop (native folder picker instead). */
        val customFolderUri: String? = null,
    ) {
        val totalSize: Long get() = games.sumOf { it.size }
        val count: Int get() = games.size
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _decodeError = MutableStateFlow(false)
    /** True when the persisted game-list JSON was corrupt and could not be decoded. */
    val decodeError: StateFlow<Boolean> = _decodeError.asStateFlow()

    private val _lists = MutableStateFlow(load())
    /** All saved lists, newest first. */
    val lists: StateFlow<List<DesktopGameList>> = _lists.asStateFlow()

    /** Save (or replace by id) a list, then re-sort newest-first and persist. */
    fun save(list: DesktopGameList) {
        val merged = (_lists.value.filterNot { it.id == list.id } + list)
            .sortedByDescending { it.createdAt }
        _lists.value = merged
        persist(merged)
    }

    /** Remove a list by id and persist. */
    fun delete(id: String) {
        val remaining = _lists.value.filterNot { it.id == id }
        _lists.value = remaining
        persist(remaining)
    }

    /** Rename a list by id (no-op if the id isn't found), then persist. */
    fun rename(listId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val updated = _lists.value.map { if (it.id == listId) it.copy(name = trimmed) else it }
        _lists.value = updated
        persist(updated)
    }

    /**
     * Set (or clear, with null/blank) the per-list custom destination folder — the desktop analogue of
     * Android's per-list SAF `customFolderUri`, but an absolute filesystem path since desktop has no SAF.
     */
    fun setListFolder(listId: String, folderPath: String?) {
        val normalized = folderPath?.trim()?.takeIf { it.isNotEmpty() }
        val updated = _lists.value.map { if (it.id == listId) it.copy(customFolderUri = normalized) else it }
        _lists.value = updated
        persist(updated)
    }

    /**
     * Append a game/item to an existing list (e.g. from a search result), de-duping by [DesktopCuratedGame.key].
     * No-op if the list id isn't found.
     */
    fun addItemToList(listId: String, item: DesktopCuratedGame) {
        val updated = _lists.value.map { list ->
            if (list.id != listId) return@map list
            if (list.games.any { it.key == item.key }) return@map list
            list.copy(games = list.games + item)
        }
        _lists.value = updated
        persist(updated)
    }

    /**
     * Parse a single exported list from JSON text (the same one-object shape the Android store's
     * `encodeOne` / `decodeOne` uses). Returns null when the text isn't a valid list.
     */
    fun decodeOne(text: String): DesktopGameList? = try {
        json.decodeFromString(DesktopGameList.serializer(), text)
    } catch (_: Exception) { null }

    // ---- JSON I/O ---------------------------------------------------------------------------------

    private fun load(): List<DesktopGameList> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(DesktopGameList.serializer()), raw)
                .sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            _decodeError.value = true
            emptyList()
        }
    }

    private fun persist(list: List<DesktopGameList>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(DesktopGameList.serializer()), list))
        } catch (_: Exception) { /* best-effort: in-memory only for this session */ }
    }
}
