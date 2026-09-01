plugins {
    kotlin("jvm") version "2.2.10"
    application
    id("io.ktor.plugin") version "3.3.1"
}

group = "br.com.estouseguro"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-forwarded-header")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
}

application { mainClass.set("br.com.estouseguro.api.ApplicationKt") }

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
