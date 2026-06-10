package io.github.mayusi.emuhelper.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun EmulatorSetupDisclaimerScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Before you continue",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    buildAnnotatedString {
                        append("This feature helps you move files ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("you already own") }
                        append(" into a Nintendo Switch emulator. It does not supply, download, host, or link to any keys or firmware files.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("You must read and agree to the following before proceeding:")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))

                DisclaimerPoint(
                    number = "1",
                    title = "Your own files only.",
                    body = buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("prod.keys") }
                        append(" and firmware must be dumped exclusively from a Nintendo Switch console that you own. Using files obtained from any other source may be illegal in your jurisdiction.")
                    }
                )
                Spacer(Modifier.height(10.dp))
                DisclaimerPoint(
                    number = "2",
                    title = "No files are provided by this app.",
                    body = buildAnnotatedString {
                        append("EmuHelper does not contain, embed, download, or link to prod.keys, firmware, or any Nintendo intellectual property. This feature only copies files you select from your device's storage.")
                    }
                )
                Spacer(Modifier.height(10.dp))
                DisclaimerPoint(
                    number = "3",
                    title = "Legal responsibility is yours.",
                    body = buildAnnotatedString {
                        append("Laws governing the dumping and use of cryptographic keys and console firmware vary by country. It is your responsibility to ensure your use complies with applicable law. The developer of this app accepts no liability for how you use this feature.")
                    }
                )
                Spacer(Modifier.height(10.dp))
                DisclaimerPoint(
                    number = "4",
                    title = "No emulation endorsement.",
                    body = buildAnnotatedString {
                        append("This app provides a file-management convenience only. The developer makes no representation about the legality of any specific emulation activity in your jurisdiction.")
                    }
                )
                Spacer(Modifier.height(10.dp))
                DisclaimerPoint(
                    number = "5",
                    title = "Proceeding means you agree.",
                    body = buildAnnotatedString {
                        append("Tapping “I understand — continue” confirms that you have read this notice, that the files you will import are legally yours, and that you accept full responsibility for their use.")
                    }
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("I understand — continue")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisagree,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("I don’t agree — go back")
                }
            }
        }
    }
}

@Composable
private fun DisclaimerPoint(
    number: String,
    title: String,
    body: androidx.compose.ui.text.AnnotatedString
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(24.dp)
        )
        Column {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                    append(" ")
                    append(body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
