package com.example.zenflow

import org.junit.Assert.assertEquals
import org.junit.Test

class RampTest {

    @Test fun `ramps from one to zero`() {
        val r = Ramp()
        r.begin(0, 1000)
        assertEquals(1f, r.factor(0))
        assertEquals(0.5f, r.factor(500), 0.01f)
        assertEquals(0f, r.factor(1000))
        assertEquals(0f, r.factor(5000))
    }

    @Test fun `begin is one shot`() {
        val r = Ramp()
        r.begin(0, 1000)
        r.begin(500, 1000)
        assertEquals(0f, r.factor(1000), 0.01f)
    }

    @Test fun `restart resets the clock`() {
        val r = Ramp()
        r.begin(0, 1000)
        r.restart(2000, 1000)
        assertEquals(1f, r.factor(2000))
        assertEquals(0f, r.factor(3000))
    }

    @Test fun `pause freezes remaining and resume picks up exactly`() {
        val r = Ramp()
        r.begin(0, 1000)
        r.pause(400)
        assertEquals(600L, r.remainingMs(50_000))
        assertEquals(0.6f, r.factor(400), 0.01f)
        r.resume(2_500)
        assertEquals(0.6f, r.factor(2_500), 0.01f)
        assertEquals(0f, r.factor(3_100), 0.01f)
    }

    @Test fun `cancel returns to full volume`() {
        val r = Ramp()
        r.begin(0, 1000)
        r.cancel()
        assertEquals(1f, r.factor(5000))
        assertEquals(0L, r.remainingMs(5000))
    }

    @Test fun `inactive ramp is always full`() {
        assertEquals(1f, Ramp().factor(12345))
    }
}
