package br.com.estouseguro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationPolicyTest {
    @Test
    fun `sandbox backend is enabled only with a safe debug URL`() {
        val hasUrl = BuildConfig.API_BASE_URL.isNotBlank()

        assertEquals(hasUrl, BuildConfig.SANDBOX_BACKEND_ENABLED)

        if (BuildConfig.SANDBOX_BACKEND_ENABLED) {
            assertTrue(BuildConfig.API_BASE_URL.startsWith("https://"))
        }
    }

    @Test
    fun `android source and build configuration never reference the master sandbox key`() {
        val prohibitedIdentifiers = listOf(
            "SANDBOX_" + "REGISTRATION_KEY",
            "ESTOU_SEGURO_" + "SANDBOX_KEY",
        )
        val files = sequenceOf(File("build.gradle.kts")) + File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "properties") }

        files.forEach { file ->
            val content = file.readText()
            prohibitedIdentifiers.forEach { identifier ->
                assertFalse("Master sandbox credential reference found in $file", content.contains(identifier))
            }
        }
    }
}
