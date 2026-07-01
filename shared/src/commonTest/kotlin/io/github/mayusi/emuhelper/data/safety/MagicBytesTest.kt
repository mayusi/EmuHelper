package io.github.mayusi.emuhelper.data.safety

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MAGIC BYTES — each signature detected from a crafted head buffer, declared-type mapping, and the
 * mismatch/equivalence logic (including the IA-specific "don't cry wolf on ROMs" rule).
 */
class MagicBytesTest {

    private fun head(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { (bytes[it] and 0xFF).toByte() }

    @Test fun `RAR signature detected`() =
        assertEquals(MagicBytes.T_RAR, MagicBytes.sniffHead(head(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0, 0)))

    @Test fun `7z signature detected`() =
        assertEquals(MagicBytes.T_7Z, MagicBytes.sniffHead(head(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))

    @Test fun `ZIP signature detected (PK 03 04)`() =
        assertEquals(MagicBytes.T_ZIP, MagicBytes.sniffHead(head(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))

    @Test fun `ZIP empty-archive variant (PK 05 06) detected`() =
        assertEquals(MagicBytes.T_ZIP, MagicBytes.sniffHead(head(0x50, 0x4B, 0x05, 0x06)))

    @Test fun `PE MZ signature detected`() =
        assertEquals(MagicBytes.T_PE, MagicBytes.sniffHead(head(0x4D, 0x5A, 0x90, 0x00)))

    @Test fun `MSI OLE compound signature detected`() =
        assertEquals(MagicBytes.T_MSI, MagicBytes.sniffHead(head(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1)))

    @Test fun `GZIP signature detected`() =
        assertEquals(MagicBytes.T_GZIP, MagicBytes.sniffHead(head(0x1F, 0x8B, 0x08)))

    @Test fun `ELF signature detected`() =
        assertEquals(MagicBytes.T_ELF, MagicBytes.sniffHead(head(0x7F, 0x45, 0x4C, 0x46)))

    @Test fun `PDF signature detected`() =
        assertEquals(MagicBytes.T_PDF, MagicBytes.sniffHead(head(0x25, 0x50, 0x44, 0x46, 0x2D)))

    @Test fun `NES signature detected`() =
        assertEquals(MagicBytes.T_NES, MagicBytes.sniffHead(head(0x4E, 0x45, 0x53, 0x1A)))

    @Test fun `HTML sniffed with leading whitespace`() {
        val b = "  \n<!DOCTYPE html><html></html>".toByteArray(Charsets.ISO_8859_1)
        assertEquals(MagicBytes.T_HTML, MagicBytes.sniffHead(b))
    }

    @Test fun `unknown bytes return null`() =
        assertNull(MagicBytes.sniffHead(head(0x00, 0x11, 0x22, 0x33)))

    @Test fun `ISO 9660 detected from a temp file with CD001 at 0x8001`() {
        val f = File.createTempFile("magic-iso", ".bin")
        try {
            f.outputStream().use { out ->
                val zeros = ByteArray(0x8001)
                out.write(zeros)
                out.write("CD001".toByteArray(Charsets.US_ASCII))
                out.write(ByteArray(16))
            }
            assertEquals(MagicBytes.T_ISO, MagicBytes.sniff(f))
        } finally { f.delete() }
    }

    // ---- declared type ----

    @Test fun `declaredTypeFromName maps extensions`() {
        assertEquals(MagicBytes.T_RAR, MagicBytes.declaredTypeFromName("game.rar"))
        assertEquals(MagicBytes.T_ZIP, MagicBytes.declaredTypeFromName("pack.ZIP"))
        assertEquals(MagicBytes.T_ZIP, MagicBytes.declaredTypeFromName("app.jar"))
        assertEquals(MagicBytes.T_PE, MagicBytes.declaredTypeFromName("tool.exe"))
        assertEquals(MagicBytes.ROM_BUCKET, MagicBytes.declaredTypeFromName("mario.z64"))
        assertNull(MagicBytes.declaredTypeFromName("noext"))
    }

    // ---- mismatch / equivalence ----

    @Test fun `rar declared vs rar magic is NOT a mismatch (equivalence)`() =
        assertFalse(MagicBytes.isMismatch(MagicBytes.T_RAR, MagicBytes.T_RAR))

    @Test fun `zip-family declared vs zip magic is NOT a mismatch`() =
        assertFalse(MagicBytes.isMismatch(MagicBytes.T_ZIP, MagicBytes.T_ZIP))

    @Test fun `name says zip but magic says PE IS a mismatch`() =
        assertTrue(MagicBytes.isMismatch(MagicBytes.T_ZIP, MagicBytes.T_PE))

    @Test fun `unknown-magic ROM extension is NOT a mismatch (no false positive on ROMs)`() {
        assertFalse(MagicBytes.isMismatch(MagicBytes.ROM_BUCKET, null))
        // A ROM that happens to sniff as an ISO is also fine (grouped).
        assertFalse(MagicBytes.isMismatch(MagicBytes.ROM_BUCKET, MagicBytes.T_ISO))
    }

    @Test fun `null declared or null magic never a mismatch`() {
        assertFalse(MagicBytes.isMismatch(null, MagicBytes.T_PE))
        assertFalse(MagicBytes.isMismatch(MagicBytes.T_ZIP, null))
    }
}
