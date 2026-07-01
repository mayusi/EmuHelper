package io.github.mayusi.emuhelper.data.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * INDICATOR CLUSTERING — the core anti-false-positive rule: lone hits never escalate; only real
 * clusters do. Also verifies the UTF-16LE decoding path.
 */
class IndicatorScanTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun `a lone https URL never escalates`() {
        val f = IndicatorScan.scan(bytes("visit https://example.com for the readme"))
        assertFalse(f.highConfidence, "a single generic URL must not be high-confidence")
    }

    @Test fun `a lone powershell mention never escalates`() {
        val f = IndicatorScan.scan(bytes("run this in powershell to install"))
        assertFalse(f.highConfidence)
    }

    @Test fun `a real script cluster escalates (frombase64string + downloadstring)`() {
        val payload = "\$x = [Convert]::FromBase64String(\$b); iex (New-Object Net.WebClient).DownloadString('h')"
        val f = IndicatorScan.scan(bytes(payload))
        assertTrue(f.highConfidence, "critical + network cluster must be high-confidence")
        assertTrue(f.matched.any { it.contains("frombase64string") })
    }

    @Test fun `two critical indicators alone escalate`() {
        val f = IndicatorScan.scan(bytes("encodedcommand ... invoke-expression ..."))
        assertTrue(f.highConfidence, "two critical indicators cluster")
    }

    @Test fun `network + execution cluster escalates`() {
        val f = IndicatorScan.scan(bytes("downloadfile then schtasks /create"))
        assertTrue(f.highConfidence)
    }

    @Test fun `four distinct script indicators escalate even if same tier mix`() {
        val f = IndicatorScan.scan(bytes("iex frombase64string invoke-webrequest start-process"))
        assertTrue(f.highConfidence)
    }

    @Test fun `UTF-16LE-encoded payload is detected`() {
        val payload = "iex (New-Object Net.WebClient).DownloadString('x'); FromBase64String"
        val utf16 = payload.toByteArray(Charsets.UTF_16LE)
        val f = IndicatorScan.scan(utf16)
        assertTrue(f.highConfidence, "UTF-16LE obfuscated payload must still cluster")
    }

    @Test fun `PE injection pair with a LOLBin escalates`() {
        val f = IndicatorScan.scan(
            bytes("VirtualAllocEx WriteProcessMemory ... powershell.exe"), treatAsPe = true,
        )
        assertTrue(f.highConfidence, "injection pair + LOLBin is paired evidence")
    }

    @Test fun `PE downloader API with a LOLBin escalates`() {
        val f = IndicatorScan.scan(bytes("URLDownloadToFile ... cmd.exe"), treatAsPe = true)
        assertTrue(f.highConfidence)
    }

    @Test fun `PE lone https with a LOLBin does NOT escalate (unpaired)`() {
        val f = IndicatorScan.scan(bytes("https://example.com powershell"), treatAsPe = true)
        assertFalse(f.highConfidence, "generic URL + LOLBin without a downloader/injection pair must not fire")
    }

    @Test fun `treatAsPe false ignores PE-only indicators`() {
        val f = IndicatorScan.scan(bytes("VirtualAlloc WriteProcessMemory cmd.exe"), treatAsPe = false)
        assertFalse(f.highConfidence, "PE cluster must not fire when treatAsPe=false")
    }

    @Test fun `clean text yields no findings`() {
        val f = IndicatorScan.scan(bytes("This is a normal readme file describing a game ROM."))
        assertFalse(f.highConfidence)
        assertTrue(f.matched.isEmpty())
    }
}
