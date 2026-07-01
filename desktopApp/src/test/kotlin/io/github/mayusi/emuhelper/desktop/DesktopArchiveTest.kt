package io.github.mayusi.emuhelper.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopArchive], focused on the zip-slip guard (security-critical: a malicious
 * or malformed .zip must never be able to write outside the chosen destination folder). Uses real
 * temp files under the JVM temp dir (System.getProperty("java.io.tmpdir")) — NOT the repo — cleaned
 * up in a finally block per test.
 */
class DesktopArchiveTest {

    // ---- pure path-safety guard (safeCanonicalOutputPath) ------------------------------------------

    @Test
    fun `normal relative entry resolves under the destination dir`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "roms/game.gba",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNotNull(result)
        assertTrue(result!!.startsWith(destDir + File.separator))
        assertTrue(result.endsWith("game.gba"))
    }

    @Test
    fun `parent traversal entry is rejected`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "../../evil.exe",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNull(result)
    }

    @Test
    fun `nested parent traversal entry is rejected`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "roms/../../../evil.exe",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNull(result)
    }

    @Test
    fun `absolute path entry is rejected`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "C:\\Windows\\evil.dll",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNull(result)
    }

    @Test
    fun `absolute unix-style path entry is rejected`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "/etc/passwd",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNull(result)
    }

    @Test
    fun `blank entry name is rejected`() {
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "",
            destDirCanonicalPath = destDir,
            resolveCanonical = { name -> File(destDir, name).canonicalPath },
        )
        assertNull(result)
    }

    @Test
    fun `entry whose canonical resolution escapes the dest dir despite no dotdot is rejected`() {
        // Simulates a symlink/junction-style escape: the resolver returns a canonical path outside
        // destDir even though the raw entry name contains no "..". The guard must still catch it.
        val destDir = File("C:\\dest").canonicalPath
        val result = DesktopArchive.safeCanonicalOutputPath(
            entryName = "innocuous.txt",
            destDirCanonicalPath = destDir,
            resolveCanonical = { "C:\\outside\\innocuous.txt" },
        )
        assertNull(result)
    }

    // ---- end-to-end extraction against real files (temp dir, not the repo) -------------------------

    @Test
    fun `extractZip writes safe entries and skips traversal entries`() {
        val tempRoot = createTempDirUnderSystemTemp("dat-safe")
        val destDir = File(tempRoot, "dest").apply { mkdirs() }
        val zipFile = File(tempRoot, "archive.zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("game.gba"))
                zos.write("GBA-DATA".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("nested/save.srm"))
                zos.write("SAVE-DATA".toByteArray())
                zos.closeEntry()

                // Malicious entry — must be skipped, not written anywhere.
                zos.putNextEntry(ZipEntry("../../evil.exe"))
                zos.write("EVIL".toByteArray())
                zos.closeEntry()
            }

            val ok = DesktopArchive.extractZip(zipFile, destDir)

            assertTrue(ok, "extraction should report success")
            assertTrue(File(destDir, "game.gba").exists())
            assertEquals("GBA-DATA", File(destDir, "game.gba").readText())
            assertTrue(File(destDir, "nested/save.srm").exists())
            assertEquals("SAVE-DATA", File(destDir, "nested/save.srm").readText())

            // The traversal entry must not exist anywhere outside destDir.
            assertTrue(!File(tempRoot, "evil.exe").exists())
            assertTrue(!File(tempRoot.parentFile, "evil.exe").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `extractIfNeeded deletes the zip after a successful extraction`() {
        val tempRoot = createTempDirUnderSystemTemp("dat-delete")
        val destDir = File(tempRoot, "dest").apply { mkdirs() }
        val zipFile = File(destDir, "game.zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("game.gba"))
                zos.write("GBA-DATA".toByteArray())
                zos.closeEntry()
            }

            DesktopArchive.extractIfNeeded(
                downloadedFile = zipFile,
                destDir = destDir,
                extractArchivesEnabled = true,
            )

            assertTrue(!zipFile.exists(), "source .zip should be deleted after a successful extract")
            assertTrue(File(destDir, "game.gba").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `extractIfNeeded leaves rar files untouched`() {
        val tempRoot = createTempDirUnderSystemTemp("dat-rar")
        val destDir = File(tempRoot, "dest").apply { mkdirs() }
        val rarFile = File(destDir, "game.rar")
        try {
            rarFile.writeText("not-really-rar-bytes")

            DesktopArchive.extractIfNeeded(
                downloadedFile = rarFile,
                destDir = destDir,
                extractArchivesEnabled = true,
            )

            assertTrue(rarFile.exists(), ".rar must be left as-is — RAR extraction is Android-only")
            assertEquals("not-really-rar-bytes", rarFile.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `extractIfNeeded does nothing when the setting is off`() {
        val tempRoot = createTempDirUnderSystemTemp("dat-off")
        val destDir = File(tempRoot, "dest").apply { mkdirs() }
        val zipFile = File(destDir, "game.zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("game.gba"))
                zos.write("GBA-DATA".toByteArray())
                zos.closeEntry()
            }

            DesktopArchive.extractIfNeeded(
                downloadedFile = zipFile,
                destDir = destDir,
                extractArchivesEnabled = false,
            )

            assertTrue(zipFile.exists(), ".zip should be left alone when extraction is disabled")
            assertTrue(!File(destDir, "game.gba").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `extractZip on a corrupt zip fails and leaves no partial output claimed as success`() {
        val tempRoot = createTempDirUnderSystemTemp("dat-corrupt")
        val destDir = File(tempRoot, "dest").apply { mkdirs() }
        val zipFile = File(tempRoot, "corrupt.zip")
        try {
            // Build a real zip, then truncate it mid-entry so ZipInputStream successfully parses the
            // local file header (and reports a valid entry with a real name) but then blows up with a
            // ZipException while streaming the (now-missing) compressed data via copyTo.
            val wellFormed = File(tempRoot, "well-formed.zip")
            ZipOutputStream(wellFormed.outputStream()).use { zos ->
                zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION) // keep entry bytes predictable/large
                zos.putNextEntry(ZipEntry("game.gba"))
                zos.write(ByteArray(4096) { it.toByte() })
                zos.closeEntry()
            }
            val fullBytes = wellFormed.readBytes()
            // Cut off well past the local file header but before the entry's data ends.
            zipFile.writeBytes(fullBytes.copyOfRange(0, fullBytes.size - 2000))

            val ok = DesktopArchive.extractZip(zipFile, destDir)

            assertTrue(!ok, "a corrupt/truncated zip must report failure so the caller keeps the archive as-is")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun createTempDirUnderSystemTemp(prefix: String): File {
        val base = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        base.mkdirs()
        return base
    }
}
