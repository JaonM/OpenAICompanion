plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "OpenAICompanionAppShared"
            isStatic = true
            export(project(":"))
        }
        target.compilations.getByName("main").cinterops.create("localLlama") {
            defFile(project.file("src/nativeInterop/cinterop/localLlama.def"))
            includeDirs(project.file("../../ios/OpenAICompanion"))
        }
    }
    sourceSets {
        iosMain.dependencies {
            api(project(":"))
            implementation(project(":iosRustBridge"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}
