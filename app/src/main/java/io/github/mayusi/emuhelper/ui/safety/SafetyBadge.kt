package io.github.mayusi.emuhelper.ui.safety

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mayusi.emuhelper.data.safety.CheckStatus
import io.github.mayusi.emuhelper.data.safety.SafetyCheck
import io.github.mayusi.emuhelper.data.safety.SafetyVerdict
import io.github.mayusi.emuhelper.data.safety.ScanReport

/**
 * SAFETY REVIEW UI (WARN + QUARANTINE model — never auto-delete, never hard-block).
 *
 * The scanner ([io.github.mayusi.emuhelper.data.safety.SecurityScanner]) is a BEST-EFFORT, purely
 * local, offline bonus check — it is deliberately framed to the user as advisory, never a guarantee.
 * These composables surface its verdict as a small colored chip on each finished download row, and a
 * details dialog with the full per-check breakdown + a "Move to Quarantine" action for flagged files.
 */

/** Three-tier colour grouping for the badge, independent of the 6 [SafetyVerdict] values. */
private enum class SafetyTier { CLEAR, REVIEW, RISKY }

private fun SafetyVerdict.tier(): SafetyTier = when (this) {
    SafetyVerdict.VERIFIED, SafetyVerdict.LOCAL_CLEAR -> SafetyTier.CLEAR
    SafetyVerdict.LIMITED, SafetyVerdict.UNKNOWN, SafetyVerdict.SUSPICIOUS -> SafetyTier.REVIEW
    SafetyVerdict.RISKY -> SafetyTier.RISKY
}

private fun SafetyVerdict.label(): String = when (this) {
    SafetyVerdict.VERIFIED -> "Verified"
    SafetyVerdict.LOCAL_CLEAR -> "Looks clean"
    SafetyVerdict.LIMITED -> "Not fully scanned"
    SafetyVerdict.UNKNOWN -> "Unknown"
    SafetyVerdict.SUSPICIOUS -> "Suspicious"
    SafetyVerdict.RISKY -> "Risky"
}

private fun SafetyVerdict.icon(): ImageVector = when (this) {
    SafetyVerdict.VERIFIED, SafetyVerdict.LOCAL_CLEAR -> Icons.Default.CheckCircle
    SafetyVerdict.LIMITED, SafetyVerdict.UNKNOWN -> Icons.Default.HelpOutline
    SafetyVerdict.SUSPICIOUS -> Icons.Default.GppMaybe
    SafetyVerdict.RISKY -> Icons.Default.Warning
}

@Composable
private fun SafetyTier.color(): Color = when (this) {
    SafetyTier.CLEAR -> MaterialTheme.colorScheme.tertiary
    SafetyTier.REVIEW -> Color(0xFFB8860B) // amber — distinct from the app's primary/tertiary/error roles
    SafetyTier.RISKY -> MaterialTheme.colorScheme.error
}

/**
 * Small colored chip a finished download row can show. Tapping it invokes [onClick] (the caller
 * opens [SafetyDetailsDialog]). Nothing is rendered for a null [report] — a task not yet scanned
 * (or whose scan errored, per the "never fail the download" contract) simply shows no badge.
 */
@Composable
fun SafetyBadge(report: ScanReport, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tier = report.verdict.tier()
    val color = tier.color()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(report.verdict.icon(), contentDescription = null, tint = color, modifier = Modifier.width(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                report.verdict.label(),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Full-detail dialog: verdict + 0-100 score + every [SafetyCheck] that ran (code/status/detail) +
 * the "best-effort, not a guarantee" disclaimer +, for a flagged (amber/red) result, a "Move to
 * Quarantine" button. Quarantine NEVER deletes — it only relocates the file into a Quarantine/
 * subfolder for the user to review later; [onQuarantine] is a no-op once [alreadyQuarantined].
 */
@Composable
fun SafetyDetailsDialog(
    report: ScanReport,
    alreadyQuarantined: Boolean,
    onQuarantine: () -> Unit,
    onDismiss: () -> Unit
) {
    val tier = report.verdict.tier()
    val color = tier.color()
    val isFlagged = tier != SafetyTier.CLEAR

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = color) },
        title = {
            Column {
                Text(report.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${report.verdict.label()} · score ${report.score.value}/100",
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "This is the best on-device check we could build — it's not a guarantee the " +
                        "file is safe (no offline scanner catches everything), but it's better than " +
                        "nothing. It never blocks a download automatically; review the details below " +
                        "and, if anything looks wrong, move the file to Quarantine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Checks performed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                report.checks.forEach { check -> SafetyCheckRow(check) }

                if (alreadyQuarantined) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This file has been moved to the Quarantine folder.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isFlagged && !alreadyQuarantined) {
                TextButton(
                    onClick = onQuarantine,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Move to Quarantine") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (isFlagged && !alreadyQuarantined) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun SafetyCheckRow(check: SafetyCheck) {
    val (icon, tint) = when (check.status) {
        CheckStatus.PASSED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
        CheckStatus.WARN -> Icons.Default.GppMaybe to Color(0xFFB8860B)
        CheckStatus.FLAG -> Icons.Default.Warning to MaterialTheme.colorScheme.error
        CheckStatus.NOT_APPLICABLE -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                check.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
