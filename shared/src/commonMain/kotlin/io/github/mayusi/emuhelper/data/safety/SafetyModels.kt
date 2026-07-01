package io.github.mayusi.emuhelper.data.safety

import kotlinx.serialization.Serializable

/**
 * BUILT-IN SECURITY SCANNER — result model (pure Kotlin, offline, cross-platform / JVM).
 *
 * EmuHelper downloads ROMs / ISOs / .pkg / ZIP / RAR from the Internet Archive. This engine runs a
 * BEST-EFFORT, purely-local scan on a just-downloaded file and returns a [ScanReport]. It is framed to
 * users as a BONUS, NOT a guarantee — hence there is deliberately no hard "BLOCK" verdict: the top
 * tier is [SafetyVerdict.RISKY] ("we strongly warn"), never a claim of certainty.
 *
 * These types are plain, `@Serializable`-friendly data so the Android (:app) and Desktop (:desktopApp)
 * UIs can hold a [ScanReport] directly in Compose state and persist/restore it.
 */

/**
 * Overall verdict, ordered least→most concerning. [severity] gives a monotone rank so the orchestrator
 * can escalate via [maxVerdict] without ever going backwards.
 *
 *  - [VERIFIED]    — RESERVED: a trusted source checksum matched. Not produced by the local engine yet
 *                    (wired only if the caller passes an expected checksum). Kept top-of-clean tier.
 *  - [LOCAL_CLEAR] — every applicable local check passed; nothing suspicious found.
 *  - [LIMITED]     — the scan could not fully inspect the file (RAR/7z/encrypted archive, unreadable),
 *                    so "clean" can't be claimed. Not a threat signal — an honesty signal.
 *  - [UNKNOWN]     — the file type is unrecognised and no technique applied. Neutral.
 *  - [SUSPICIOUS]  — a real behavioural cluster or a type mismatch was found; review recommended.
 *  - [RISKY]       — a strong/near-certain bad signal (known-bad hash, W+X section, injection import
 *                    cluster). The top tier: we WARN hard. We do NOT block (best-effort framing).
 */
@Serializable
enum class SafetyVerdict(val severity: Int) {
    VERIFIED(0),
    LOCAL_CLEAR(1),
    LIMITED(2),
    UNKNOWN(3),
    SUSPICIOUS(4),
    RISKY(5);
}

/**
 * Monotone escalation: return whichever verdict is more concerning (higher [SafetyVerdict.severity]).
 * The orchestrator folds every check's implied verdict through this so the final verdict can only ever
 * ratchet UP, never regress.
 */
fun maxVerdict(a: SafetyVerdict, b: SafetyVerdict): SafetyVerdict =
    if (b.severity > a.severity) b else a

/** Outcome of a single technique ([SafetyCheck]). */
@Serializable
enum class CheckStatus {
    /** Ran and found nothing concerning. */
    PASSED,

    /** Ran and found a low-confidence / advisory signal (e.g. extension-vs-magic mismatch). */
    WARN,

    /** Ran and found a real threat signal (known-bad hash, W+X section, behavioural cluster). */
    FLAG,

    /** Did not apply to this file, or could not run (missing data, caught error). Never fatal. */
    NOT_APPLICABLE,
}

/** One technique's result. [code] is a stable short id (e.g. "HASH", "MAGIC", "PE_SECTIONS"). */
@Serializable
data class SafetyCheck(
    val code: String,
    val status: CheckStatus,
    val detail: String,
)

/** Coarse score band for UI colouring. CLEAR < 40, REVIEW 40–69, RISKY ≥ 70 (see [SecurityScanner]). */
@Serializable
enum class ScoreBand { CLEAR, REVIEW, RISKY }

/** Numeric threat score 0–100 plus its [band]. */
@Serializable
data class ThreatScore(
    val value: Int,
    val band: ScoreBand,
)

/**
 * The full scan result other code (UIs) consume.
 *
 * @param verdict      overall best-effort verdict (see [SafetyVerdict]).
 * @param score        0–100 threat score + band.
 * @param checks       one [SafetyCheck] per technique that ran (including NOT_APPLICABLE ones, so the
 *                     UI can show exactly what was and wasn't inspected — important for the "best
 *                     effort, not a guarantee" framing).
 * @param sha256       lowercase hex SHA-256 of the file, or null if it couldn't be computed.
 * @param fileName     the declared name used for extension/type reasoning.
 * @param magicType    normalised true type from magic bytes (e.g. "rar","zip","pe"), or null/unknown.
 * @param declaredType normalised type implied by the file name's extension, or null.
 */
@Serializable
data class ScanReport(
    val verdict: SafetyVerdict,
    val score: ThreatScore,
    val checks: List<SafetyCheck>,
    val sha256: String?,
    val fileName: String,
    val magicType: String?,
    val declaredType: String?,
)
