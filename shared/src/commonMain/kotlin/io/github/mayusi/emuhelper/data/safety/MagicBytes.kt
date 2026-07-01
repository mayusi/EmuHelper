package io.github.mayusi.emuhelper.data.safety

import java.io.File
import java.io.RandomAccessFile

/**
 * MAGIC-BYTE SNIFFING — identify a file's TRUE type from its leading bytes, independent of its name,
 * and compare that against the type its extension CLAIMS. Pure JVM I/O, defensive, never throws.
 *
 * Design ported from PcHelper's magic sniffer + its SAFE_MIME_EQUIVALENTS idea: an extension-vs-magic
 * disagreement is only a WARN when the two are genuinely different families. Legitimately-equivalent
 * pairs (a .rar whose magic is "rar", a .jar whose magic is "zip") are NOT a mismatch.
 *
 * IMPORTANT for the Internet Archive: ROMs very often ship with a console-specific extension the
 * sniffer doesn't recognise (.z64, .gba, .cso, .chd, .bin, or no extension at all) and no magic
 * signature. So "unknown magic + rom-ish extension" is treated as NOT a mismatch — we don't cry wolf
 * on legit ROM dumps.
 */
object MagicBytes {

    /** Normalised true-type strings this sniffer can emit. */
    const val T_RAR = "rar"
    const val T_ZIP = "zip"
    const val T_7Z = "7z"
    const val T_PE = "pe"          // Windows executable / DLL (MZ + PE)
    const val T_MSI = "msi"        // OLE compound (MSI installer / legacy Office)
    const val T_GZIP = "gzip"
    const val T_ELF = "elf"
    const val T_PDF = "pdf"
    const val T_ISO = "iso"        // ISO 9660 optical image
    const val T_NES = "nes"        // iNES ROM
    const val T_HTML = "html"

    /** How many leading bytes we need for the fixed-offset signatures below. */
    private const val HEAD_LEN = 16

    /**
     * Sniff the true type of [file] from its leading bytes (+ the ISO 9660 offsets, which live deep in
     * the file). Returns a normalised type string (see the T_* constants) or null if unrecognised.
     * Never throws — an unreadable file yields null.
     */
    fun sniff(file: File): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val head = ByteArray(minOf(HEAD_LEN.toLong(), len).toInt())
            raf.seek(0)
            raf.readFully(head)
            val headType = sniffHead(head)
            if (headType != null) return@use headType
            // ISO 9660: "CD001" appears at a volume-descriptor offset (sector 16 = 0x8000, +1 byte).
            // Standard candidates for 2048-byte sectors and a couple of raw-mode variants.
            if (looksLikeIso9660(raf, len)) T_ISO else null
        }
    } catch (_: Throwable) {
        null
    }

    /** Sniff purely from an in-memory head buffer (used by tests and by [sniff]). */
    fun sniffHead(head: ByteArray): String? {
        if (head.isEmpty()) return null

        // RAR:  52 61 72 21 1A 07  ("Rar!" .. )
        if (startsWith(head, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return T_RAR

        // 7z:   37 7A BC AF 27 1C
        if (startsWith(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return T_7Z

        // ZIP family: "PK" (50 4B) then one of {03,05,07} {04,06,08}. Covers normal, empty, spanned.
        if (head.size >= 4 && head[0].i() == 0x50 && head[1].i() == 0x4B) {
            val b2 = head[2].i(); val b3 = head[3].i()
            if ((b2 == 0x03 || b2 == 0x05 || b2 == 0x07) && (b3 == 0x04 || b3 == 0x06 || b3 == 0x08)) {
                return T_ZIP
            }
        }

        // PE / EXE: "MZ" (4D 5A). (Could also be a DOS stub; PE-ness is confirmed later by PeInspector.)
        if (startsWith(head, 0x4D, 0x5A)) return T_PE

        // OLE / MSI compound document: D0 CF 11 E0 A1 B1 1A E1
        if (startsWith(head, 0xD0, 0xCF, 0x11, 0xE0)) return T_MSI

        // GZIP: 1F 8B
        if (startsWith(head, 0x1F, 0x8B)) return T_GZIP

        // ELF: 7F 45 4C 46
        if (startsWith(head, 0x7F, 0x45, 0x4C, 0x46)) return T_ELF

        // PDF: 25 50 44 46  ("%PDF")
        if (startsWith(head, 0x25, 0x50, 0x44, 0x46)) return T_PDF

        // iNES ROM: 4E 45 53 1A  ("NES")
        if (startsWith(head, 0x4E, 0x45, 0x53, 0x1A)) return T_NES

        // HTML / text sniff: leading (whitespace-trimmed, lowercased) "<!doctype html" or "<html".
        if (looksLikeHtml(head)) return T_HTML

        return null
    }

    /** True if "CD001" sits at any standard ISO 9660 volume-descriptor offset. */
    private fun looksLikeIso9660(raf: RandomAccessFile, len: Long): Boolean {
        // Offsets: 0x8001 (2048-byte sectors), 0x8801 / 0x9001 (raw 2352-byte Mode1/Mode2 variants).
        val offsets = longArrayOf(0x8001L, 0x8801L, 0x9001L)
        val sig = byteArrayOf(0x43, 0x44, 0x30, 0x30, 0x31) // "CD001"
        val buf = ByteArray(5)
        for (off in offsets) {
            if (off + 5 > len) continue
            try {
                raf.seek(off)
                raf.readFully(buf)
                if (buf.contentEquals(sig)) return true
            } catch (_: Throwable) { /* skip this offset */ }
        }
        return false
    }

    private fun looksLikeHtml(head: ByteArray): Boolean {
        // Look at a small prefix, skip leading whitespace / BOM, compare case-insensitively.
        val n = minOf(head.size, 512)
        var i = 0
        // Skip UTF-8 BOM.
        if (n >= 3 && head[0].i() == 0xEF && head[1].i() == 0xBB && head[2].i() == 0xBF) i = 3
        while (i < n) {
            val c = head[i].i()
            if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) i++ else break
        }
        val rest = String(head, i, n - i, Charsets.ISO_8859_1).lowercase()
        return rest.startsWith("<!doctype html") || rest.startsWith("<html")
    }

    // ---- declared type (from name) ------------------------------------------------------------

    /**
     * Type implied by [name]'s extension, normalised to the same vocabulary as [sniff] where they
     * overlap. Archive/exe/doc extensions map to their family; ROM/disc extensions map to a synthetic
     * "rom" bucket used by the equivalence logic (they have no reliable magic).
     * Returns null for an extension we don't recognise at all.
     */
    fun declaredTypeFromName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return when (name.substring(dot + 1).lowercase()) {
            "rar" -> T_RAR
            "zip" -> T_ZIP
            "jar", "apk", "cbz", "epub" -> T_ZIP // ZIP-container formats
            "7z" -> T_7Z
            "gz", "tgz" -> T_GZIP
            "exe", "dll", "sys", "scr", "ocx" -> T_PE
            "msi" -> T_MSI
            "pdf" -> T_PDF
            "iso" -> T_ISO
            "nes" -> T_NES
            "html", "htm" -> T_HTML
            in ROM_EXTENSIONS -> ROM_BUCKET
            else -> null
        }
    }

    /** Synthetic bucket for console-ROM / disc-image extensions that have no reliable magic. */
    const val ROM_BUCKET = "rom"

    /** ROM / disc-image / save extensions common on the Internet Archive. */
    val ROM_EXTENSIONS: Set<String> = setOf(
        // Nintendo
        "n64", "z64", "v64", "gba", "gb", "gbc", "nds", "3ds", "cia", "nsp", "xci", "wbfs", "rvz",
        "wad", "sfc", "smc", "fds",
        // Sony
        "pkg", "iso", "cso", "chd", "pbp", "ecm", "img",
        // Sega / Atari / misc disc & cart
        "gg", "sms", "md", "gen", "smd", "32x", "a26", "a78", "lnx", "ngp", "ngc", "vb", "ws", "wsc",
        // Generic dumps
        "bin", "rom", "cue", "gdi", "nrg", "mdf", "mds", "ciso", "gcm", "dol", "elf",
    )

    // ---- equivalence / mismatch -------------------------------------------------------------

    /**
     * Groups of types that are legitimately the same thing. Mirrors PcHelper's SAFE_MIME_EQUIVALENTS:
     * a declared type and a sniffed type in the SAME group is NOT a mismatch.
     */
    private val EQUIVALENCE_GROUPS: List<Set<String>> = listOf(
        setOf(T_ZIP),                 // .zip/.jar/.apk (all declared as T_ZIP) vs magic "zip"
        setOf(T_RAR),
        setOf(T_7Z),
        setOf(T_GZIP),
        setOf(T_PE),                  // .exe/.dll vs magic "pe"
        setOf(T_MSI),
        setOf(T_PDF),
        setOf(T_HTML),
        setOf(T_NES),
        // A declared ROM/disc image can legitimately BE an ISO (many .pkg/.chd/.img wrap disc images),
        // and its magic is frequently unrecognised. Group ROM with ISO so neither is a mismatch.
        setOf(ROM_BUCKET, T_ISO),
    )

    private fun sameFamily(a: String, b: String): Boolean {
        if (a == b) return true
        return EQUIVALENCE_GROUPS.any { it.contains(a) && it.contains(b) }
    }

    /**
     * Extension-vs-magic mismatch decision.
     *
     * Returns true ONLY when the name claims one concrete family and the magic bytes prove a DIFFERENT
     * concrete family (the classic "name says .jpg / .iso but it's really a PE" trick).
     *
     * NOT a mismatch — returns false — when:
     *   - either side is unknown/null (can't prove disagreement),
     *   - the two are in the same equivalence family,
     *   - the declared type is a ROM/disc bucket and the magic is unrecognised (legit ROM dumps).
     *
     * @param declared normalised declared type from [declaredTypeFromName].
     * @param magic    normalised sniffed type from [sniff]/[sniffHead], or null if unrecognised.
     */
    fun isMismatch(declared: String?, magic: String?): Boolean {
        if (declared == null) return false
        // Unknown magic: only a "mismatch" if we could otherwise identify it. For ROMs (no magic) and
        // for anything with unrecognised magic, we can't PROVE disagreement -> not a mismatch.
        if (magic == null) return false
        return !sameFamily(declared, magic)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun Byte.i(): Int = this.toInt() and 0xFF

    private fun startsWith(buf: ByteArray, vararg sig: Int): Boolean {
        if (buf.size < sig.size) return false
        for (i in sig.indices) if (buf[i].i() != (sig[i] and 0xFF)) return false
        return true
    }
}
