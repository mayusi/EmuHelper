package io.github.mayusi.emuhelper.data.safety

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ARCHIVE INSPECTOR — crafted ZIPs exercise: path traversal (zip-slip), a normal clean archive, an
 * encrypted archive (LIMITED, not clean), and an embedded malicious-content cluster. All temp files go
 * to java.io.tmpdir and are deleted in finally.
 */
class ArchiveInspectorTest {

    private fun tempZip(build: (ZipOutputStream) -> Unit): File {
        val f = File.createTempFile("safety-zip", ".zip")
        ZipOutputStream(f.outputStream()).use(build)
        return f
    }

    @Test fun `normal zip with benign entries is clean`() {
        val f = tempZip { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("just a normal game readme".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("data/rom.bin"))
            zos.write(ByteArray(1024) { it.toByte() })
            zos.closeEntry()
        }
        try {
            val r = ArchiveInspector.inspectZip(f)
            assertTrue(r.inspectionComplete, "a normal readable zip should complete")
            assertFalse(r.hasRedFlag, "no structural or content threat expected")
            assertFalse(r.encrypted)
        } finally { f.delete() }
    }

    @Test fun `path-traversal entry is flagged`() {
        val f = tempZip { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("owned".toByteArray())
            zos.closeEntry()
        }
        try {
            val r = ArchiveInspector.inspectZip(f)
            assertTrue(r.pathTraversal, "a '../' entry must trip the zip-slip guard")
            assertTrue(r.hasRedFlag)
        } finally { f.delete() }
    }

    @Test fun `absolute and drive-prefix entries are flagged`() {
        val f1 = tempZip { it.putNextEntry(ZipEntry("/etc/passwd")); it.closeEntry() }
        val f2 = tempZip { it.putNextEntry(ZipEntry("C:/Windows/system32/x.dll")); it.closeEntry() }
        try {
            assertTrue(ArchiveInspector.inspectZip(f1).pathTraversal, "absolute path must flag")
            assertTrue(ArchiveInspector.inspectZip(f2).pathTraversal, "drive prefix must flag")
        } finally { f1.delete(); f2.delete() }
    }

    @Test fun `dangerous-extension entries are counted but do not flag by presence alone`() {
        val f = tempZip { zos ->
            zos.putNextEntry(ZipEntry("setup.exe")); zos.write("MZ harmless-looking".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib.dll")); zos.write("dll".toByteArray()); zos.closeEntry()
        }
        try {
            val r = ArchiveInspector.inspectZip(f)
            assertTrue(r.dangerousEntryCount >= 2, "should count exe+dll entries")
            assertFalse(r.hasRedFlag, "mere presence of exe/dll must NOT flag (games ship exes)")
        } finally { f.delete() }
    }

    @Test fun `embedded malicious content cluster is flagged`() {
        val f = tempZip { zos ->
            zos.putNextEntry(ZipEntry("install.ps1"))
            zos.write(
                "\$b=[Convert]::FromBase64String(\$p); iex (New-Object Net.WebClient).DownloadString('h')"
                    .toByteArray(),
            )
            zos.closeEntry()
        }
        try {
            val r = ArchiveInspector.inspectZip(f)
            assertTrue(r.contentThreat, "a real script cluster in an entry must be flagged")
            assertTrue(r.hasRedFlag)
        } finally { f.delete() }
    }

    @Test fun `encrypted zip is reported as a limitation, not clean`() {
        // Hand-build a minimal ZIP with ONE stored (uncompressed) entry whose local-header
        // general-purpose bit 0 (encrypted) is set. We don't need valid crypto — only the flag bit.
        val f = File.createTempFile("safety-encrypted", ".zip")
        try {
            writeMinimalZipWithFlag(f, name = "secret.bin", gpFlag = 0x1)
            val r = ArchiveInspector.inspectZip(f)
            assertTrue(r.encrypted, "GP-flag bit 0 must be detected as encryption")
            assertFalse(r.inspectionComplete, "an encrypted archive can't be fully inspected -> LIMITED")
        } finally { f.delete() }
    }

    /**
     * Write a hand-rolled ZIP: local file header + stored data + central directory + EOCD, with a
     * caller-chosen general-purpose flag. Kept minimal but valid enough for ZipFile to open AND for the
     * raw local-header probe to read the flag.
     */
    private fun writeMinimalZipWithFlag(file: File, name: String, gpFlag: Int) {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val content = "encrypted-content-placeholder".toByteArray()
        val crc = CRC32().apply { update(content) }.value
        val out = ByteArrayOutputStream()

        fun w16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun w32(v: Long) {
            out.write((v and 0xFF).toInt()); out.write(((v ushr 8) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt()); out.write(((v ushr 24) and 0xFF).toInt())
        }

        val lfhOffset = 0L
        // ---- Local file header ----
        w32(0x04034b50)          // signature PK\3\4
        w16(20)                  // version needed
        w16(gpFlag)              // general purpose flag (bit 0 = encrypted)
        w16(0)                   // method = stored
        w16(0); w16(0)           // mod time/date
        w32(crc)                 // crc32
        w32(content.size.toLong()) // compressed size (stored)
        w32(content.size.toLong()) // uncompressed size
        w16(nameBytes.size)      // name length
        w16(0)                   // extra length
        out.write(nameBytes)
        out.write(content)

        // ---- Central directory ----
        val cdOffset = out.size().toLong()
        w32(0x02014b50)          // central dir signature
        w16(20); w16(20)         // version made by / needed
        w16(gpFlag)              // gp flag
        w16(0)                   // method
        w16(0); w16(0)           // time/date
        w32(crc)
        w32(content.size.toLong())
        w32(content.size.toLong())
        w16(nameBytes.size)
        w16(0); w16(0)           // extra / comment length
        w16(0); w16(0)           // disk number / internal attrs
        w32(0)                   // external attrs
        w32(lfhOffset)           // relative offset of local header
        out.write(nameBytes)
        val cdSize = out.size().toLong() - cdOffset

        // ---- End of central directory ----
        w32(0x06054b50)
        w16(0); w16(0)           // disk numbers
        w16(1); w16(1)           // entries this disk / total
        w32(cdSize)
        w32(cdOffset)
        w16(0)                   // comment length

        file.writeBytes(out.toByteArray())
    }
}
