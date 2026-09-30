import gobley.gradle.rust.targets.RustPosixTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("org.jetbrains.kotlin.plugin.atomicfu") version "2.2.0"
    id("dev.gobley.cargo") version "0.3.7"
    id("dev.gobley.uniffi") version "0.3.7"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
        }
    }
}

cargo {
    packageDirectory = layout.projectDirectory.dir("../../harness")
}

uniffi {
    generateFromLibrary {
        namespace = "harness"
        packageName = "uniffi.harness"
        build = when (System.getProperty("os.arch")) {
            "aarch64", "arm64" -> RustPosixTarget.MacOSArm64
            "x86_64", "amd64" -> RustPosixTarget.MacOSX64
            else -> error("Unsupported macOS host architecture for UniFFI generation")
        }
    }
}
