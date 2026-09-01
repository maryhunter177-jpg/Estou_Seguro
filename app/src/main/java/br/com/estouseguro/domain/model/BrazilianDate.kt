package br.com.estouseguro.domain.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object BrazilianDate {
    private val displayFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")
        .withResolverStyle(ResolverStyle.STRICT)

    fun isoToDisplay(iso: String): String = if (iso.isBlank()) "" else
        LocalDate.parse(iso.trim()).format(displayFormatter)

    fun displayToIso(display: String): String = if (display.isBlank()) "" else
        LocalDate.parse(display.trim(), displayFormatter).toString()

    fun mask(raw: String): String {
        val digits = raw.filter(Char::isDigit).take(8)
        return buildString(10) {
            digits.forEachIndexed { index, char ->
                if (index == 2 || index == 4) append('/')
                append(char)
            }
        }
    }
}
