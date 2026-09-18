import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.openai.companion"
version = "0.1.0"

val mcpSdkVersion = "0.10.0"

kotlin {
    // Gradle is launched with JDK 22; keep generated JVM bytecode at 11 for
    // consumers that still run on the existing project baseline.
    jvmToolchain(22)

    androidTarget()
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk:$mcpSdkVersion")
            implementation("io.modelcontextprotocol:kotlin-sdk-testing:$mcpSdkVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("io.ktor:ktor-client-core:3.2.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.2.3")
            // UniFFI's generated JVM bindings use JNA to load and call libharness.
            implementation("net.java.dev.jna:jna:5.15.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.openai.companion.kmp"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
