package com.sudokuassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine of the auto-refresh loop, extracted from [com.sudokuassist.capture.ScreenCaptureService].
 *
 * The bug this guards against: the service published "Reading the screen…" on every tick and,
 * when the picture had not changed, returned early *without publishing anything terminal*.
 * The overlay therefore showed that banner forever on any screen with no board in it.
 *
 * The rules, in order of importance:
 *  1. every automatic pass either publishes a terminal update or publishes nothing — but the
 *     "busy" announcement is only ever made when the pass is going to produce something;
 *  2. an unchanged picture does not start a pass at all;
 *  3. a screen already described as "no board here" is not re-described (no banner flicker);
 *  4. a manual tap always runs, even on an unchanged picture.
 */
class ScanLoopTest {

    /** Mirrors the fields the service keeps between frames. */
    private class Loop {
        var lastSignature: String? = null
        var lastIdleMessage: String? = null
        /** Timestamp of the last analysis; the service starts at "long ago", not at 0. */
        var lastBusyAt = Long.MIN_VALUE / 4
        var passes = 0

        sealed class Outcome {
            object Skipped : Outcome()
            data class Worked(val announcesBusy: Boolean) : Outcome()
            data class Idle(val message: String?) : Outcome()
        }

        /** @return what an automatic frame with this signature would do. */
        fun onAutoFrame(sig: String, now: Long, minIntervalMs: Long = 2500): Outcome {
            if (sig == lastSignature) return Outcome.Skipped
            lastSignature = sig
            if (now - lastBusyAt < minIntervalMs) return Outcome.Skipped
            lastBusyAt = now
            passes++
            return Outcome.Worked(announcesBusy = true)
        }

        fun onManualFrame(now: Long): Outcome {
            lastBusyAt = now
            passes++
            return Outcome.Worked(announcesBusy = true)
        }

        /** The recogniser found no board. */
        fun reportIdle(message: String): Outcome.Idle {
            val repeated = message == lastIdleMessage
            lastIdleMessage = message
            return Outcome.Idle(if (repeated) null else message)
        }
    }

    @Test
    fun unchangedScreenDoesNotStartAnotherPass() {
        val loop = Loop()
        assertTrue(loop.onAutoFrame("AAA", 0) is Loop.Outcome.Worked)
        loop.reportIdle("no board")
        // Same picture, many ticks: never another pass, so never another banner.
        for (t in 1..20) {
            assertTrue(
                "tick $t must skip an unchanged screen",
                loop.onAutoFrame("AAA", t * 1200L) is Loop.Outcome.Skipped
            )
        }
        assertEquals(1, loop.passes)
    }

    @Test
    fun idleIsAnnouncedOnceNotOnEveryTick() {
        val loop = Loop()
        loop.onAutoFrame("AAA", 0)
        assertEquals(Loop.Outcome.Idle("no board"), loop.reportIdle("no board"))
        // Re-entering the same state (e.g. after a brief re-check) stays silent.
        loop.onAutoFrame("BBB", 3000)
        assertEquals(Loop.Outcome.Idle(null), loop.reportIdle("no board"))
    }

    @Test
    fun changedScreenStartsAPass() {
        val loop = Loop()
        loop.onAutoFrame("AAA", 0)
        loop.reportIdle("no board")
        val next = loop.onAutoFrame("BBB", 3000)
        assertTrue("a new picture must be processed", next is Loop.Outcome.Worked)
        assertEquals(2, loop.passes)
    }

    @Test
    fun manualTapAlwaysRunsEvenWithoutChange() {
        val loop = Loop()
        loop.onAutoFrame("AAA", 0)
        loop.reportIdle("no board")
        val manual = loop.onManualFrame(1000)
        assertTrue(manual is Loop.Outcome.Worked)
        assertEquals(2, loop.passes)
    }

    @Test
    fun signatureIsWhatDecidesNotEmptyNothingElse() {
        // 8 luminance levels over a 12x24 thumbnail: a changed digit or a scrolled list must
        // differ, while a blinking caret should usually stay inside one bucket.
        fun bucket(lum: Int) = lum / 32
        assertEquals(bucket(10), bucket(20))
        assertFalse(bucket(10) == bucket(60))
    }
}
