package io.github.kiriashi.biopay.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenStateTest {
    @Test
    fun cancelKeepsAutomaticPromptConsumedUntilScreenEnds() {
        val screen = PaymentScreenState<Any>()
        val keyboard = Any()

        screen.markPrompted(keyboard)
        assertTrue(screen.prompted)
        assertSame(keyboard, screen.keyboard())

        screen.rememberKeyboard(Any())
        assertTrue(screen.prompted)

        screen.clear()
        assertFalse(screen.prompted)
        assertTrue(screen.keyboard() == null)
    }

    @Test
    fun retryAndExitGraceAreScopedToOneScreen() {
        val screen = PaymentScreenState<Any>()
        val keyboard = Any()

        assertTrue(screen.shouldAttempt(keyboard, 1_000L))
        assertFalse(screen.shouldAttempt(keyboard, 1_500L))
        assertTrue(screen.shouldAttempt(keyboard, 2_001L))

        screen.markPrompted(keyboard)
        assertFalse(screen.screenAbsentTooLong(false, false, false, 3_000L))
        assertFalse(screen.screenAbsentTooLong(false, true, false, 5_600L))
        assertFalse(screen.screenAbsentTooLong(false, false, false, 5_700L))
        assertTrue(screen.screenAbsentTooLong(false, false, false, 8_201L))

        screen.clear()
        assertTrue(screen.shouldAttempt(keyboard, 9_000L))
    }
}
