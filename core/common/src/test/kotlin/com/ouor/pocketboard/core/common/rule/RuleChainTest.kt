package com.ouor.pocketboard.core.common.rule

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleChainTest {
    private fun <I, O : Any> rule(id: String, f: (I) -> O?) = object : Rule<I, O> {
        override val id = id
        override fun apply(input: I) = f(input)
    }

    @Test
    fun firstMatch_takesFirstNonNull_elseDefault() {
        val rules = listOf(
            rule<Int, String>("a") { if (it < 0) "neg" else null },
            rule("b") { if (it == 0) "zero" else null },
            rule("c") { if (it <= 0) "never" else null },
        )
        assertEquals(Verdict("zero", "b"), rules.firstMatch(0) { "pos" })
        assertEquals(Verdict("pos", null), rules.firstMatch(5) { "pos" })
    }

    @Test
    fun transform_appliesAllInOrder_andRecordsChangers() {
        val rules = listOf(
            rule<String, String>("trim") { s -> s.trim().takeIf { it != s } },
            rule("upper") { s -> s.uppercase().takeIf { it != s } },
            rule("noop") { null },
        )
        assertEquals(Transformed("AB", listOf("trim", "upper")), rules.transform(" ab "))
        assertEquals(Transformed("AB", emptyList<String>()), rules.transform("AB"))
    }
}
