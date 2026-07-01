package io.github.mayusi.emuhelper.data.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PE INSPECTOR — hand-crafts minimal PE byte buffers to exercise the pure parser:
 *  - a W+X section is flagged,
 *  - a suspicious import is resolved and matched,
 *  - a truncated/garbage PE never crashes and reports inspectionComplete honestly,
 *  - a non-PE returns NOT_PE.
 */
class PeInspectorTest {

    // Little-endian writers into a fixed-size scratch buffer.
    private class Pe(size: Int) {
        val buf = ByteArray(size)
        fun u8(off: Int, v: Int) { buf[off] = (v and 0xFF).toByte() }
        fun u16(off: Int, v: Int) { u8(off, v); u8(off + 1, v shr 8) }
        fun u32(off: Int, v: Int) { u16(off, v and 0xFFFF); u16(off + 2, (v ushr 16) and 0xFFFF) }
        fun ascii(off: Int, s: String) { for (i in s.indices) u8(off + i, s[i].code) }
    }

    /**
     * Build a minimal but structurally-valid PE32.
     * @param sectionChars characteristics flags for the single section (control W+X, etc.)
     * @param withImport   if true, wires an import directory that imports one named function.
     * @param importName   the imported function name to embed.
     */
    private fun buildPe(
        sectionChars: Int,
        withImport: Boolean = false,
        importName: String = "VirtualAllocEx",
    ): ByteArray {
        val pe = Pe(2048)
        val peOff = 0x80
        // DOS header.
        pe.ascii(0, "MZ")
        pe.u32(0x3C, peOff)
        // PE signature.
        pe.ascii(peOff, "PE"); pe.u8(peOff + 2, 0); pe.u8(peOff + 3, 0)
        val coff = peOff + 4
        pe.u16(coff + 0, 0x14C)         // machine i386
        pe.u16(coff + 2, 1)             // 1 section
        val sizeOfOptional = 0xE0       // typical PE32 optional header size
        pe.u16(coff + 16, sizeOfOptional)
        pe.u16(coff + 18, 0x0102)       // characteristics (executable)
        val opt = coff + 20
        pe.u16(opt + 0, 0x10b)          // PE32 magic
        // NumberOfRvaAndSizes at opt+92; data dirs start opt+96.
        pe.u32(opt + 92, 16)
        val dataDir = opt + 96

        // Section table right after the optional header.
        val secTab = opt + sizeOfOptional
        // Lay section raw data at 0x400; give it a virtual address of 0x1000.
        val secVAddr = 0x1000
        val secRawPtr = 0x400
        val secRawSize = 0x200
        pe.ascii(secTab + 0, ".text")
        pe.u32(secTab + 8, secRawSize)      // VirtualSize
        pe.u32(secTab + 12, secVAddr)       // VirtualAddress
        pe.u32(secTab + 16, secRawSize)     // SizeOfRawData
        pe.u32(secTab + 20, secRawPtr)      // PointerToRawData
        pe.u32(secTab + 36, sectionChars)   // Characteristics

        if (withImport) {
            // Put the import directory inside the section (so RVA->offset resolves via the section).
            // Layout within the raw section:
            //   descriptor (20 bytes) + terminator (20 bytes) | thunk array (2 * 4) | hint/name.
            val descRva = secVAddr
            val descOff = secRawPtr
            val thunkRva = secVAddr + 40
            val thunkOff = secRawPtr + 40
            val nameRva = secVAddr + 40 + 8   // after the 2-entry thunk array
            val nameOff = secRawPtr + 40 + 8

            // Import descriptor: OriginalFirstThunk (RVA of thunk array), ..., FirstThunk.
            pe.u32(descOff + 0, thunkRva)     // OriginalFirstThunk
            pe.u32(descOff + 16, thunkRva)    // FirstThunk
            // Terminator descriptor is already zero (buffer inits to 0).

            // Thunk array: [ nameRva, 0 ].
            pe.u32(thunkOff + 0, nameRva)     // by-name (high bit clear) -> RVA of hint/name entry
            pe.u32(thunkOff + 4, 0)           // null terminator

            // Hint/name: 2-byte hint then the ASCII name (null-terminated).
            pe.u16(nameOff, 0)
            pe.ascii(nameOff + 2, importName)
            pe.u8(nameOff + 2 + importName.length, 0)

            // Point the Import data directory (index 1) at the descriptor.
            pe.u32(dataDir + 1 * 8 + 0, descRva)
            pe.u32(dataDir + 1 * 8 + 4, 40)
        }

        return pe.buf
    }

    @Test fun `writable plus executable section is flagged`() {
        val wx = 0x20000000 or 0x80000000.toInt()   // MEM_EXECUTE | MEM_WRITE
        val findings = PeInspector.inspect(buildPe(wx))
        assertTrue(findings.isPe)
        assertTrue(findings.hasWritableExecutable, "a W+X section must be flagged")
        assertTrue(findings.hasRedFlag)
    }

    @Test fun `read-only executable section is NOT flagged as W plus X`() {
        val rx = 0x20000000 or 0x40000000            // MEM_EXECUTE | MEM_READ
        val findings = PeInspector.inspect(buildPe(rx))
        assertTrue(findings.isPe)
        assertFalse(findings.hasWritableExecutable)
    }

    @Test fun `suspicious import is resolved and matched`() {
        val rx = 0x20000000 or 0x40000000
        val findings = PeInspector.inspect(buildPe(rx, withImport = true, importName = "VirtualAllocEx"))
        assertTrue(findings.isPe)
        assertTrue(
            findings.suspiciousImports.any { it.contains("VirtualAllocEx") && it.contains("injection", ignoreCase = true) },
            "VirtualAllocEx should resolve and match Process injection; got ${findings.suspiciousImports}",
        )
        assertTrue(findings.hasRedFlag)
    }

    @Test fun `benign import is not matched`() {
        val rx = 0x20000000 or 0x40000000
        val findings = PeInspector.inspect(buildPe(rx, withImport = true, importName = "GetModuleHandleW"))
        assertTrue(findings.suspiciousImports.isEmpty(), "a benign import must not be flagged")
    }

    @Test fun `non-PE bytes return NOT_PE`() {
        val findings = PeInspector.inspect(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFalse(findings.isPe)
    }

    @Test fun `truncated PE does not crash and marks incomplete`() {
        // MZ + e_lfanew pointing at a PE sig, but the section table runs off the end.
        val pe = Pe(0x100)
        pe.ascii(0, "MZ")
        val peOff = 0x80
        pe.u32(0x3C, peOff)
        pe.ascii(peOff, "PE"); pe.u8(peOff + 2, 0); pe.u8(peOff + 3, 0)
        val coff = peOff + 4
        pe.u16(coff + 2, 8)             // claim 8 sections that don't fit
        pe.u16(coff + 16, 0xE0)
        val findings = PeInspector.inspect(pe.buf)
        assertTrue(findings.isPe)
        assertFalse(findings.inspectionComplete, "a truncated section table must report incomplete")
        // Must not throw — reaching this assertion is the point.
    }

    @Test fun `random garbage never throws`() {
        val rnd = java.util.Random(9L)
        repeat(50) {
            val n = 4 + rnd.nextInt(4096)
            val buf = ByteArray(n).also { rnd.nextBytes(it) }
            // Force MZ so it enters the parser path.
            if (buf.size >= 2) { buf[0] = 'M'.code.toByte(); buf[1] = 'Z'.code.toByte() }
            PeInspector.inspect(buf) // just must not throw
        }
        assertTrue(true)
    }
}
