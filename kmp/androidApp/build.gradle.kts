plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.openai.companion.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.openai.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-fexceptions") }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

val buildRustHarness by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/android-rust.sh")
    val ndkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
        .resolve("ndk/27.2.12479018")
    inputs.files(rootProject.file("../harness/Cargo.toml"),
        rootProject.fileTree("../harness/src") { include("**/*.rs", "**/*.udl") })
    outputs.dir(file("src/main/jniLibs"))
    commandLine("bash", script.absolutePath)
    environment("ANDROID_NDK_HOME", ndkDirectory.absolutePath)
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildRustHarness)
    }
}

dependencies {
    implementation(project(":"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-okhttp:3.2.3")
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}
