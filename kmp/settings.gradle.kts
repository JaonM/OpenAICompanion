pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "openai-companion-kmp"
include(":iosRustBridge")
include(":iosApp")
if (!providers.gradleProperty("skipAndroidApp").map(String::toBoolean).getOrElse(false)) {
    include(":androidApp")
}
