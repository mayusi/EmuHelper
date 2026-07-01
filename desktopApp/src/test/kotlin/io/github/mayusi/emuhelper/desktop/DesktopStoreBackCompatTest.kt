package io.github.mayusi.emuhelper.desktop

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backward-compat regression tests for the JSON-backed desktop stores: verifies that an OLD-shape
 * `history.json` (written before `identifier`/`console`/`destFolder` existed on [DesktopHistoryStore
 * .DesktopHistoryEntry]) still deserializes cleanly, with the new fields defaulting to "". Uses real
 * temp files under the JVM temp dir (System.getProperty("java.io.tmpdir")) — NOT the repo — cleaned up
 * in a finally block per test, matching [DesktopArchiveTest]'s convention.
 */
class DesktopStoreBackCompatTest {

    @Test
    fun `old-shape history json without identifier, console, destFolder still loads`() {
        val file = File(System.getProperty("java.io.tmpdir"), "emuhelper_test_history_${UUID.randomUUID()}.json")
        try {
            // Shape written by a pre-parity build: no identifier/console/destFolder keys at all.
            file.writeText(
                """
                [
                  {
                    "filename": "game.zip",
                    "subfolder": "SNES",
                    "sizeBytes": 12345,
                    "status": "DONE",
                    "timestampMillis": 1700000000000,
                    "name": "Some Game"
                  }
                ]
                """.trimIndent()
            )

            val store = DesktopHistoryStore(file)

            assertFalse(store.decodeError.value, "old-shape JSON should not be flagged as a decode error")
            val entries = store.entries.value
            assertEquals(1, entries.size)
            val entry = entries.single()
            assertEquals("game.zip", entry.filename)
            assertEquals("Some Game", entry.name)
            assertEquals("", entry.identifier)
            assertEquals("", entry.console)
            assertEquals("", entry.destFolder)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `record helper populates new history fields and persists them`() {
        val file = File(System.getProperty("java.io.tmpdir"), "emuhelper_test_history_${UUID.randomUUID()}.json")
        try {
            val store = DesktopHistoryStore(file)
            store.record(
                filename = "game.zip",
                name = "Some Game",
                sizeBytes = 42L,
                status = "DONE",
                identifier = "some-ia-id",
                console = "snes",
                destFolder = "C:\\Roms\\SNES",
            )

            val reloaded = DesktopHistoryStore(file)
            val entry = reloaded.entries.value.single()
            assertEquals("some-ia-id", entry.identifier)
            assertEquals("snes", entry.console)
            assertEquals("C:\\Roms\\SNES", entry.destFolder)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `old-shape game_lists json without customFolderUri still loads and list mutations work`() {
        val file = File(System.getProperty("java.io.tmpdir"), "emuhelper_test_lists_${UUID.randomUUID()}.json")
        try {
            file.writeText(
                """
                [
                  {
                    "id": "list-1",
                    "name": "My List",
                    "createdAt": 1700000000000,
                    "games": []
                  }
                ]
                """.trimIndent()
            )

            val store = DesktopListStore(file)
            assertFalse(store.decodeError.value)
            assertEquals(1, store.lists.value.size)

            store.rename("list-1", "Renamed List")
            assertEquals("Renamed List", store.lists.value.single().name)

            store.setListFolder("list-1", "D:\\Games")
            assertEquals("D:\\Games", store.lists.value.single().customFolderUri)

            store.addItemToList(
                "list-1",
                DesktopListStore.DesktopCuratedGame(
                    name = "Cool Game",
                    filename = "cool.zip",
                    identifier = "cool-id",
                    console = "genesis",
                ),
            )
            assertEquals(1, store.lists.value.single().games.size)
            assertTrue(store.lists.value.single().games.any { it.identifier == "cool-id" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `favorite consoles toggle persists across store instances`() {
        val file = File(System.getProperty("java.io.tmpdir"), "emuhelper_test_settings_${UUID.randomUUID()}.properties")
        try {
            val store = DesktopSettingsStore(file)
            assertFalse(store.isFavorite("snes"))

            store.toggleFavorite("snes")
            assertTrue(store.isFavorite("snes"))
            assertEquals(setOf("snes"), store.favoriteConsoles.value)

            val reloaded = DesktopSettingsStore(file)
            assertTrue(reloaded.isFavorite("snes"))

            reloaded.toggleFavorite("snes")
            assertFalse(reloaded.isFavorite("snes"))
        } finally {
            file.delete()
        }
    }
}
