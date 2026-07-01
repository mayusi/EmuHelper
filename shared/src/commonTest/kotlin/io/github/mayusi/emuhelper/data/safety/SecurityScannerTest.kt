package io.github.mayusi.emuhelper.data.safety

import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SECURITY SCANNER — end-to-end orchestration over real temp files:
 *  - a clean text file -> LOCAL_CLEAR,
 *  - a fake PE with an injection import + W+X section -> RISKY,
 *  - a ZIP renamed to .iso (type mismatch) -> at least a WARN check present,
 *  - a known-bad hash (seeded store) -> RISKY,
 *  - malformed input never crashes.
 * Temp files live in java.io.tmpdir and are deleted in finally.
 */
class SecurityScannerTest {

    private val scanner = SecurityScanner() // bundled (empty) hash list

    @Test fun `clean text file is LOCAL_CLEAR`() = runTest {
        val f = File.createTempFile("scan-clean", ".txt")
        try {
            f.writeText("This is a perfectly ordinary description of a retro game ROM. Enjoy!")
            val report = scanner.scan(f)
            assertEquals(SafetyVerdict.LOCAL_CLEAR, report.verdict, "clean text should be LOCAL_CLEAR")
            assertNotNull(report.sha256)
            assertEquals(ScoreBand.CLEAR, report.score.band)
        } finally { f.delete() }
    }

    @Test fun `fake PE with injection import and W+X section is RISKY`() = runTest {
        val f = File.createTempFile("scan-pe", ".exe")
        try {
            f.writeBytes(buildMaliciousPe())
            val report = scanner.scan(f)
            assertEquals(SafetyVerdict.RISKY, report.verdict, "W+X + injection import must be RISKY; checks=${report.checks}")
            assertEquals(ScoreBand.RISKY, report.score.band)
            assertTrue(report.checks.any { it.code.startsWith("PE") && it.status == CheckStatus.FLAG })
        } finally { f.delete() }
    }

    @Test fun `zip renamed to iso produces a type-mismatch WARN`() = runTest {
        // Create a real ZIP on disk, then present it under an .iso declared name.
        val f = File.createTempFile("scan-mismatch", ".zip")
        try {
            ZipOutputStream(f.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("a.txt")); zos.write("hi".toByteArray()); zos.closeEntry()
            }
            val report = scanner.scan(f, declaredName = "totally_a_disc_image.iso")
            assertTrue(
                report.checks.any { it.code == "MAGIC" && it.status == CheckStatus.WARN },
                "declared .iso vs zip magic should yield a MAGIC WARN; checks=${report.checks}",
            )
            assertTrue(report.verdict.severity >= SafetyVerdict.SUSPICIOUS.severity)
        } finally { f.delete() }
    }

    @Test fun `known-bad hash forces RISKY`() = runTest {
        val f = File.createTempFile("scan-hash", ".bin")
        try {
            f.writeText("known bad sample content")
            // Compute this file's SHA-256 the same way the scanner does, then seed a store with it.
            val digest = sha256Hex(f)
            val seeded = SecurityScanner(
                ThreatHashStore.fromList(ThreatHashStore.ThreatList(fullHashes = listOf(digest))),
            )
            val report = seeded.scan(f)
            assertEquals(SafetyVerdict.RISKY, report.verdict, "a seeded known-bad hash must be RISKY")
            assertEquals(100, report.score.value)
            assertTrue(report.checks.any { it.code == "HASH" && it.status == CheckStatus.FLAG })
        } finally { f.delete() }
    }

    @Test fun `expected-checksum match yields VERIFIED on an otherwise-clean file`() = runTest {
        val f = File.createTempFile("scan-verified", ".txt")
        try {
            f.writeText("clean payload for checksum verification")
            val digest = sha256Hex(f)
            val report = scanner.scan(f, expectedSha256 = digest)
            assertEquals(SafetyVerdict.VERIFIED, report.verdict, "matching source checksum -> VERIFIED")
        } finally { f.delete() }
    }

    @Test fun `RAR magic yields LIMITED (deep RAR scan out of scope)`() = runTest {
        val f = File.createTempFile("scan-rar", ".rar")
        try {
            // RAR4 signature followed by junk — enough for magic sniffing.
            f.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) + ByteArray(64))
            val report = scanner.scan(f)
            assertEquals(SafetyVerdict.LIMITED, report.verdict, "RAR must be LIMITED, not clean")
            assertEquals("rar", report.magicType)
        } finally { f.delete() }
    }

    @Test fun `empty file never crashes`() = runTest {
        val f = File.createTempFile("scan-empty", ".dat")
        try {
            val report = scanner.scan(f)
            assertNotNull(report)                 // reaching here == no crash
            assertNotNull(report.sha256)          // SHA-256 of empty is still computed
        } finally { f.delete() }
    }

    // ---- helpers ----

    private fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val b = ByteArray(8192)
            while (true) { val r = ins.read(b); if (r <= 0) break; md.update(b, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** A minimal PE32 with a W+X section AND an imported VirtualAllocEx (mirrors PeInspectorTest). */
    private fun buildMaliciousPe(): ByteArray {
        val buf = ByteArray(2048)
        fun u8(o: Int, v: Int) { buf[o] = (v and 0xFF).toByte() }
        fun u16(o: Int, v: Int) { u8(o, v); u8(o + 1, v shr 8) }
        fun u32(o: Int, v: Int) { u16(o, v and 0xFFFF); u16(o + 2, (v ushr 16) and 0xFFFF) }
        fun ascii(o: Int, s: String) { for (i in s.indices) u8(o + i, s[i].code) }

        val peOff = 0x80
        ascii(0, "MZ"); u32(0x3C, peOff)
        ascii(peOff, "PE"); u8(peOff + 2, 0); u8(peOff + 3, 0)
        val coff = peOff + 4
        u16(coff + 0, 0x14C); u16(coff + 2, 1)
        val sizeOfOptional = 0xE0
        u16(coff + 16, sizeOfOptional); u16(coff + 18, 0x0102)
        val opt = coff + 20
        u16(opt + 0, 0x10b)
        u32(opt + 92, 16)
        val dataDir = opt + 96
        val secTab = opt + sizeOfOptional
        val secVAddr = 0x1000; val secRawPtr = 0x400; val secRawSize = 0x200
        ascii(secTab + 0, ".text")
        u32(secTab + 8, secRawSize); u32(secTab + 12, secVAddr)
        u32(secTab + 16, secRawSize); u32(secTab + 20, secRawPtr)
        u32(secTab + 36, 0x20000000 or 0x80000000.toInt()) // W+X

        val descOff = secRawPtr; val descRva = secVAddr
        val thunkOff = secRawPtr + 40; val thunkRva = secVAddr + 40
        val nameOff = secRawPtr + 48; val nameRva = secVAddr + 48
        u32(descOff + 0, thunkRva); u32(descOff + 16, thunkRva)
        u32(thunkOff + 0, nameRva); u32(thunkOff + 4, 0)
        u16(nameOff, 0); ascii(nameOff + 2, "VirtualAllocEx"); u8(nameOff + 2 + 14, 0)
        u32(dataDir + 8, descRva); u32(dataDir + 12, 40)
        return buf
    }
}
