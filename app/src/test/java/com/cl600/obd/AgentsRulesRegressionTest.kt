package com.cl600.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsRulesRegressionTest {

    private fun resolveAgentsMd(): File {
        val candidates = listOf(
            File("AGENTS.md"),
            File("../AGENTS.md")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate AGENTS.md from ${File("").absolutePath}")
    }

    @Test
    fun crit87_agentsMdHonorsCouncilWorkerCommitAndPushRestrictions() {
        val text = resolveAgentsMd().readText()

        assertFalse(
            "AGENTS.md must not unconditionally override no-commit or no-push caller controls",
            text.contains("always commit directly on") ||
                text.contains("don't commit to main directly") ||
                text.contains("do not push or merge")
        )
        assertTrue(
            "AGENTS.md must gate direct-to-main publication on explicit authorization",
            text.contains("explicitly authorized to publish")
        )
        assertTrue(
            "AGENTS.md must require Council audit, proof, and auto-patch workers to honor caller commit/push restrictions",
            text.contains("Council audit, proof, and auto-patch workers") &&
                text.contains("honor their caller's") &&
                text.contains("commit") &&
                text.contains("push restrictions")
        )
        assertTrue(
            "AGENTS.md must preserve the single main branch and skalamera auth rules",
            text.contains("single `main` branch") &&
                text.contains("`skalamera`")
        )
    }
}
