package br.com.estouseguro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrazilianDateTest {
    @Test fun `converte ISO para formato brasileiro`() {
        assertEquals("31/08/1996", BrazilianDate.isoToDisplay("1996-08-31"))
    }

    @Test fun `converte formato brasileiro para ISO`() {
        assertEquals("2000-02-29", BrazilianDate.displayToIso("29/02/2000"))
    }

    @Test fun `rejeita data inexistente`() {
        assertThrows(Exception::class.java) { BrazilianDate.displayToIso("31/02/2020") }
    }

    @Test fun `mascara insere barras enquanto digita`() {
        assertEquals("01/09/2026", BrazilianDate.mask("01092026"))
        assertEquals("01/09", BrazilianDate.mask("01/09"))
    }
}
