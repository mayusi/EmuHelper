package io.github.mayusi.emuhelper.data.source

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for the pure (Android-free) quarantine destination-path rule: a flagged file at
 * <root>/<subfolder>/<filename> quarantines to <root>/Quarantine/<subfolder>/<filename>, mirroring
 * the existing per-console subfolder layout so quarantined files stay organised and easy to find.
 */
class QuarantinePathsTest {

    @Test
    fun destinationFor_mirrorsSubfolderUnderQuarantine() {
        val root = File("/roms")
        val dest = QuarantinePaths.destinationFor(root, "SNES", "Game (USA).zip")
        assertEquals(File("/roms/Quarantine/SNES/Game (USA).zip"), dest)
    }

    @Test
    fun destinationFor_blankSubfolder_stillNestsUnderQuarantine() {
        val root = File("/roms")
        val dest = QuarantinePaths.destinationFor(root, "", "loose-file.rar")
        assertEquals(File("/roms/Quarantine/loose-file.rar"), dest)
    }

    @Test
    fun destinationFor_neverEqualsSourcePath() {
        val root = File("/roms")
        val subfolder = "PSP"
        val filename = "Game.iso"
        val source = File(File(root, subfolder), filename)
        val dest = QuarantinePaths.destinationFor(root, subfolder, filename)
        assert(source != dest) { "quarantine destination must differ from the source path" }
    }
}
