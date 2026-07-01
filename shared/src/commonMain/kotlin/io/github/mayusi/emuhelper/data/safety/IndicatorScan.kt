package io.github.mayusi.emuhelper.data.safety

/**
 * STRING-INDICATOR CLUSTERING SCANNER — the key design of the engine.
 *
 * The rule that keeps false-positives low: NEVER escalate on a single hit. A lone "https://" or a lone
 * "powershell" means nothing (a legit tool, a readme, a config). We only raise a high-confidence FLAG
 * when INDEPENDENT indicators from DIFFERENT tiers CLUSTER together — the signature of an actual
 * malicious payload, not incidental strings.
 *
 * A buffer is decoded BOTH as ISO-8859-1 (raw single-byte view — catches ASCII/base64 payloads) AND as
 * UTF-16LE (catches the common Windows-script obfuscation where commands are stored UTF-16). Ports
 * PcHelper's tiered indicator sets + its paired-evidence gate.
 *
 * Pure logic over a byte buffer; the caller decides the sample cap (default helper caps at ~2 MB).
 */
object IndicatorScan {

    /** Default cap on how many bytes we decode/scan from a buffer (keeps big files cheap). */
    const val DEFAULT_SAMPLE_CAP = 2 * 1024 * 1024

    /** A clustered finding. [highConfidence] == true means the caller should FLAG + escalate. */
    data class Findings(
        val highConfidence: Boolean,
        val category: String,          // "script" or "pe" (empty if nothing notable)
        val matched: List<String>,     // the distinct indicators that hit
        val detail: String,            // human-readable summary for the SafetyCheck
    ) {
        companion object {
            val NONE = Findings(false, "", emptyList(), "no indicator clusters")
        }
    }

    // ---- SCRIPT indicators (PowerShell / batch / living-off-the-land) --------------------------

    private val SCRIPT_CRITICAL = listOf("encodedcommand", "frombase64string", "invoke-expression", "iex")
    private val SCRIPT_NETWORK = listOf(
        "invoke-webrequest", "invoke-restmethod", "downloadstring", "downloadfile", "curl ", "wget ",
    )
    private val SCRIPT_EXECUTION = listOf(
        "start-process", "bitsadmin", "certutil", "reg add", "schtasks",
    )

    // ---- PE-string indicators (Win32 API names / LOLBins embedded in a binary) ------------------

    private val PE_INJECTION = listOf(
        "virtualalloc", "virtualprotect", "writeprocessmemory", "createremotethread",
    )
    private val PE_NETWORK_API = listOf("urldownloadtofile", "internetopen", "httpsendrequest")
    private val PE_GENERIC_NETWORK = listOf("http://", "https://")
    private val PE_SHELL_LAUNCH = listOf("winexec", "shellexecute")
    private val PE_STRONG_LOLBINS = listOf(
        "powershell", "powershell.exe", "cmd.exe", "regsvr32", "schtasks",
    )

    /**
     * Scan [buf] (up to [cap] bytes) for BOTH script and PE indicator clusters. The stronger of the two
     * findings is returned (a high-confidence hit in either dominates). Never throws.
     *
     * @param treatAsPe when true (the file is/looks like a PE binary), also run the PE-string cluster.
     *                  When false, only the script cluster runs (script/small-binary standalone case).
     */
    fun scan(
        buf: ByteArray,
        cap: Int = DEFAULT_SAMPLE_CAP,
        treatAsPe: Boolean = true,
    ): Findings {
        return try {
            val n = minOf(buf.size, cap)
            // Two decodings: raw single-byte, and UTF-16LE to catch UTF-16-obfuscated payloads.
            val latin = String(buf, 0, n, Charsets.ISO_8859_1).lowercase()
            val utf16 = decodeUtf16Le(buf, n).lowercase()

            val script = scanScript(latin, utf16)
            val pe = if (treatAsPe) scanPe(latin, utf16) else Findings.NONE

            // Prefer a high-confidence finding; otherwise prefer the one with more distinct matches.
            when {
                script.highConfidence && !pe.highConfidence -> script
                pe.highConfidence && !script.highConfidence -> pe
                script.highConfidence && pe.highConfidence ->
                    if (pe.matched.size >= script.matched.size) pe else script
                pe.matched.size > script.matched.size -> pe
                script.matched.isNotEmpty() -> script
                pe.matched.isNotEmpty() -> pe
                else -> Findings.NONE
            }
        } catch (_: Throwable) {
            Findings.NONE
        }
    }

    private fun scanScript(latin: String, utf16: String): Findings {
        val critical = distinctHits(SCRIPT_CRITICAL, latin, utf16)
        val network = distinctHits(SCRIPT_NETWORK, latin, utf16)
        val execution = distinctHits(SCRIPT_EXECUTION, latin, utf16)

        val all = (critical + network + execution)
        val totalDistinct = all.size
        val cCount = critical.size
        val nCount = network.size
        val eCount = execution.size

        // High-confidence ONLY when independent evidence clusters (never a lone hit).
        val high = cCount >= 2 ||
            (cCount >= 1 && (nCount >= 1 || eCount >= 1)) ||
            (nCount >= 1 && eCount >= 1) ||
            totalDistinct >= 4

        return if (all.isEmpty()) {
            Findings.NONE
        } else {
            Findings(
                highConfidence = high,
                category = "script",
                matched = all,
                detail = if (high) {
                    "script behaviour cluster: ${all.joinToString(", ")}"
                } else {
                    // Lone / weak hits: log-only, no escalation.
                    "isolated script indicator(s), below cluster threshold: ${all.joinToString(", ")}"
                },
            )
        }
    }

    private fun scanPe(latin: String, utf16: String): Findings {
        val injection = distinctHits(PE_INJECTION, latin, utf16)
        val networkApi = distinctHits(PE_NETWORK_API, latin, utf16)
        val genericNet = distinctHits(PE_GENERIC_NETWORK, latin, utf16)
        val shellLaunch = distinctHits(PE_SHELL_LAUNCH, latin, utf16)
        val lolbins = distinctHits(PE_STRONG_LOLBINS, latin, utf16)

        val all = (injection + networkApi + genericNet + shellLaunch + lolbins)

        // PAIRED evidence required. A plain "https://" alone NEVER triggers.
        //   - an injection pair (>=2 injection APIs) alongside a LOLBin, OR
        //   - a downloader API (network API OR a shell-launch) alongside a LOLBin.
        val injectionPair = injection.size >= 2
        val downloader = networkApi.isNotEmpty() || shellLaunch.isNotEmpty()
        val high = (injectionPair && lolbins.isNotEmpty()) ||
            (downloader && lolbins.isNotEmpty())

        return if (all.isEmpty()) {
            Findings.NONE
        } else {
            Findings(
                highConfidence = high,
                category = "pe",
                matched = all,
                detail = if (high) {
                    "PE behaviour cluster (paired evidence): ${all.joinToString(", ")}"
                } else {
                    "PE indicator(s) present but unpaired, below threshold: ${all.joinToString(", ")}"
                },
            )
        }
    }

    /** Distinct set of [needles] found in EITHER decoding, preserving needle order, deduped. */
    private fun distinctHits(needles: List<String>, latin: String, utf16: String): List<String> {
        val out = ArrayList<String>()
        for (needle in needles) {
            if (latin.contains(needle) || utf16.contains(needle)) {
                // Normalise "curl "/"wget " display (trim the trailing space) for readability.
                val label = needle.trim()
                if (label !in out) out.add(label)
            }
        }
        return out
    }

    /** Decode [n] bytes of [buf] as UTF-16LE, defensively (odd length / bad bytes never throw). */
    private fun decodeUtf16Le(buf: ByteArray, n: Int): String {
        return try {
            // Drop a trailing odd byte so the surrogate decoder has whole code units.
            val even = n and 1.inv()
            if (even <= 0) "" else String(buf, 0, even, Charsets.UTF_16LE)
        } catch (_: Throwable) {
            ""
        }
    }
}
