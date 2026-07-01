package io.github.mayusi.emuhelper.data.safety

import kotlin.math.ln

/**
 * Shannon entropy over a byte buffer — pure math, no I/O.
 *
 * H = -Σ p(x)·log2(p(x)) across the 256-value byte histogram, where p(x) is the frequency of byte
 * value x. Result is in the range [0.0, 8.0]:
 *   - a buffer of a single repeated byte  -> 0.0 (perfectly predictable),
 *   - a uniformly-random buffer           -> ~8.0 (maximally unpredictable).
 *
 * High entropy in an executable section is a classic PACKED / encrypted-payload indicator, which is
 * why [PeInspector] samples section bytes through here.
 */
object Entropy {

    private val LN2 = ln(2.0)

    /**
     * Shannon entropy (bits/byte) over [buf] in the range [[from], [to]).
     * Returns 0.0 for an empty / out-of-range window (never throws on bad bounds — defensive, since
     * this scans untrusted files).
     */
    fun shannon(buf: ByteArray, from: Int = 0, to: Int = buf.size): Double {
        val start = from.coerceIn(0, buf.size)
        val end = to.coerceIn(start, buf.size)
        val n = end - start
        if (n <= 0) return 0.0

        val counts = IntArray(256)
        for (i in start until end) {
            counts[buf[i].toInt() and 0xFF]++
        }

        var h = 0.0
        val total = n.toDouble()
        for (c in counts) {
            if (c == 0) continue
            val p = c / total
            h -= p * (ln(p) / LN2)
        }
        return h
    }
}
