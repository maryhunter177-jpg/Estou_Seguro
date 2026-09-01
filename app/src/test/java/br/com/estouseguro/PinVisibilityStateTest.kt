package br.com.estouseguro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PinVisibilityStateTest {
    @Test
    fun `pin starts hidden and toggles without inverted state`() {
        val initial = PinVisibilityState.HIDDEN
        assertFalse(initial.isVisible)

        val revealed = initial.toggled()
        assertSame(PinVisibilityState.VISIBLE, revealed)
        assertTrue(revealed.isVisible)

        val hiddenAgain = revealed.toggled()
        assertSame(PinVisibilityState.HIDDEN, hiddenAgain)
        assertFalse(hiddenAgain.isVisible)
    }
}
