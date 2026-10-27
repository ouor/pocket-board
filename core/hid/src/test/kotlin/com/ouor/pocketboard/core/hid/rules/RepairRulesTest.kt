package com.ouor.pocketboard.core.hid.rules

import com.ouor.pocketboard.core.common.rule.firstMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class RepairRulesTest {
    private val rules = DefaultRules.repair(failureThreshold = 5)
    private fun judge(bondLost: Boolean, failures: Int) = rules.firstMatch(RepairContext(bondLost, failures)) { false }

    @Test fun `bond lost wins immediately`() =
        assertEquals("repair/bond-none", judge(bondLost = true, failures = 0).ruleId)

    @Test fun `failures below threshold are a host that is just off`() =
        assertEquals(false, judge(bondLost = false, failures = 4).value)

    @Test fun `threshold failures suggest re-pairing`() =
        assertEquals("repair/consecutive-failures", judge(bondLost = false, failures = 5).ruleId)
}
