package br.com.estouseguro.domain.model

object BrazilianCpf {
    fun digits(value: String): String = value.filter(Char::isDigit)

    fun isValid(value: String): Boolean {
        val cpf = digits(value)
        if (cpf.length != 11 || cpf.all { it == cpf.first() }) return false
        fun checkDigit(length: Int): Int {
            val sum = (0 until length).sumOf { (cpf[it] - '0') * (length + 1 - it) }
            val remainder = (sum * 10) % 11
            return if (remainder == 10) 0 else remainder
        }
        return checkDigit(9) == cpf[9] - '0' && checkDigit(10) == cpf[10] - '0'
    }

    fun format(value: String): String {
        val cpf = digits(value).take(11)
        return buildString {
            cpf.forEachIndexed { index, char ->
                if (index == 3 || index == 6) append('.')
                if (index == 9) append('-')
                append(char)
            }
        }
    }

    fun masked(value: String): String {
        val cpf = digits(value)
        return if (cpf.length == 11) "***.***.***-${cpf.takeLast(2)}" else "••••${cpf.takeLast(4)}"
    }
}
