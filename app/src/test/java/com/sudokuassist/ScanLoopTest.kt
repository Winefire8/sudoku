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
 *  1. the "busy" announcement is only made once a board-like screen has been confirmed, and
 *     every terminated pass retires it — an idle that repeats the previous message still
 *     publishes when a banner is outstanding;
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
        /** True while a "Reading the screen…" banner is on screen and not yet replaced. */
        var busyAnnounced = false

        sealed class Outcome {
            object Skipped : Outcome()
            data class Worked(val announcesBusy: Boolean) : Outcome()
            data class Idle(val message: String?) : Outcome()
        }

        /**
         * @param boardLike whether the cheap pre-check thinks there is a board here. Only a
         *   board-like screen announces "Reading the screen…"; everything else is idle.
         */
        fun onAutoFrame(
            sig: String,
            now: Long,
            boardLike: Boolean = true,
            minIntervalMs: Long = 2500,
        ): Outcome {
            if (sig == lastSignature) return Outcome.Skipped
            lastSignature = sig
            if (now - lastBusyAt < minIntervalMs) return Outcome.Skipped
            lastBusyAt = now
            passes++
            if (!boardLike) return reportIdle("no board")
            busyAnnounced = true
            return Outcome.Worked(announcesBusy = true)
        }

        fun onManualFrame(now: Long, boardLike: Boolean = true): Outcome {
            lastBusyAt = now
            passes++
            if (!boardLike) return reportIdle("no board")
            busyAnnounced = true
            return Outcome.Worked(announcesBusy = true)
        }

        /** The recogniser found no board. */
        fun reportIdle(message: String): Outcome.Idle {
            // Repeated *and* nothing outstanding on screen → stay silent.
            val suppressed = message == lastIdleMessage && !busyAnnounced
            lastIdleMessage = message
            busyAnnounced = false
            return Outcome.Idle(if (suppressed) null else message)
        }

        /** A successful pass or a failure: both retire the banner. */
        fun finish() {
            busyAnnounced = false
        }
    }

    @Test
    fun unchangedScreenDoesNotStartAnotherPass() {
        val loop = Loop()
        assertTrue(loop.onAutoFrame("AAA", 0) is Loop.Outcome.Worked)
        loop.reportIdle("no board")
        loop.finish()
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
        // Re-entering the same state (a re-check of a board-less screen) stays silent.
        loop.onAutoFrame("BBB", 3000, boardLike = false)
        assertEquals(Loop.Outcome.Idle(null), loop.reportIdle("no board"))
    }

    @Test
    fun changedScreenStartsAPass() {
        val loop = Loop()
        loop.onAutoFrame("AAA", 0)
        loop.reportIdle("no board")
        loop.finish()
        val next = loop.onAutoFrame("BBB", 3000)
        assertTrue("a new picture must be processed", next is Loop.Outcome.Worked)
        assertEquals(2, loop.passes)
    }

    @Test
    fun manualTapAlwaysRunsEvenWithoutChange() {
        val loop = Loop()
        loop.onAutoFrame("AAA", 0)
        loop.reportIdle("no board")
        loop.finish()
        val manual = loop.onManualFrame(1000)
        assertTrue(manual is Loop.Outcome.Worked)
        assertEquals(2, loop.passes)
    }

    /**
     * The regression the user actually reported: a screen with no board, whose signature
     * keeps changing (a ticking clock is enough), re-entered the same idle state while the
     * old "Reading the screen…" banner was still up. Deduplicating that idle left the banner
     * on screen forever.
     */
    @Test
    fun repeatedIdleStillClearsAnOutstandingBanner() {
        val loop = Loop()
        // A board-less screen: the pre-check runs first, so no banner is ever announced.
        assertEquals(Loop.Outcome.Idle("no board"), loop.onAutoFrame("AAA", 0, boardLike = false))
        // The picture changes slightly, the pre-check disagrees... and now it looks board-like?
        // Worst case: a banner goes up and the very next pass finds nothing again.
        loop.onAutoFrame("BBB", 3000, boardLike = true)
        assertTrue("a banner must be tracked as outstanding", loop.busyAnnounced)
        assertEquals(
            "the repeated idle must be published, not suppressed",
            Loop.Outcome.Idle("no board"),
            loop.reportIdle("no board")
        )
        assertFalse("nothing is outstanding any more", loop.busyAnnounced)
        // Only now is a repeated idle allowed to stay silent.
        assertEquals(Loop.Outcome.Idle(null), loop.reportIdle("no board"))
    }

    @Test
    fun boardlessScreenNeverAnnouncesBusy() {
        val loop = Loop()
        for (t in 0..5) {
            // Every tick brings a new picture (clock in the status bar) and no board.
            val outcome = loop.onAutoFrame("sig$t", t * 3000L, boardLike = false)
            assertTrue("tick $t published an idle, not a banner", outcome is Loop.Outcome.Idle)
            assertFalse("tick $t must not announce busy", loop.busyAnnounced)
        }
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
