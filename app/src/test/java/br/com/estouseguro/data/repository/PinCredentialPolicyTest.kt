package br.com.estouseguro.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCredentialPolicyTest {
    @Test
    fun acceptsFourToEightAsciiDigits() {
        assertTrue(PinCredentialPolicy.isValid("1234".toCharArray()))
        assertTrue(PinCredentialPolicy.isValid("01234567".toCharArray()))
    }

    @Test
    fun rejectsLengthOutsideContract() {
        assertFalse(PinCredentialPolicy.isValid("123".toCharArray()))
        assertFalse(PinCredentialPolicy.isValid("123456789".toCharArray()))
    }

    @Test
    fun rejectsNonAsciiDigitsAndNonNumericCharacters() {
        assertFalse(PinCredentialPolicy.isValid("12 4".toCharArray()))
        assertFalse(PinCredentialPolicy.isValid("12a4".toCharArray()))
        assertFalse(PinCredentialPolicy.isValid("١٢٣٤".toCharArray()))
    }
}
