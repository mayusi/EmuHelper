package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.data.safety.SafetyVerdict
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the PURE desktop-UI helpers in [DesktopUiHelpers]: the file filter/sort predicate,
 * the quarantine-path builder (collision handling), and the verdict→band colour mapping. No Compose,
 * no network, no real downloads; the one File-touching helper is driven with an injected `exists`.
 */
class DesktopUiHelpersTest {

    private data class Row(
        override val displayName: String,
        override val sizeBytes: Long,
        override val isSelected: Boolean,
    ) : FileRowLike

    private val rows = listOf(
        Row("Zelda.gba", 100, isSelected = true),
        Row("alpha.nes", 300, isSelected = false),
        Row("Metroid.sfc", 200, isSelected = true),
    )

    // ---- filterAndSortFiles: query --------------------------------------------------------------

    @Test
    fun `empty query keeps all rows`() {
        val out = filterAndSortFiles(rows, "", FileSort.NAME_ASC, SelectionFilter.ALL)
        assertEquals(3, out.size)
    }

    @Test
    fun `query is a case-insensitive substring match on the name`() {
        val out = filterAndSortFiles(rows, "ZEL", FileSort.NAME_ASC, SelectionFilter.ALL)
        assertEquals(listOf("Zelda.gba"), out.map { it.displayName })
    }

    @Test
    fun `query is trimmed before matching`() {
        val out = filterAndSortFiles(rows, "  metroid  ", FileSort.NAME_ASC, SelectionFilter.ALL)
        assertEquals(listOf("Metroid.sfc"), out.map { it.displayName })
    }

    // ---- filterAndSortFiles: selection filter ---------------------------------------------------

    @Test
    fun `SELECTED filter keeps only ticked rows`() {
        val out = filterAndSortFiles(rows, "", FileSort.NAME_ASC, SelectionFilter.SELECTED)
        assertEquals(setOf("Zelda.gba", "Metroid.sfc"), out.map { it.displayName }.toSet())
    }

    @Test
    fun `UNSELECTED filter hides ticked rows`() {
        val out = filterAndSortFiles(rows, "", FileSort.NAME_ASC, SelectionFilter.UNSELECTED)
        assertEquals(listOf("alpha.nes"), out.map { it.displayName })
    }

    // ---- filterAndSortFiles: sort ---------------------------------------------------------------

    @Test
    fun `NAME_ASC sorts case-insensitively`() {
        val out = filterAndSortFiles(rows, "", FileSort.NAME_ASC, SelectionFilter.ALL)
        assertEquals(listOf("alpha.nes", "Metroid.sfc", "Zelda.gba"), out.map { it.displayName })
    }

    @Test
    fun `NAME_DESC reverses the case-insensitive order`() {
        val out = filterAndSortFiles(rows, "", FileSort.NAME_DESC, SelectionFilter.ALL)
        assertEquals(listOf("Zelda.gba", "Metroid.sfc", "alpha.nes"), out.map { it.displayName })
    }

    @Test
    fun `SIZE_DESC sorts largest first`() {
        val out = filterAndSortFiles(rows, "", FileSort.SIZE_DESC, SelectionFilter.ALL)
        assertEquals(listOf(300L, 200L, 100L), out.map { it.sizeBytes })
    }

    @Test
    fun `SIZE_ASC sorts smallest first`() {
        val out = filterAndSortFiles(rows, "", FileSort.SIZE_ASC, SelectionFilter.ALL)
        assertEquals(listOf(100L, 200L, 300L), out.map { it.sizeBytes })
    }

    @Test
    fun `query and selection filter compose`() {
        val out = filterAndSortFiles(rows, "a", FileSort.NAME_ASC, SelectionFilter.SELECTED)
        // "a" matches Zelda + alpha + Metroid... case-insensitive: Zelda(a), alpha(a), Metroid(no a)
        // selected-only: Zelda (selected), alpha (not), Metroid (selected, but no 'a')
        assertEquals(listOf("Zelda.gba"), out.map { it.displayName })
    }

    // ---- quarantineTargetFor --------------------------------------------------------------------

    @Test
    fun `quarantine target lands in a Quarantine subdir of the file's parent`() {
        val dl = File(File("dl").absolutePath)
        val f = File(dl, "game.iso")
        val target = quarantineTargetFor(f, exists = { false })
        assertEquals("Quarantine", target.parentFile.name)
        assertEquals("game.iso", target.name)
    }

    @Test
    fun `quarantine target disambiguates a name clash before the extension`() {
        val dl = File(File("dl").absolutePath)
        val f = File(dl, "game.iso")
        val quarantineDir = File(dl, "Quarantine")
        val taken = setOf(File(quarantineDir, "game.iso").path)
        val target = quarantineTargetFor(f, exists = { it.path in taken })
        assertEquals("game (1).iso", target.name)
    }

    @Test
    fun `quarantine target handles an extensionless name`() {
        val dl = File(File("dl").absolutePath)
        val f = File(dl, "README")
        val quarantineDir = File(dl, "Quarantine")
        val taken = setOf(File(quarantineDir, "README").path)
        val target = quarantineTargetFor(f, exists = { it.path in taken })
        assertEquals("README (1)", target.name)
    }

    // ---- bandForVerdict / isQuarantineWorthy ----------------------------------------------------

    @Test
    fun `clean verdicts map to green and are not quarantine-worthy`() {
        assertEquals(SafetyBand.GREEN, bandForVerdict(SafetyVerdict.VERIFIED))
        assertEquals(SafetyBand.GREEN, bandForVerdict(SafetyVerdict.LOCAL_CLEAR))
        assertFalse(isQuarantineWorthy(SafetyVerdict.LOCAL_CLEAR))
    }

    @Test
    fun `review verdicts map to amber and are quarantine-worthy`() {
        assertEquals(SafetyBand.AMBER, bandForVerdict(SafetyVerdict.LIMITED))
        assertEquals(SafetyBand.AMBER, bandForVerdict(SafetyVerdict.UNKNOWN))
        assertEquals(SafetyBand.AMBER, bandForVerdict(SafetyVerdict.SUSPICIOUS))
        assertTrue(isQuarantineWorthy(SafetyVerdict.SUSPICIOUS))
    }

    @Test
    fun `risky maps to red and is quarantine-worthy`() {
        assertEquals(SafetyBand.RED, bandForVerdict(SafetyVerdict.RISKY))
        assertTrue(isQuarantineWorthy(SafetyVerdict.RISKY))
    }
}
