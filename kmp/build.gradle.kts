import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "com.openai.companion"
version = "0.1.0"

kotlin {
    jvmToolchain(11)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:3.2.3")
            implementation("io.ktor:ktor-client-sse:3.2.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.2.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

