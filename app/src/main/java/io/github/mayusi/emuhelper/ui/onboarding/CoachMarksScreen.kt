package io.github.mayusi.emuhelper.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoachViewModel @Inject constructor(
    private val settings: SettingsStore
) : ViewModel() {
    fun finish(then: () -> Unit) {
        viewModelScope.launch {
            settings.setSeenCoach(true)
            settings.setOnboarded(true) // guarantee onboarding is marked done at the true end
            then()
        }
    }
}

data class CoachMarkStep(
    val icon: ImageVector,
    val title: String,
    val body: String
)

@Composable
fun CoachMarksScreen(
    onDone: () -> Unit,
    viewModel: CoachViewModel = hiltViewModel()
) {
    var step by remember { mutableIntStateOf(0) }

    val steps = listOf(
        CoachMarkStep(Icons.AutoMirrored.Filled.PlaylistAdd, "Two ways to get items", "Make a list to save items for later, or Instant install to download right now."),
        CoachMarkStep(Icons.Default.FlashOn, "Pick your items", "Choose a category, the app scans the source, then you tick the items you want."),
        CoachMarkStep(Icons.Default.Download, "Download", "Files download in the background into per-category folders (e.g. downloads/SNES). You can leave the app; force-closing stops them."),
        CoachMarkStep(Icons.Default.Settings, "Tune speed anytime", "Use ⋮ → Settings to change connections, run a speed test, and see device info.")
    )

    val currentStep = steps[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    currentStep.icon,
                    null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    currentStep.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    currentStep.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) {
                        TextButton(onClick = { step -= 1 }) {
                            Text("Back", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    Text(
                        "${step + 1} / ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            if (step < steps.size - 1) {
                                step += 1
                            } else {
                                viewModel.finish(onDone)
                            }
                        },
                        modifier = Modifier.height(Dimens.ButtonMinHeight),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            if (step < steps.size - 1) "Next" else "Got it",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
