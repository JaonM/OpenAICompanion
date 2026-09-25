import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "com.openai.companion"
version = "0.1.0"

// Permit a separate package build while a previously packaged app is running.
providers.gradleProperty("companionBuildDir").orNull?.let { path ->
    layout.buildDirectory.set(file(path))
}

val mcpSdkVersion = "0.10.0"

kotlin {
    // Gradle is launched with JDK 22; keep generated JVM bytecode at 11 for
    // consumers that still run on the existing project baseline.
    jvmToolchain(providers.gradleProperty("kmpJvmToolchain").map(String::toInt).getOrElse(22))

    androidTarget()
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "OpenAICompanionShared"
            isStatic = true
        }
    }
    macosX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        jvmMain.dependencies {
            implementation("io.modelcontextprotocol:kotlin-sdk:$mcpSdkVersion")
            implementation("io.modelcontextprotocol:kotlin-sdk-testing:$mcpSdkVersion")
            implementation("io.ktor:ktor-client-core:3.2.3")
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation("io.ktor:ktor-client-cio:3.2.3")
            // UniFFI's generated JVM bindings use JNA to load and call libharness.
            implementation("net.java.dev.jna:jna:5.15.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.openai.companion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "OpenAICompanion"
            packageVersion = "1.0.0"
            modules("java.net.http")
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
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
