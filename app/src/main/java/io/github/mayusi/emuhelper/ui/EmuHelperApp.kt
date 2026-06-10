package io.github.mayusi.emuhelper.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.about.AboutScreen
import io.github.mayusi.emuhelper.ui.browse.ConsoleSelectScreen
import io.github.mayusi.emuhelper.ui.history.HistoryScreen
import io.github.mayusi.emuhelper.ui.browse.GamePickerScreen
import io.github.mayusi.emuhelper.ui.browse.ScanProgressScreen
import io.github.mayusi.emuhelper.ui.download.DownloadPreviewScreen
import io.github.mayusi.emuhelper.ui.download.DownloadScreen
import io.github.mayusi.emuhelper.ui.home.HomeScreen
import io.github.mayusi.emuhelper.ui.lists.ListLibraryScreen
import io.github.mayusi.emuhelper.ui.lists.SaveListScreen
import io.github.mayusi.emuhelper.ui.login.LoginScreen
import io.github.mayusi.emuhelper.ui.onboarding.CoachMarksScreen
import io.github.mayusi.emuhelper.ui.onboarding.CoachViewModel
import io.github.mayusi.emuhelper.ui.onboarding.OnboardingScreen
import io.github.mayusi.emuhelper.ui.onboarding.SkipExplainerScreen
import io.github.mayusi.emuhelper.ui.settings.SettingsScreen
import io.github.mayusi.emuhelper.ui.setup.EmulatorSetupDisclaimerScreen
import io.github.mayusi.emuhelper.ui.setup.EmulatorSetupFirmwareScreen
import io.github.mayusi.emuhelper.ui.setup.EmulatorSetupInstructionsScreen
import io.github.mayusi.emuhelper.ui.setup.EmulatorSetupKeysScreen
import io.github.mayusi.emuhelper.ui.setup.EmulatorSetupPickEmulatorScreen
import io.github.mayusi.emuhelper.ui.theme.EmuHelperTheme
import io.github.mayusi.emuhelper.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

object Routes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"                 // login?next={dest}
    const val SKIP_EXPLAINER = "skip_explainer"
    const val COACH = "coach"
    const val CONSOLE_SELECT = "console_select"
    const val CONSOLE_SELECT_ROUTE = "console_select?instant={instant}"
    fun consoleSelect(instant: Boolean) = "console_select?instant=$instant"
    const val SCAN = "scan"
    const val PICK = "pick"
    const val PICK_ROUTE = "pick?instant={instant}"
    fun pick(instant: Boolean) = "pick?instant=$instant"
    const val LIST_LIBRARY = "list_library"
    const val SAVE_LIST = "save_list"
    const val DOWNLOAD_PREVIEW = "download_preview"
    const val DOWNLOAD = "download"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val HISTORY = "history"

    const val EMULATOR_SETUP_DISCLAIMER    = "emulator_setup_disclaimer"
    const val EMULATOR_SETUP_PICK_EMULATOR = "emulator_setup_pick_emulator"
    const val EMULATOR_SETUP_KEYS          = "emulator_setup_keys/{emulator}"
    const val EMULATOR_SETUP_FIRMWARE      = "emulator_setup_firmware/{emulator}"
    const val EMULATOR_SETUP_INSTRUCTIONS  = "emulator_setup_instructions/{emulator}"

    fun emulatorSetupKeys(emulator: String)         = "emulator_setup_keys/$emulator"
    fun emulatorSetupFirmware(emulator: String)     = "emulator_setup_firmware/$emulator"
    fun emulatorSetupInstructions(emulator: String) = "emulator_setup_instructions/$emulator"

    // login is registered with an optional ?next= argument.
    const val LOGIN_ROUTE = "login?next={next}"
    fun login(next: String) = "login?next=$next"
}

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val settings: SettingsStore
) : ViewModel() {
    // null = still loading, true/false = known. REACTIVE so completing onboarding
    // immediately flips it (the old one-shot read stayed stale -> onboarding looped).
    val hasOnboarded: StateFlow<Boolean?> =
        settings.hasOnboarded.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val seenSetupDisclaimer: StateFlow<Boolean> =
        settings.seenSetupDisclaimer.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun markDisclaimerSeen() {
        viewModelScope.launch { settings.setSeenSetupDisclaimer(true) }
    }
}

@Composable
fun EmuHelperApp(modifier: Modifier = Modifier) {
    val shellVm: AppShellViewModel = hiltViewModel()
    val themeMode by shellVm.themeMode.collectAsState()
    EmuHelperTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val onboarded by shellVm.hasOnboarded.collectAsState()
        val seenDisclaimer by shellVm.seenSetupDisclaimer.collectAsState()

        // First-launch onboarding runs ONCE at startup (folder -> login -> coach),
        // gated on the persisted flag — NOT on button taps. This prevents the loop
        // where a stale flag re-triggered onboarding every time a button was pressed.
        var onboardingLaunched by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(onboarded) {
            if (onboarded == false && !onboardingLaunched) {
                onboardingLaunched = true
                navController.navigate(Routes.ONBOARDING)
            }
        }

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = modifier,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 6 },
                    animationSpec = tween(220)
                ) + fadeIn(animationSpec = tween(220))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(160))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(180))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 6 },
                    animationSpec = tween(200)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {

            composable(Routes.HOME) {
                HomeScreen(
                    onMakeList = { navController.navigate(Routes.consoleSelect(instant = false)) },
                    onInstall = { navController.navigate(Routes.consoleSelect(instant = true)) },
                    onDownload = { navController.navigate(Routes.LIST_LIBRARY) },
                    onSignIn = { navController.navigate(Routes.login(Routes.HOME)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOAD) },
                    onEmulatorSetup = {
                        if (seenDisclaimer) {
                            navController.navigate(Routes.EMULATOR_SETUP_PICK_EMULATOR)
                        } else {
                            navController.navigate(Routes.EMULATOR_SETUP_DISCLAIMER)
                        }
                    },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(onContinue = {
                    // After first-launch folder setup, continue into login flow.
                    navController.navigate(Routes.login(Routes.COACH)) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                })
            }

            // login?next={dest}: after a successful login, go to {dest}. The caller
            // sets {dest} (HOME for a plain sign-in, DOWNLOAD for the download gate).
            composable(
                route = Routes.LOGIN_ROUTE,
                arguments = listOf(navArgument("next") { type = NavType.StringType; defaultValue = Routes.HOME })
            ) { entry ->
                val next = entry.arguments?.getString("next") ?: Routes.HOME
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate(next) {
                            popUpTo(Routes.LOGIN_ROUTE) { inclusive = true }
                        }
                    },
                    onSkip = {
                        if (next == Routes.COACH) {
                            navController.navigate(Routes.SKIP_EXPLAINER)
                        } else {
                            navController.navigate(next) {
                                popUpTo(Routes.LOGIN_ROUTE) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Routes.SKIP_EXPLAINER) {
                SkipExplainerScreen(
                    onSignInInstead = { navController.popBackStack() },
                    onSkipAnyway = { navController.navigate(Routes.COACH) { popUpTo(Routes.SKIP_EXPLAINER) { inclusive = true } } }
                )
            }

            composable(Routes.COACH) {
                val vm: CoachViewModel = hiltViewModel()
                CoachMarksScreen(onDone = { vm.finish { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } } })
            }

            // ---- Make a list / Instant install -------------------------------
            composable(
                route = Routes.CONSOLE_SELECT_ROUTE,
                arguments = listOf(navArgument("instant") { type = NavType.BoolType; defaultValue = false })
            ) { entry ->
                val instant = entry.arguments?.getBoolean("instant") ?: false
                ConsoleSelectScreen(
                    onStartScan = { consoles ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("consoles", ArrayList(consoles))
                            set("instant", instant)
                        }
                        navController.navigate(Routes.SCAN)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SCAN) {
                val prev = navController.previousBackStackEntry?.savedStateHandle
                val consoles: Set<String> = prev?.get<ArrayList<String>>("consoles")?.toSet() ?: emptySet()
                val instant: Boolean = prev?.get<Boolean>("instant") ?: false
                ScanProgressScreen(
                    selectedConsoles = consoles,
                    instantInstall = instant,
                    // Drop SCAN from the back stack on the way to PICK (transient screen).
                    // Carry `instant` to PICK as an explicit route arg so it can't be lost.
                    onScanComplete = {
                        navController.navigate(Routes.pick(instant)) {
                            popUpTo(Routes.SCAN) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.PICK_ROUTE,
                arguments = listOf(navArgument("instant") { type = NavType.BoolType; defaultValue = false })
            ) { entry ->
                val instant = entry.arguments?.getBoolean("instant") ?: false
                GamePickerScreen(
                    instantInstall = instant,
                    onProceed = {
                        if (instant) {
                            // Instant install: straight to downloading (login checked there
                            // if needed).
                            navController.navigate(Routes.DOWNLOAD_PREVIEW)
                        } else {
                            navController.navigate(Routes.SAVE_LIST)
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SAVE_LIST) {
                SaveListScreen(
                    onSaved = { navController.popBackStack(Routes.HOME, inclusive = false) },
                    onBack = { navController.popBackStack() }
                )
            }

            // ---- Download ----------------------------------------------------
            composable(Routes.LIST_LIBRARY) {
                ListLibraryScreen(
                    // ListLibraryScreen calls loadForDownload(list) before this fires,
                    // so downloadQueue is already populated for the preview screen.
                    onOpen = { navController.navigate(Routes.DOWNLOAD_PREVIEW) },
                    onBack = { navController.popBackStack() },
                    onMakeList = {
                        navController.navigate(Routes.CONSOLE_SELECT) {
                            popUpTo(Routes.LIST_LIBRARY) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.DOWNLOAD_PREVIEW) {
                DownloadPreviewScreen(
                    onConfirm = { needsLogin ->
                        if (needsLogin) navController.navigate(Routes.login(Routes.DOWNLOAD))
                        else navController.navigate(Routes.DOWNLOAD)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.DOWNLOAD) {
                DownloadScreen(
                    onDone = { navController.popBackStack(Routes.HOME, inclusive = false) },
                    onBack = { navController.popBackStack() },
                    onHistory = { navController.navigate(Routes.HISTORY) }
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() })
            }

            // ---- Emulator Setup -------------------------------------------------
            composable(Routes.EMULATOR_SETUP_DISCLAIMER) {
                EmulatorSetupDisclaimerScreen(
                    onAgree = {
                        shellVm.markDisclaimerSeen()
                        navController.navigate(Routes.EMULATOR_SETUP_PICK_EMULATOR)
                    },
                    onDisagree = { navController.popBackStack() }
                )
            }

            composable(Routes.EMULATOR_SETUP_PICK_EMULATOR) {
                EmulatorSetupPickEmulatorScreen(
                    onBack = { navController.popBackStack() },
                    onEmulatorSelected = { emulator ->
                        navController.navigate(Routes.emulatorSetupKeys(emulator))
                    }
                )
            }

            composable(
                route = Routes.EMULATOR_SETUP_KEYS,
                arguments = listOf(navArgument("emulator") { type = NavType.StringType })
            ) { entry ->
                val emulator = entry.arguments?.getString("emulator") ?: "Eden"
                EmulatorSetupKeysScreen(
                    emulator = emulator,
                    onBack = { navController.popBackStack() },
                    onNext = { emu -> navController.navigate(Routes.emulatorSetupFirmware(emu)) },
                    onSkipFirmware = { emu -> navController.navigate(Routes.emulatorSetupInstructions(emu)) }
                )
            }

            composable(
                route = Routes.EMULATOR_SETUP_FIRMWARE,
                arguments = listOf(navArgument("emulator") { type = NavType.StringType })
            ) { entry ->
                val emulator = entry.arguments?.getString("emulator") ?: "Eden"
                EmulatorSetupFirmwareScreen(
                    emulator = emulator,
                    onBack = { navController.popBackStack() },
                    onNext = { emu -> navController.navigate(Routes.emulatorSetupInstructions(emu)) },
                    onSkip = { emu -> navController.navigate(Routes.emulatorSetupInstructions(emu)) }
                )
            }

            composable(
                route = Routes.EMULATOR_SETUP_INSTRUCTIONS,
                arguments = listOf(navArgument("emulator") { type = NavType.StringType })
            ) { entry ->
                val emulator = entry.arguments?.getString("emulator") ?: "Eden"
                EmulatorSetupInstructionsScreen(
                    emulator = emulator,
                    onGoHome = { navController.popBackStack(Routes.HOME, inclusive = false) }
                )
            }
        }
    }
}
