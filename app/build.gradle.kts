import java.util.Properties

plugins {
    id("com.android.application")
}

val sandboxConfigFile = rootProject.file("sandbox.local.properties")
val sandboxConfig = Properties().apply {
    if (sandboxConfigFile.isFile) sandboxConfigFile.inputStream().use(::load)
}
val standardLocalConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun firstConfigured(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()

// Environment variables have precedence for ephemeral CI/local builds and are never written to disk.
val sandboxApiBaseUrl = firstConfigured(
    System.getenv("ESTOU_SEGURO_API_BASE_URL"),
    sandboxConfig.getProperty("API_BASE_URL"),
    standardLocalConfig.getProperty("ESTOU_SEGURO_API_BASE_URL"),
    standardLocalConfig.getProperty("API_BASE_URL"),
).trimEnd('/')
val sandboxBackendEnabled = sandboxApiBaseUrl.isNotEmpty()

if (sandboxBackendEnabled) {
    check(sandboxApiBaseUrl.startsWith("https://")) { "Sandbox API_BASE_URL must use HTTPS" }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "br.com.estouseguro"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.estouseguro"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            buildConfigField("String", "API_BASE_URL", sandboxApiBaseUrl.asBuildConfigString())
            buildConfigField("boolean", "SANDBOX_BACKEND_ENABLED", sandboxBackendEnabled.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sandbox bootstrap credentials must never be present in a distributable build.
            buildConfigField("String", "API_BASE_URL", "".asBuildConfigString())
            buildConfigField("boolean", "SANDBOX_BACKEND_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.register("verifyBackendConfigurationPolicy") {
    group = "verification"
    description = "Verifies that local sandbox configuration is ignored and release-safe."
    doLast {
        val gitignore = rootProject.file(".gitignore").readText()
        val ignoredEntries = gitignore.lineSequence().map(String::trim).toSet()
        check("local.properties" in ignoredEntries) {
            "local.properties must remain ignored by Git"
        }
        check("sandbox.local.properties" in ignoredEntries) {
            "sandbox.local.properties must remain ignored by Git"
        }
        val prohibitedIdentifiers = listOf(
            "SANDBOX_" + "REGISTRATION_KEY",
            "ESTOU_SEGURO_" + "SANDBOX_KEY",
        )
        val androidConfigurationFiles = sequenceOf(project.file("build.gradle.kts")) +
            project.fileTree("src/main").matching {
                include("**/*.kt", "**/*.java", "**/*.xml", "**/*.properties")
            }.files.asSequence()
        androidConfigurationFiles.forEach { file ->
            val content = file.readText()
            check(prohibitedIdentifiers.none(content::contains)) {
                "Master sandbox credential identifiers are prohibited in Android source/configuration: ${file.relativeTo(projectDir)}"
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn("verifyBackendConfigurationPolicy")
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
}
