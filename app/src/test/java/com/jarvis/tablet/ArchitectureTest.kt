package com.jarvis.tablet

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dependency rule of Clean Architecture, enforced on every build:
 *   domain  -> depends on nothing but the Kotlin/JVM standard library and coroutines
 *   data    -> may depend on domain only (never on Engine or the UI)
 * Adding an Android or data import to a domain file, or an Engine import to a data file, fails this test.
 */
class ArchitectureTest {
    private fun sources(sub: String): List<File> {
        val dir = File("src/main/java/com/jarvis/tablet/$sub")
        assertTrue("source folder not found: ${dir.absolutePath}", dir.exists())
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun violations(files: List<File>, forbidden: List<String>): List<String> =
        files.flatMap { f ->
            f.readLines().filter { it.trimStart().startsWith("import ") }
                .filter { line -> forbidden.any { line.contains(it) } }
                .map { "${f.name}: $it" }
        }

    @Test fun domainIsPureKotlin() {
        val bad = violations(sources("domain"), listOf("import android.", "import org.json", "import androidx.", "com.jarvis.tablet.data", "com.jarvis.tablet.Engine", "com.jarvis.tablet.Keys", "com.jarvis.tablet.MainActivity"))
        assertTrue("domain layer must stay pure: $bad", bad.isEmpty())
    }

    @Test fun dataLayerNeverReachesIntoTheEngineOrUi() {
        val bad = violations(sources("data"), listOf("com.jarvis.tablet.Engine", "com.jarvis.tablet.MainActivity", "com.jarvis.tablet.Keys", "com.jarvis.tablet.Bus"))
        assertTrue("data layer must depend on domain only: $bad", bad.isEmpty())
    }

    @Test fun domainHasRealContent() {
        assertTrue(sources("domain").size >= 6)
    }
}
