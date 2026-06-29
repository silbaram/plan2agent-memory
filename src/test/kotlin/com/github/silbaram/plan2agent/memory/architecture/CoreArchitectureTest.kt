package com.github.silbaram.plan2agent.memory.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.readText

class CoreArchitectureTest {
    @Test
    fun `application core does not depend on adapters or infrastructure frameworks`() {
        val forbiddenPatterns = listOf(
            "org.springframework.web",
            "org.springframework.jdbc",
            "java.sql",
            "javax.sql",
            "org.postgresql",
            "pgvector",
            "com.github.silbaram.plan2agent.memory.adapter",
            "adapter.`",
        )

        val violations = coreSourceFiles().flatMap { file ->
            val content = file.readText()
            forbiddenPatterns
                .filter { pattern -> content.contains(pattern) }
                .map { pattern -> "${file.relativeTo(projectRoot())}: $pattern" }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `production code and dependency declarations contain no external AI provider paths`() {
        val forbiddenPatterns = listOf(
            Regex("""(?i)\bopenai\b"""),
            Regex("""(?i)\banthropic\b"""),
            Regex("""(?i)\bgemini\b"""),
            Regex("""(?i)\bbedrock\b"""),
            Regex("""(?i)\bai-sdk\b"""),
            Regex("""(?i)\blangchain\b"""),
        )
        val files = sourceFilesUnder("src/main/kotlin") + listOf(projectRoot().resolve("build.gradle.kts"))

        val violations = files.flatMap { file ->
            val content = file.readText()
            forbiddenPatterns
                .filter { pattern -> pattern.containsMatchIn(content) }
                .map { pattern -> "${file.relativeTo(projectRoot())}: ${pattern.pattern}" }
        }

        assertThat(violations).isEmpty()
    }

    private fun coreSourceFiles(): List<Path> =
        sourceFilesUnder("src/main/kotlin/com/github/silbaram/plan2agent/memory/application") +
            sourceFilesUnder("src/main/kotlin/com/github/silbaram/plan2agent/memory/domain")

    private fun sourceFilesUnder(relativePath: String): List<Path> {
        val root = projectRoot().resolve(relativePath)
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }
    }

    private fun projectRoot(): Path =
        Path.of("").toAbsolutePath()
}
