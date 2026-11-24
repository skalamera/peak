package com.cl600.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsRulesRegressionTest {

    private companion object {
        val TEST_CONFIGURE_EACH_REGEX = Regex(
            """tasks\s*\.\s*withType\s*<\s*(?:org\.gradle\.api\.tasks\.testing\.)?Test\s*>\s*\(\s*\)\s*\.\s*configureEach\s*\{"""
        )
        val AGENTS_MD_INPUT_REGEX = Regex(
            """inputs\s*\.\s*file\s*\(\s*rootProject\s*\.\s*layout\s*\.\s*projectDirectory\s*\.\s*file\s*\(\s*"AGENTS\.md"\s*\)\s*\)"""
        )
        const val REQUIRED_WORKER_POLICY_SENTENCE =
            "Automated Council audit, proof, and auto-patch workers must strictly honor their caller's " +
                "branch, commit, and push restrictions and must never commit or push on their own."
    }

    private fun resolveRepoRoot(): File {
        val candidates = listOf(
            File(".").canonicalFile,
            File("..").canonicalFile
        )
        return candidates.firstOrNull { dir ->
            File(dir, "settings.gradle.kts").exists() &&
                File(dir, "app/build.gradle.kts").exists()
        } ?: error("Could not locate repository root from ${File("").canonicalPath}")
    }

    private fun resolveAgentsMd(): File =
        File(resolveRepoRoot(), "AGENTS.md").also { file ->
            check(file.exists()) { "Could not locate AGENTS.md at ${file.absolutePath}" }
        }

    private fun resolveAppBuildGradle(): File =
        File(resolveRepoRoot(), "app/build.gradle.kts").also { file ->
            check(file.exists()) { "Could not locate app/build.gradle.kts at ${file.absolutePath}" }
        }

    private fun advancePastLiteral(source: String, index: Int): Int? {
        if (source.startsWith("\"\"\"", index)) {
            var j = index + 3
            while (j < source.length && !source.startsWith("\"\"\"", j)) {
                j++
            }
            return if (source.startsWith("\"\"\"", j)) j + 3 else source.length
        }
        val quote = source[index]
        if (quote != '"' && quote != '\'') return null
        var j = index + 1
        while (j < source.length) {
            val c = source[j++]
            if (c == '\\' && j < source.length) {
                j++
            } else if (c == quote) {
                return j
            }
        }
        return source.length
    }

    private fun stripKotlinComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val literalEnd = advancePastLiteral(source, i)
            if (literalEnd != null) {
                sb.append(source, i, literalEnd)
                i = literalEnd
                continue
            }
            if (source.startsWith("//", i)) {
                i += 2
                while (i < source.length && source[i] != '\n') {
                    i++
                }
                continue
            }
            if (source.startsWith("/*", i)) {
                var depth = 1
                i += 2
                while (i < source.length && depth > 0) {
                    if (source.startsWith("/*", i)) {
                        depth++
                        i += 2
                    } else if (source.startsWith("*/", i)) {
                        depth--
                        i += 2
                    } else {
                        i++
                    }
                }
                sb.append(' ')
                continue
            }
            sb.append(source[i++])
        }
        return sb.toString()
    }

    private fun extractBraceBlock(source: String, openBraceIndex: Int): String {
        var i = openBraceIndex + 1
        var depth = 1
        while (i < source.length && depth > 0) {
            val literalEnd = advancePastLiteral(source, i)
            if (literalEnd != null) {
                i = literalEnd
                continue
            }
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        val endExclusive = if (depth == 0) i - 1 else source.length
        return source.substring(openBraceIndex + 1, endExclusive)
    }

    internal fun hasTestTaskAgentsMdInput(buildGradleText: String): Boolean {
        val stripped = stripKotlinComments(buildGradleText)
        return TEST_CONFIGURE_EACH_REGEX.findAll(stripped).any { match ->
            val blockBody = extractBraceBlock(stripped, match.range.last)
            AGENTS_MD_INPUT_REGEX.containsMatchIn(blockBody)
        }
    }

    @Test
    fun crit88_testTasksDeclareAgentsMdAsInput() {
        val buildGradleText = resolveAppBuildGradle().readText()
        assertTrue(
            "app/build.gradle.kts must declare repo-root AGENTS.md as a Gradle Test task input",
            hasTestTaskAgentsMdInput(buildGradleText)
        )
        assertFalse(
            hasTestTaskAgentsMdInput("// inputs.file(rootProject.layout.projectDirectory.file(\"AGENTS.md\"))")
        )
        assertFalse(
            hasTestTaskAgentsMdInput(
                "tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {\n" +
                    "    // inputs.file(rootProject.layout.projectDirectory.file(\"AGENTS.md\"))\n" +
                    "}"
            )
        )
        assertFalse(
            hasTestTaskAgentsMdInput(
                "tasks.register(\"other\") {\n" +
                    "    inputs.file(rootProject.layout.projectDirectory.file(\"AGENTS.md\"))\n" +
                    "}"
            )
        )
    }

    internal fun validateAgentsPolicy(text: String): List<String> {
        val violations = mutableListOf<String>()
        if (text.contains("always commit directly on") ||
            text.contains("don't commit to main directly") ||
            text.contains("do not push or merge")
        ) {
            violations += "AGENTS.md must not unconditionally override no-commit or no-push caller controls"
        }
        if (!text.contains("explicitly authorized to publish")) {
            violations += "AGENTS.md must gate direct-to-main publication on explicit authorization"
        }
        if (!text.contains(REQUIRED_WORKER_POLICY_SENTENCE)) {
            violations += "AGENTS.md must require Council audit, proof, and auto-patch workers to honor caller commit/push restrictions"
        }
        if (!text.contains("single `main` branch") || !text.contains("`skalamera`")) {
            violations += "AGENTS.md must preserve the single main branch and skalamera auth rules"
        }
        return violations
    }

    @Test
    fun crit87_agentsMdHonorsCouncilWorkerCommitAndPushRestrictions() {
        val violations = validateAgentsPolicy(resolveAgentsMd().readText())
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun crit89_policyValidationRejectsAutonomousWorkerCommitPermission() {
        val original = resolveAgentsMd().readText()
        val targetClause = "branch, commit, and push restrictions and must never commit or push on their own."
        assertTrue(
            "Baseline AGENTS.md must contain expected worker restriction clause",
            original.contains(targetClause)
        )

        val allowWorkerCommit = original.replace(
            targetClause,
            "branch and push restrictions and may commit on their own."
        )
        assertTrue(
            "Policy validation must fail if workers are allowed to commit on their own",
            validateAgentsPolicy(allowWorkerCommit).isNotEmpty()
        )

        val dropProhibition = original.replace(
            targetClause,
            "branch, commit, and push restrictions."
        )
        assertTrue(
            "Policy validation must fail if explicit never-commit-or-push clause is removed",
            validateAgentsPolicy(dropProhibition).isNotEmpty()
        )
    }
}
