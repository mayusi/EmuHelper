package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OVERNIGHT GOVERNORS (v0.8) — pure DECISION logic.
 *
 * (3a) [decideWifiGovernorAction]: with wifiOnly ON, pause when the active network is metered/lost,
 * resume when unmetered Wi-Fi returns, and NEVER fight a user's manual pause (only ever resume a
 * pause the governor itself caused). With wifiOnly OFF, the governor enforces nothing (but undoes its
 * own pause if one is in effect).
 *
 * (3b) [thermalPermitsToHold]: how many permits the thermal governor holds at each thermal status —
 * 0 below SEVERE (full budget), and (budget - reducedCap) at SEVERE-or-worse so the live cap shrinks
 * toward ~8. It can only ever REDUCE the live cap (hold >= 0), never raise it past the budget.
 */
class GovernorDecisionTest {

    // ---- (3a) Wi-Fi-only governor decisions ---------------------------------------------------

    @Test
    fun `wifiOnly off - governor enforces nothing`() {
        // Metered, unmetered, connected, disconnected — all NONE when wifiOnly is off and nothing is
        // governor-paused.
        for (metered in listOf(true, false)) for (connected in listOf(true, false)) {
            assertEquals(
                WifiGovernorAction.NONE,
                decideWifiGovernorAction(wifiOnly = false, isConnected = connected, isMetered = metered, governorPaused = false),
                "wifiOnly off must not enforce (metered=$metered connected=$connected)"
            )
        }
    }

    @Test
    fun `wifiOnly off but governor had paused - resume to undo our own pause`() {
        // User turned wifiOnly OFF while the governor had it paused -> release that pause.
        assertEquals(
            WifiGovernorAction.RESUME,
            decideWifiGovernorAction(wifiOnly = false, isConnected = true, isMetered = true, governorPaused = true)
        )
    }

    @Test
    fun `wifiOnly on and metered - pause when not already paused`() {
        assertEquals(
            WifiGovernorAction.PAUSE,
            decideWifiGovernorAction(wifiOnly = true, isConnected = true, isMetered = true, governorPaused = false),
            "metered network with wifiOnly on must pause"
        )
    }

    @Test
    fun `wifiOnly on and disconnected - pause`() {
        assertEquals(
            WifiGovernorAction.PAUSE,
            decideWifiGovernorAction(wifiOnly = true, isConnected = false, isMetered = false, governorPaused = false),
            "lost connectivity must pause when wifiOnly is on"
        )
    }

    @Test
    fun `wifiOnly on and unmetered wifi returns - resume only if governor paused`() {
        // We paused it -> resume.
        assertEquals(
            WifiGovernorAction.RESUME,
            decideWifiGovernorAction(wifiOnly = true, isConnected = true, isMetered = false, governorPaused = true)
        )
        // We had NOT paused (already running fine on Wi-Fi) -> nothing to do.
        assertEquals(
            WifiGovernorAction.NONE,
            decideWifiGovernorAction(wifiOnly = true, isConnected = true, isMetered = false, governorPaused = false)
        )
    }

    @Test
    fun `wifiOnly on and already paused on metered - no repeat pause`() {
        // Still metered and we already paused -> NONE (don't spam pause).
        assertEquals(
            WifiGovernorAction.NONE,
            decideWifiGovernorAction(wifiOnly = true, isConnected = true, isMetered = true, governorPaused = true)
        )
    }

    @Test
    fun `the governor never resumes a pause it did not cause`() {
        // On unmetered Wi-Fi but governorPaused=false: the governor has no pause of its own to lift,
        // so it returns NONE — it can never auto-resume a USER pause (the caller tracks userPaused
        // separately; the governor only ever acts on its OWN governorPaused flag).
        assertEquals(
            WifiGovernorAction.NONE,
            decideWifiGovernorAction(wifiOnly = true, isConnected = true, isMetered = false, governorPaused = false)
        )
    }

    // ---- (3b) Thermal backoff decisions -------------------------------------------------------

    @Test
    fun `below SEVERE holds zero permits (full budget)`() {
        // NONE(0), LIGHT(1), MODERATE(2) -> no backoff.
        for (status in 0..2) {
            assertEquals(0, thermalPermitsToHold(status), "status $status below SEVERE must hold 0")
        }
    }

    @Test
    fun `SEVERE and worse shrink the live cap toward the reduced target`() {
        val budget = AdaptiveEngine.MAX_ADAPTIVE_WORKERS          // 24
        val reduced = AdaptiveEngine.THERMAL_REDUCED_CAP          // 8
        val expectedHold = budget - reduced                       // 16
        // SEVERE(3), CRITICAL(4), EMERGENCY(5), SHUTDOWN(6) all hold the same (clamp at the floor).
        for (status in 3..6) {
            assertEquals(expectedHold, thermalPermitsToHold(status), "status $status must hold $expectedHold")
        }
    }

    @Test
    fun `thermal hold stays within budget - only reduces, never over-holds`() {
        val budget = AdaptiveEngine.MAX_ADAPTIVE_WORKERS
        for (status in 0..6) {
            val hold = thermalPermitsToHold(status)
            assert(hold in 0..budget) { "hold $hold for status $status out of range 0..$budget" }
            // The implied live cap (budget - hold) is always at least 1 (download never fully stalls).
            assert((budget - hold) >= 1) { "live cap (budget - hold) must stay >= 1" }
        }
    }

    @Test
    fun `a tiny reduced-cap config never drops the live cap below 1`() {
        // Even with an absurd reducedCap of 0, the live cap floors at 1 (at least one connection).
        val hold = thermalPermitsToHold(THERMAL_STATUS_SEVERE, globalBudget = 24, reducedCap = 0)
        assertEquals(23, hold, "reducedCap clamps to a min live cap of 1 -> hold at most budget-1")
    }
}
