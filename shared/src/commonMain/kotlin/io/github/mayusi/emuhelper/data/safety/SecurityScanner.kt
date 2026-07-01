package io.github.mayusi.emuhelper.data.safety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * SECURITY SCANNER — the orchestrator that runs every technique over a just-downloaded file and folds
 * the results into one [ScanReport].
 *
 * BEST-EFFORT, OFFLINE, CROSS-PLATFORM (JVM). Framed to users as a bonus, not a guarantee: the top
 * verdict is [SafetyVerdict.RISKY] ("we warn hard"), never a hard block. Every individual check is
 * wrapped so a single failing technique degrades to a limitation ([CheckStatus.NOT_APPLICABLE]) and
 * NEVER fails the whole scan.
 *
 * DI-free by design (:shared is DI-free): both the Android app and the Desktop app construct this
 * manually. The engine performs zero mandatory network I/O.
 *
 * Usage:
 * ```
 * val scanner = SecurityScanner()               // bundled (empty) hash list
 * val report  = scanner.scan(downloadedFile)    // suspend; runs off the main thread
 * ```
 */
class SecurityScanner(
    private val hashStore: ThreatHashStore = ThreatHashStore.loadBundled(),
) {

    /** How many leading bytes we pull for magic/PE/indicator work when we don't need the whole file. */
    private val headSampleSize = 1 * 1024 * 1024      // 1 MB prefix
    /** Cap on how much of a PE we load for parsing (imports live in the header region; enough for that). */
    private val peSampleSize = 8 * 1024 * 1024        // 8 MB
    /** Below this size a "plain" binary/script is small enough to indicator-scan whole. */
    private val smallFileScanCap = 4 * 1024 * 1024

    /**
     * Scan [file], returning a full [ScanReport]. Runs the CPU/IO work off the caller's thread.
     *
     * @param declaredName the name to reason about for extension/type (defaults to the file's own name;
     *        callers that downloaded to a temp path should pass the ORIGINAL name here).
     * @param expectedSha256 OPTIONAL trusted source checksum. If provided and it matches the computed
     *        digest, the verdict floor becomes [SafetyVerdict.VERIFIED]. (Left unused by default; this
     *        is the wired path for the future source-checksum feature.)
     */
    suspend fun scan(
        file: File,
        declaredName: String = file.name,
        expectedSha256: String? = null,
    ): ScanReport = withContext(Dispatchers.Default) {
        val checks = ArrayList<SafetyCheck>()
        // Optimistic floor: escalation is monotone via [maxVerdict], so we must start at the LOWEST
        // reachable tier and let checks ratchet UP. LOCAL_CLEAR is the floor; the truly-unrecognised
        // case is escalated to UNKNOWN explicitly in the fall-through branch below. (Starting at UNKNOWN
        // would wrongly out-rank LIMITED, which sits below UNKNOWN in severity.)
        var verdict = SafetyVerdict.LOCAL_CLEAR
        var score = 0

        // --- 1. SHA-256 (streamed) --------------------------------------------------------------
        val sha = runCheck { sha256(file) }
        // --- magic + declared type --------------------------------------------------------------
        val magic = runCheck { MagicBytes.sniff(file) }
        val declared = runCheck { MagicBytes.declaredTypeFromName(declaredName) }

        // --- 2. known-bad hash ------------------------------------------------------------------
        when (safe { hashStore.checkHash(sha) } ?: ThreatHashStore.Match.NOT_APPLICABLE) {
            ThreatHashStore.Match.HIT -> {
                checks.add(SafetyCheck("HASH", CheckStatus.FLAG, "matches a known-bad SHA-256 in the bundled threat list"))
                verdict = maxVerdict(verdict, SafetyVerdict.RISKY)
                score = maxOf(score, 100)
            }
            ThreatHashStore.Match.CLEAN ->
                checks.add(SafetyCheck("HASH", CheckStatus.PASSED, "not present in the bundled threat list"))
            ThreatHashStore.Match.NOT_APPLICABLE ->
                checks.add(SafetyCheck("HASH", CheckStatus.NOT_APPLICABLE, "no bundled threat list available"))
        }

        // --- 3. type mismatch -------------------------------------------------------------------
        val mismatch = safe { MagicBytes.isMismatch(declared, magic) } ?: false
        if (mismatch) {
            checks.add(SafetyCheck("MAGIC", CheckStatus.WARN,
                "declared type '${declared}' does not match detected type '${magic}'"))
            verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
            score = maxOf(score, 25)
        } else {
            checks.add(SafetyCheck("MAGIC", CheckStatus.PASSED,
                "declared '${declared ?: "?"}' consistent with detected '${magic ?: "unknown"}'"))
        }

        // --- 4. type-specific inspection --------------------------------------------------------
        when (magic) {
            MagicBytes.T_PE -> {
                val pe = safe { PeInspector.inspect(readPrefix(file, peSampleSize)) }
                    ?: PeInspector.PeFindings.NOT_PE
                if (pe.isPe) {
                    if (pe.hasWritableExecutable) {
                        checks.add(SafetyCheck("PE_SECTIONS", CheckStatus.FLAG,
                            "writable+executable section present (self-modifying / unpacking indicator)"))
                        verdict = maxVerdict(verdict, SafetyVerdict.RISKY)
                        score = maxOf(score, 100)
                    }
                    if (pe.suspiciousImports.isNotEmpty()) {
                        checks.add(SafetyCheck("PE_IMPORTS", CheckStatus.FLAG,
                            "suspicious imports: ${pe.suspiciousImports.joinToString(", ")}"))
                        verdict = maxVerdict(verdict, SafetyVerdict.RISKY)
                        score = maxOf(score, 100)
                    }
                    if (pe.hasPackedSection && !pe.hasWritableExecutable) {
                        checks.add(SafetyCheck("PE_PACKED", CheckStatus.WARN,
                            "high-entropy executable section (possibly packed)"))
                        verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
                        score = maxOf(score, 60)
                    }
                    // Also cluster-scan the PE strings for LOLBins/injection API name pairs.
                    val strings = safe {
                        IndicatorScan.scan(readPrefix(file, peSampleSize), treatAsPe = true)
                    }
                    if (strings != null && strings.highConfidence) {
                        checks.add(SafetyCheck("PE_STRINGS", CheckStatus.FLAG, strings.detail))
                        verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
                        score = maxOf(score, 65)
                    }
                    if (!pe.hasRedFlag && !pe.hasPackedSection && (strings == null || !strings.highConfidence)) {
                        checks.add(SafetyCheck("PE", CheckStatus.PASSED, "PE parsed; no red-flag sections or imports"))
                        verdict = maxVerdict(verdict, SafetyVerdict.LOCAL_CLEAR)
                    }
                    if (!pe.inspectionComplete) {
                        checks.add(SafetyCheck("PE", CheckStatus.NOT_APPLICABLE,
                            "PE header truncated/malformed — inspection incomplete"))
                        verdict = maxVerdict(verdict, SafetyVerdict.LIMITED)
                    }
                } else {
                    checks.add(SafetyCheck("PE", CheckStatus.NOT_APPLICABLE, "MZ present but not a valid PE"))
                }
            }

            MagicBytes.T_ZIP -> {
                val zip = safe { ArchiveInspector.inspectZip(file) }
                    ?: ArchiveInspector.Result.limited("archive inspection failed")
                when {
                    zip.pathTraversal -> {
                        checks.add(SafetyCheck("ARCHIVE", CheckStatus.FLAG,
                            "path-traversal entry (zip-slip): ${zip.reasons.joinToString("; ")}"))
                        verdict = maxVerdict(verdict, SafetyVerdict.RISKY)
                        score = maxOf(score, 100)
                    }
                    zip.zipBomb -> {
                        checks.add(SafetyCheck("ARCHIVE", CheckStatus.FLAG,
                            "zip-bomb indicators: ${zip.reasons.joinToString("; ")}"))
                        verdict = maxVerdict(verdict, SafetyVerdict.RISKY)
                        score = maxOf(score, 100)
                    }
                    zip.contentThreat -> {
                        checks.add(SafetyCheck("ARCHIVE", CheckStatus.FLAG,
                            "malicious content cluster in an entry: ${zip.reasons.joinToString("; ")}"))
                        verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
                        score = maxOf(score, 65)
                    }
                    !zip.inspectionComplete -> {
                        checks.add(SafetyCheck("ARCHIVE", CheckStatus.NOT_APPLICABLE,
                            if (zip.encrypted) "encrypted archive — contents cannot be scanned"
                            else "archive could not be fully inspected"))
                        verdict = maxVerdict(verdict, SafetyVerdict.LIMITED)
                    }
                    else -> {
                        checks.add(SafetyCheck("ARCHIVE", CheckStatus.PASSED,
                            "${zip.entryCount} entries inspected; no structural or content threats"))
                        verdict = maxVerdict(verdict, SafetyVerdict.LOCAL_CLEAR)
                    }
                }
            }

            MagicBytes.T_RAR, MagicBytes.T_7Z -> {
                checks.add(SafetyCheck("ARCHIVE", CheckStatus.NOT_APPLICABLE,
                    "$magic archive — deep inspection out of scope for this engine (contents not scanned)"))
                verdict = maxVerdict(verdict, SafetyVerdict.LIMITED)
            }

            else -> {
                // Standalone script-ish or small binary: indicator-scan a prefix. Big media/ROM/ISO
                // files are left as-is (indicator scanning a multi-GB ISO is pointless + slow).
                val size = safe { file.length() } ?: 0L
                if (size in 1..smallFileScanCap.toLong()) {
                    val findings = safe { IndicatorScan.scan(readPrefix(file, headSampleSize), treatAsPe = false) }
                    if (findings != null && findings.highConfidence) {
                        checks.add(SafetyCheck("CONTENT", CheckStatus.FLAG, findings.detail))
                        verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
                        score = maxOf(score, 55)
                    } else {
                        checks.add(SafetyCheck("CONTENT", CheckStatus.PASSED,
                            "no scripting/behaviour clusters in the sampled prefix"))
                        verdict = maxVerdict(verdict, SafetyVerdict.LOCAL_CLEAR)
                    }
                } else {
                    checks.add(SafetyCheck("CONTENT", CheckStatus.NOT_APPLICABLE,
                        if (magic == null) "unrecognised type; large or empty — content not sampled"
                        else "type '$magic' not content-scanned"))
                    // Recognised-but-clean container types (iso/pdf/gzip/nes/html) stay at the
                    // LOCAL_CLEAR floor; a truly-unrecognised, un-sampled blob escalates to UNKNOWN
                    // (nothing could actually vouch for it).
                    if (magic == null) verdict = maxVerdict(verdict, SafetyVerdict.UNKNOWN)
                }
            }
        }

        // --- 5. source checksum (reserved VERIFIED path) ----------------------------------------
        if (!expectedSha256.isNullOrBlank() && sha != null) {
            if (sha.equals(expectedSha256, ignoreCase = true)) {
                checks.add(SafetyCheck("CHECKSUM", CheckStatus.PASSED, "matches the expected source checksum"))
                // VERIFIED only makes sense if nothing worse was found.
                if (verdict.severity <= SafetyVerdict.LOCAL_CLEAR.severity) verdict = SafetyVerdict.VERIFIED
            } else {
                checks.add(SafetyCheck("CHECKSUM", CheckStatus.WARN, "does NOT match the expected source checksum"))
                verdict = maxVerdict(verdict, SafetyVerdict.SUSPICIOUS)
                score = maxOf(score, 30)
            }
        }

        // --- assemble ---------------------------------------------------------------------------
        // verdict already reflects monotone escalation from the LOCAL_CLEAR floor; UNKNOWN only appears
        // when the fall-through branch explicitly set it for an unrecognised, un-sampled blob.
        ScanReport(
            verdict = verdict,
            score = ThreatScore(score.coerceIn(0, 100), bandOf(score)),
            checks = checks,
            sha256 = sha,
            fileName = declaredName,
            magicType = magic,
            declaredType = declared,
        )
    }

    // ---- band ---------------------------------------------------------------------------------

    private fun bandOf(score: Int): ScoreBand = when {
        score >= 70 -> ScoreBand.RISKY
        score >= 40 -> ScoreBand.REVIEW
        else -> ScoreBand.CLEAR
    }

    // ---- io -----------------------------------------------------------------------------------

    /** Streamed SHA-256 (lowercase hex). Never loads the whole file into memory. */
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Read up to [max] leading bytes of [file] into a buffer (for magic/PE/indicator work). */
    private fun readPrefix(file: File, max: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(max.toLong(), raf.length()).toInt()
            val buf = ByteArray(n)
            raf.seek(0)
            raf.readFully(buf)
            return buf
        }
    }

    /** Run a check that returns a value; on ANY throwable return null (recorded as a limitation). */
    private inline fun <T> runCheck(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    /** Alias of [runCheck] for inline guards. */
    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
}
