package io.github.mayusi.emuhelper.data.source.root

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [RootPlacement] — the eligibility decision and command construction for the
 * optional accelerated file-placement path. Two invariants matter most and are both proven here:
 *   1. Eligibility is a STRICT SUBSET of what [PServerCommandGuard] would allow, so every command we
 *      ever build for an eligible destination is independently guard-ALLOWED (no wasted transacts,
 *      no surprise denials).
 *   2. Anything the guard would reject as a destination (space, metachar, traversal, wrong root) is
 *      classified NOT eligible -> caller falls back to the normal copy.
 */
class RootPlacementTest {

    // ---- eligibility ------------------------------------------------------------------------

    @Test fun `external files dir under storage emulated is eligible`() {
        val p = "/storage/emulated/0/Android/data/io.github.mayusi.emuhelper/files/Download/ROMs/SNES/game.sfc"
        assertEquals(p, RootPlacement.cleanEligiblePath(p))
    }

    @Test fun `sdcard path is eligible`() {
        assertNotNull(RootPlacement.cleanEligiblePath("/sdcard/roms/snes/game.sfc"))
    }

    @Test fun `path with a space is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/roms/Super Mario.sfc"))
    }

    @Test fun `relative path is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("sdcard/roms/game.sfc"))
    }

    @Test fun `traversal component is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/roms/../../system/x"))
    }

    @Test fun `dot component is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/roms/./game.sfc"))
    }

    @Test fun `metacharacter in path is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/roms/game\$x.sfc"))
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/roms/game;rm.sfc"))
    }

    @Test fun `path outside any write root is NOT eligible`() {
        assertNull(RootPlacement.cleanEligiblePath("/system/app/x"))
        assertNull(RootPlacement.cleanEligiblePath("/data/local/tmp/x"))
    }

    @Test fun `write-root prefix boundary is respected (sdcardX is not under sdcard)`() {
        assertNull(RootPlacement.cleanEligiblePath("/sdcardX/game.sfc"))
    }

    @Test fun `bare write root with no child is NOT eligible`() {
        // Must be strictly INSIDE the root, never the root itself.
        assertNull(RootPlacement.cleanEligiblePath("/sdcard/"))
    }

    @Test fun `eligibleDestPath delegates to cleanEligiblePath on the File absolute path`() {
        // NOTE: File.absolutePath is host-OS dependent (the JVM test host is not Android), so we
        // assert the delegation/consistency rather than a hard-coded unix path: whatever absolute
        // path the File reports, eligibleDestPath must return exactly what cleanEligiblePath would.
        val f = File("/storage/emulated/0/Android/data/io.github.mayusi.emuhelper/files/ROMs/PSP/game.iso")
        assertEquals(RootPlacement.cleanEligiblePath(f.absolutePath), RootPlacement.eligibleDestPath(f))
    }

    // ---- command construction ----------------------------------------------------------------

    // App-private write roots are supplied per-call from the runtime Context (carrying any
    // applicationId suffix), exactly as DownloadManager.tryFastPlacement does. Tests pass them
    // explicitly so the source cache path under /data/data/<pkg> is recognized as eligible.
    private val appRoots = listOf(
        "/data/data/io.github.mayusi.emuhelper/",
        "/data/user/0/io.github.mayusi.emuhelper/"
    )

    @Test fun `buildPlaceCommands produces mkdir cp chmod in order`() {
        val src = "/data/data/io.github.mayusi.emuhelper/cache/dl/game.sfc"
        val dst = "/sdcard/roms/snes/game.sfc"
        val cmds = RootPlacement.buildPlaceCommands(src, dst, appRoots)
        assertNotNull(cmds)
        assertEquals(3, cmds.size)
        assertEquals("mkdir -p /sdcard/roms/snes", cmds[0])
        assertEquals("cp /data/data/io.github.mayusi.emuhelper/cache/dl/game.sfc /sdcard/roms/snes/game.sfc", cmds[1])
        assertEquals("chmod 644 /sdcard/roms/snes/game.sfc", cmds[2])
    }

    @Test fun `buildPlaceCommands returns null for an ineligible destination`() {
        assertNull(RootPlacement.buildPlaceCommands("/sdcard/a/x", "/system/x"))
    }

    // ---- the DISK-EFFICIENT move variant -----------------------------------------------------

    @Test fun `buildMoveCommands produces mkdir mv chmod in order`() {
        val src = "/data/data/io.github.mayusi.emuhelper/cache/dl/game.pkg"
        val dst = "/sdcard/roms/ps4/game.pkg"
        val cmds = RootPlacement.buildMoveCommands(src, dst, appRoots)
        assertNotNull(cmds)
        assertEquals(3, cmds.size)
        assertEquals("mkdir -p /sdcard/roms/ps4", cmds[0])
        assertEquals("mv /data/data/io.github.mayusi.emuhelper/cache/dl/game.pkg /sdcard/roms/ps4/game.pkg", cmds[1])
        assertEquals("chmod 644 /sdcard/roms/ps4/game.pkg", cmds[2])
    }

    @Test fun `every command built by buildMoveCommands is guard-allowed`() {
        // The load-bearing invariant for the move fast-path: every mv command we build for an
        // eligible (under-a-write-root) src+dst is independently ALLOWED by the guard — including the
        // guard's stricter mv SOURCE constraint (the source must itself be under a write root, which
        // eligibility already enforces). Register the runtime package exactly as DownloadManager does.
        PServerCommandGuard.registerOwnPackage("io.github.mayusi.emuhelper")
        val src = "/data/data/io.github.mayusi.emuhelper/cache/dl/game.pkg"
        val dst = "/storage/emulated/0/Android/data/io.github.mayusi.emuhelper/files/Download/ROMs/PS4/game.pkg"
        val cmds = RootPlacement.buildMoveCommands(src, dst, appRoots)
        assertNotNull(cmds)
        for (cmd in cmds) {
            assertTrue(
                PServerCommandGuard.inspect(cmd) is PServerCommandGuard.Verdict.Allow,
                "expected guard ALLOW for built move command: <$cmd>"
            )
        }
    }

    @Test fun `buildMoveCommands returns null for an ineligible (system) source`() {
        // A system source is NOT eligible -> we never even attempt to mv (let alone delete) it.
        assertNull(RootPlacement.buildMoveCommands("/system/x", "/sdcard/roms/y"))
    }

    @Test fun `buildMoveCommands returns null for an ineligible destination`() {
        assertNull(RootPlacement.buildMoveCommands("/sdcard/a/x", "/system/x"))
    }

    @Test fun `buildMoveCommands returns null when a path has a space`() {
        assertNull(RootPlacement.buildMoveCommands("/sdcard/a/x", "/sdcard/My Folder/x"))
    }

    @Test fun `app-private source under a debug-suffixed package is eligible when its root is supplied`() {
        // Regression: the fast path was silently dead on debug builds because the app-private root
        // was hardcoded to the release package. The cache source lives under the runtime package
        // (with a .debug suffix on debug builds); supplying that root must make it eligible.
        val debugRoots = listOf(
            "/data/data/io.github.mayusi.emuhelper.debug/",
            "/data/user/0/io.github.mayusi.emuhelper.debug/"
        )
        val src = "/data/user/0/io.github.mayusi.emuhelper.debug/cache/dl/game.sfc"
        val dst = "/sdcard/roms/snes/game.sfc"
        // Without the debug root -> not eligible (no longer hardcoded).
        assertNull(RootPlacement.buildPlaceCommands(src, dst))
        // With the runtime root supplied -> eligible.
        val cmds = RootPlacement.buildPlaceCommands(src, dst, debugRoots)
        assertNotNull(cmds)
        assertEquals(3, cmds.size)
        // And the guard ALSO accepts the debug source once this app's runtime package is registered
        // (PServerBridge does this on init; mirror it here). This proves both layers agree.
        PServerCommandGuard.registerOwnPackage("io.github.mayusi.emuhelper.debug")
        for (cmd in cmds) {
            assertTrue(
                PServerCommandGuard.inspect(cmd) is PServerCommandGuard.Verdict.Allow,
                "expected guard ALLOW for built command: <$cmd>"
            )
        }
    }

    @Test fun `buildPlaceCommands returns null for an ineligible source`() {
        assertNull(RootPlacement.buildPlaceCommands("/system/x", "/sdcard/a/x"))
    }

    @Test fun `buildPlaceCommands returns null when a path has a space`() {
        assertNull(RootPlacement.buildPlaceCommands("/sdcard/a/x", "/sdcard/My Folder/x"))
    }

    // ---- the load-bearing invariant: every built command is guard-ALLOWED --------------------

    @Test fun `every command built for an eligible destination is guard-allowed`() {
        val src = "/data/data/io.github.mayusi.emuhelper/cache/dl/game.iso"
        val dst = "/storage/emulated/0/Android/data/io.github.mayusi.emuhelper/files/Download/ROMs/PSP/game.iso"
        val cmds = RootPlacement.buildPlaceCommands(src, dst, appRoots)
        assertNotNull(cmds)
        for (cmd in cmds) {
            assertTrue(
                PServerCommandGuard.inspect(cmd) is PServerCommandGuard.Verdict.Allow,
                "expected guard ALLOW for built command: <$cmd>"
            )
        }
    }

    @Test fun `commands into a known emulator data dir are guard-allowed`() {
        // RootPlacement only targets app/shared roots, but if a dest ever resolves under a guard
        // write root the built commands must still be guard-clean. Prove cp/chmod shape holds.
        val cmds = RootPlacement.buildPlaceCommands(
            "/sdcard/dl/x.zip",
            "/sdcard/roms/snes/x.zip"
        )
        assertNotNull(cmds)
        for (cmd in cmds) {
            assertTrue(PServerCommandGuard.inspect(cmd) is PServerCommandGuard.Verdict.Allow, cmd)
        }
    }
}
