package br.com.estouseguro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrazilianPhoneNumberTest {
    @Test fun `normalizes Brazilian mobile to E164`() {
        assertEquals("+5533999667145", BrazilianPhoneNumber.normalizeForSms("(33) 9 9966-7145"))
    }

    @Test fun `rejects mobile with old eight digit subscriber`() {
        assertNull(BrazilianPhoneNumber.normalizeForSms("3399667145"))
    }

    @Test fun `accepts valid Brazilian landline`() {
        assertEquals("+553333456789", BrazilianPhoneNumber.normalizeForSms("(33) 3345-6789"))
    }

    @Test fun `rejects unknown Brazilian area code`() {
        assertNull(BrazilianPhoneNumber.normalizeForSms("(20) 99999-9999"))
    }
}
