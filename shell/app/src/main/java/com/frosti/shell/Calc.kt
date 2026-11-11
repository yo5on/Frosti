package com.frosti.shell

import java.math.BigDecimal
import java.math.MathContext

/** The calculator's maths: + − × ÷, parentheses, unary minus. null = unfinished or invalid (or ÷ 0). */
object Calc {
    fun eval(expr: String): Double? = try {
        val p = Parser(expr.replace('×', '*').replace('÷', '/').replace('−', '-').filterNot { it.isWhitespace() })
        val v = p.sum()
        if (p.i == p.s.length && v.isFinite()) v else null
    } catch (e: Exception) { null }

    /** 12 significant digits, no float noise: 0.1+0.2 → "0.3", 6/2 → "3". */
    fun fmt(v: Double): String = BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()

    private class Parser(val s: String) {
        var i = 0
        private fun at(c: Char) = i < s.length && s[i] == c

        fun sum(): Double {
            var v = prod()
            while (at('+') || at('-')) { val op = s[i++]; val r = prod(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun prod(): Double {
            var v = unary()
            while (at('*') || at('/')) { val op = s[i++]; val r = unary(); v = if (op == '*') v * r else v / r }
            return v
        }
        private fun unary(): Double = if (at('-')) { i++; -unary() } else atom()
        private fun atom(): Double {
            if (at('(')) { i++; val v = sum(); if (at(')')) i++; return v }  // a missing ")" at the end is closed for you
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()  // throws on "" or "1.2.3" → null
        }
    }
}
