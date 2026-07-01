package io.github.mayusi.emuhelper.data.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ENTROPY — known vectors: constant buffer -> 0.0, uniform 256 histogram -> 8.0, empty -> 0.0. */
class EntropyTest {

    @Test fun `all-same-byte buffer has zero entropy`() {
        val buf = ByteArray(4096) { 0x41 }
        assertEquals(0.0, Entropy.shannon(buf), 1e-9)
    }

    @Test fun `uniform distribution over all 256 values approaches 8 bits`() {
        // Each byte value appears exactly once -> perfectly uniform -> H = 8.0.
        val buf = ByteArray(256) { it.toByte() }
        assertEquals(8.0, Entropy.shannon(buf), 1e-9)
    }

    @Test fun `two equally-likely values give 1 bit`() {
        val buf = ByteArray(1000) { if (it % 2 == 0) 0x00 else 0x01 }
        assertEquals(1.0, Entropy.shannon(buf), 1e-9)
    }

    @Test fun `empty or bad-range window is zero, never throws`() {
        assertEquals(0.0, Entropy.shannon(ByteArray(0)))
        assertEquals(0.0, Entropy.shannon(ByteArray(10), from = 5, to = 5))
        assertEquals(0.0, Entropy.shannon(ByteArray(10), from = 100, to = 200))
    }

    @Test fun `pseudo-random buffer has high entropy`() {
        val rnd = java.util.Random(1234L)
        val buf = ByteArray(65536).also { rnd.nextBytes(it) }
        assertTrue(Entropy.shannon(buf) > 7.9, "random data should be near-maximal entropy")
    }
}
