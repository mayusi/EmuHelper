package io.github.mayusi.emuhelper.data.safety

/**
 * PE (Portable Executable) inspector — PURE byte-array parsing, NO OS / loader calls.
 *
 * Ported from PcHelper's PeParser, reimplemented as defensive offset math over a byte buffer so it is
 * cross-platform (Windows PEs can be inspected from Android/Linux/desktop alike) and — critically —
 * cannot execute or map the file it inspects. Every offset is bounds-checked; any malformed input
 * yields a PARTIAL result with [PeFindings.inspectionComplete] == false rather than an exception.
 *
 * What it detects:
 *   (a) a section that is BOTH writable AND executable (W+X) — a strong self-modifying/unpacking flag,
 *   (b) a packed-section heuristic: an executable section, rawSize >= 4096, Shannon entropy >= 7.4 on a
 *       ≤64 KB sample, and virtualSize > rawSize*4 (i.e. it expands massively at runtime),
 *   (c) suspicious imports: walks the Import Address Table and matches imported function names against
 *       [SUSPICIOUS_IMPORTS] (injection / hollowing / anti-debug / ransomware / persistence / downloader).
 */
object PeInspector {

    // COFF characteristics / section flags.
    private const val SCN_MEM_EXECUTE = 0x20000000
    private const val SCN_MEM_WRITE = 0x80000000.toInt()

    // Optional-header magics.
    private const val PE32_MAGIC = 0x10b
    private const val PE32PLUS_MAGIC = 0x20b

    private const val PACK_ENTROPY_THRESHOLD = 7.4
    private const val PACK_MIN_RAW = 4096
    private const val ENTROPY_SAMPLE = 64 * 1024

    /**
     * Suspicious Win32 imports -> why they're suspicious. Matched case-insensitively against imported
     * function names (an A/W suffix, e.g. RegCreateKeyExW, still matches via prefix/contains).
     */
    val SUSPICIOUS_IMPORTS: Map<String, String> = linkedMapOf(
        "virtualallocex" to "Process injection",
        "createremotethread" to "Process injection",
        "ntunmapviewofsection" to "Process hollowing",
        "queueuserapc" to "APC injection",
        "writeprocessmemory" to "Process memory write",
        "isdebuggerpresent" to "Anti-debugging",
        "cryptencrypt" to "Encryption (ransomware indicator)",
        "regcreatekey" to "Registry persistence",
        "regsetvalue" to "Registry persistence",
        "urldownloadtofile" to "Downloader",
    )

    /**
     * Result of a PE inspection.
     * @param isPe               true if the MZ + PE signatures were found (else this wasn't a PE).
     * @param inspectionComplete true if the walk finished without hitting a malformed/truncated region.
     * @param reasons            distinct human-readable red-flag reasons (empty == nothing found).
     * @param hasWritableExecutable  a W+X section was present (the strongest single flag).
     * @param hasPackedSection       the packed-section heuristic fired.
     * @param suspiciousImports  matched suspicious import descriptions (deduped, may be empty).
     */
    data class PeFindings(
        val isPe: Boolean,
        val inspectionComplete: Boolean,
        val reasons: List<String>,
        val hasWritableExecutable: Boolean,
        val hasPackedSection: Boolean,
        val suspiciousImports: List<String>,
    ) {
        /** Any real red flag worth escalating on. */
        val hasRedFlag: Boolean
            get() = hasWritableExecutable || suspiciousImports.isNotEmpty()

        companion object {
            val NOT_PE = PeFindings(false, true, emptyList(), false, false, emptyList())
        }
    }

    private class Section(
        val virtualSize: Int,
        val virtualAddress: Int,
        val rawSize: Int,
        val rawPointer: Int,
        val characteristics: Int,
    )

    /**
     * Inspect [buf] as a PE. Returns [PeFindings]. Never throws: on ANY parse trouble it returns
     * whatever it has with [PeFindings.inspectionComplete] == false.
     */
    fun inspect(buf: ByteArray): PeFindings {
        val reasons = LinkedHashSet<String>()
        var wx = false
        var packed = false
        val imports = LinkedHashSet<String>()
        var complete = true

        try {
            // DOS header: "MZ" + e_lfanew (LONG) at 0x3C -> offset of the PE header.
            if (buf.size < 0x40 || u8(buf, 0) != 0x4D || u8(buf, 1) != 0x5A) {
                return PeFindings.NOT_PE
            }
            val peOff = u32(buf, 0x3C)
            if (peOff < 0 || peOff + 24 > buf.size) return PeFindings.NOT_PE
            // PE signature "PE\0\0" (50 45 00 00).
            if (u8(buf, peOff) != 0x50 || u8(buf, peOff + 1) != 0x45 ||
                u8(buf, peOff + 2) != 0x00 || u8(buf, peOff + 3) != 0x00
            ) {
                return PeFindings.NOT_PE
            }

            // COFF file header (20 bytes) right after the signature.
            val coff = peOff + 4
            val numberOfSections = u16(buf, coff + 2)
            val sizeOfOptionalHeader = u16(buf, coff + 16)
            val optStart = coff + 20

            // Optional header magic tells us PE32 vs PE32+ (affects data-dir offset).
            var dataDirOffset = -1
            var numRvaAndSizes = 0
            if (optStart + 2 <= buf.size) {
                val magic = u16(buf, optStart)
                // NumberOfRvaAndSizes lives at optStart+92 (PE32) / +108 (PE32+); data dirs follow it.
                when (magic) {
                    PE32_MAGIC -> {
                        if (optStart + 96 <= buf.size) {
                            numRvaAndSizes = u32(buf, optStart + 92)
                            dataDirOffset = optStart + 96
                        }
                    }
                    PE32PLUS_MAGIC -> {
                        if (optStart + 112 <= buf.size) {
                            numRvaAndSizes = u32(buf, optStart + 108)
                            dataDirOffset = optStart + 112
                        }
                    }
                    else -> { /* unknown optional header; sections still parse below */ }
                }
            }

            // Section table follows the optional header.
            val sectionTableStart = optStart + sizeOfOptionalHeader
            val sections = ArrayList<Section>()
            if (numberOfSections in 1..256) {
                for (s in 0 until numberOfSections) {
                    val base = sectionTableStart + s * 40
                    if (base + 40 > buf.size) { complete = false; break }
                    val vSize = u32(buf, base + 8)
                    val vAddr = u32(buf, base + 12)
                    val rSize = u32(buf, base + 16)
                    val rPtr = u32(buf, base + 20)
                    val chars = i32(buf, base + 36)
                    sections.add(Section(vSize, vAddr, rSize, rPtr, chars))
                }
            } else {
                complete = false
            }

            // (a) + (b): per-section W+X and packing checks.
            for (sec in sections) {
                val exec = (sec.characteristics and SCN_MEM_EXECUTE) != 0
                val write = (sec.characteristics and SCN_MEM_WRITE) != 0
                if (exec && write) {
                    wx = true
                    reasons.add("writable+executable section (self-modifying / unpacking indicator)")
                }
                if (exec && sec.rawSize >= PACK_MIN_RAW && sec.virtualSize > sec.rawSize * 4L) {
                    val from = sec.rawPointer
                    val to = minOf(sec.rawPointer.toLong() + minOf(sec.rawSize, ENTROPY_SAMPLE), buf.size.toLong()).toInt()
                    if (from in 0 until buf.size && to > from) {
                        val h = Entropy.shannon(buf, from, to)
                        if (h >= PACK_ENTROPY_THRESHOLD) {
                            packed = true
                            reasons.add("high-entropy executable section (packed, H=${"%.2f".format(h)})")
                        }
                    }
                }
            }

            // (c): import table walk.
            if (dataDirOffset >= 0 && numRvaAndSizes >= 2) {
                // Data directory index 1 = Import Directory (8 bytes each: RVA, Size).
                val importDirEntry = dataDirOffset + 1 * 8
                if (importDirEntry + 8 <= buf.size) {
                    val importRva = u32(buf, importDirEntry)
                    if (importRva > 0) {
                        walkImports(buf, importRva, sections, imports, magicIsPlus(buf, optStart)) {
                            complete = false
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            complete = false
        }

        val importReasons = imports.toList()
        for (r in importReasons) reasons.add("suspicious import: $r")

        return PeFindings(
            isPe = true,
            inspectionComplete = complete,
            reasons = reasons.toList(),
            hasWritableExecutable = wx,
            hasPackedSection = packed,
            suspiciousImports = importReasons,
        )
    }

    private fun magicIsPlus(buf: ByteArray, optStart: Int): Boolean =
        optStart + 2 <= buf.size && u16(buf, optStart) == PE32PLUS_MAGIC

    /**
     * Walk the import directory: an array of 20-byte IMAGE_IMPORT_DESCRIPTORs terminated by an all-zero
     * entry. For each, follow the (Original)FirstThunk to the array of thunks; for name-based imports,
     * read the hint/name table entry (skip 2-byte hint) and match against [SUSPICIOUS_IMPORTS].
     * Fully bounds-checked; on trouble it calls [onIncomplete] and stops.
     */
    private fun walkImports(
        buf: ByteArray,
        importRva: Int,
        sections: List<Section>,
        out: MutableSet<String>,
        isPlus: Boolean,
        onIncomplete: () -> Unit,
    ) {
        val dirOff = rvaToOffset(importRva, sections)
        if (dirOff < 0) { onIncomplete(); return }

        val thunkSize = if (isPlus) 8 else 4
        // PE32+ high bit (import-by-ordinal) is bit 63; PE32 is bit 31.
        val ordinalMask = if (isPlus) (1L shl 63) else (1L shl 31)

        var desc = dirOff
        var descCount = 0
        while (desc + 20 <= buf.size && descCount < 4096) {
            val origFirstThunk = u32(buf, desc)      // OriginalFirstThunk (INT)
            val firstThunk = u32(buf, desc + 16)     // FirstThunk (IAT)
            // All-zero descriptor terminates the array.
            if (origFirstThunk == 0 && firstThunk == 0) break

            val thunkRva = if (origFirstThunk != 0) origFirstThunk else firstThunk
            var thunkOff = rvaToOffset(thunkRva, sections)
            if (thunkOff < 0) { onIncomplete(); desc += 20; descCount++; continue }

            var thunkCount = 0
            while (thunkOff + thunkSize <= buf.size && thunkCount < 65536) {
                val entry = if (isPlus) u64(buf, thunkOff) else (u32(buf, thunkOff).toLong() and 0xFFFFFFFFL)
                if (entry == 0L) break
                // Import-by-name only (skip ordinals).
                if ((entry and ordinalMask) == 0L) {
                    val nameRva = (entry and 0x7FFFFFFFL).toInt()  // low 31 bits = RVA of hint/name
                    val nameOff = rvaToOffset(nameRva, sections)
                    if (nameOff >= 0) {
                        val name = readAsciiZ(buf, nameOff + 2, 128) // +2 skips the 2-byte hint
                        matchImport(name, out)
                    }
                }
                thunkOff += thunkSize
                thunkCount++
            }
            desc += 20
            descCount++
        }
        if (descCount >= 4096) onIncomplete()
    }

    private fun matchImport(name: String, out: MutableSet<String>) {
        if (name.isEmpty()) return
        val lower = name.lowercase()
        for ((needle, why) in SUSPICIOUS_IMPORTS) {
            if (lower.contains(needle)) out.add("$name ($why)")
        }
    }

    /** Convert an RVA to a file offset using the section table. Returns -1 if it maps nowhere. */
    private fun rvaToOffset(rva: Int, sections: List<Section>): Int {
        if (rva < 0) return -1
        for (sec in sections) {
            val vaEnd = sec.virtualAddress + maxOf(sec.virtualSize, sec.rawSize)
            if (rva >= sec.virtualAddress && rva < vaEnd) {
                val delta = rva - sec.virtualAddress
                if (delta < 0 || delta > sec.rawSize) return -1
                return sec.rawPointer + delta
            }
        }
        return -1
    }

    private fun readAsciiZ(buf: ByteArray, off: Int, max: Int): String {
        if (off < 0 || off >= buf.size) return ""
        val sb = StringBuilder()
        var i = off
        val end = minOf(buf.size, off + max)
        while (i < end) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0) break
            // Keep printable ASCII only.
            if (c in 0x20..0x7E) sb.append(c.toChar()) else break
            i++
        }
        return sb.toString()
    }

    // ---- little-endian readers (all bounds-safe; callers pre-check where it matters) -----------

    private fun u8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF
    private fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
    private fun i32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    /** Unsigned 32-bit as an Int; values above Int.MAX are clamped to a safe sentinel (-1 caller-checked). */
    private fun u32(buf: ByteArray, off: Int): Int {
        val v = (buf[off].toInt() and 0xFF).toLong() or
            ((buf[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((buf[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((buf[off + 3].toInt() and 0xFF).toLong() shl 24)
        // Offsets/sizes in PEs we care about fit in Int; if a hostile file sets the high bit, treat it
        // as out-of-range so bounds checks reject it rather than wrapping to a negative usable value.
        return if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    private fun u64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[off + i].toInt() and 0xFF).toLong() shl (8 * i))
        return v
    }
}
