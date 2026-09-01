package br.com.estouseguro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrazilianCpfTest {
    @Test fun `aceita CPF com digitos verificadores validos`() {
        assertTrue(BrazilianCpf.isValid("529.982.247-25"))
    }

    @Test fun `rejeita repeticoes e digito incorreto`() {
        assertFalse(BrazilianCpf.isValid("111.111.111-11"))
        assertFalse(BrazilianCpf.isValid("529.982.247-24"))
    }

    @Test fun `mascara CPF sem expor o numero completo`() {
        assertEquals("***.***.***-25", BrazilianCpf.masked("52998224725"))
    }
}
