package io.github.mayusi.emuhelper.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess

/**
 * :desktopApp entry point (Phase 3a of the EmuHelper Windows port).
 *
 *   • Default:      launch the Compose Desktop GUI — a real window with the working core flow
 *                   (browse consoles → scan → pick files → download to a folder), all driven by the
 *                   SHARED engine via [DownloadController].
 *   • `--headless`: run the original Phase-2 headless download proof (kept for CI / regression) and
 *                   exit with 0 (PASS) or 1 (FAIL). Same engine path the GUI uses under the hood.
 */
fun main(args: Array<String>) {
    if (args.contains("--headless")) {
        val ok = runHeadlessProof()
        exitProcess(if (ok) 0 else 1)
    }
    launchGui()
}

/** Launches the Compose Desktop window. Blocks until the window is closed. */
private fun launchGui() = application {
    // One app-lifetime state holder; it owns the IO scope + the shared RemoteSource engine.
    val controller = remember { DownloadController(DownloadController.newScope()) }
    val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "EmuHelper — Desktop (Windows)",
    ) {
        App(controller)
    }
}
