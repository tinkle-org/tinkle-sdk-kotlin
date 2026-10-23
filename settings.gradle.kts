pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "sdkSmoke"
            url = uri("build/maven")
            content { includeGroup("com.github.tinklehq") }
        }
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.tinklehq") }
        }
    }
}

rootProject.name = "tinkle-sdk-kotlin"
// Keep the JitPack build a single-publication root library. Explicit consumer
// tasks opt in to the unpublished smoke app; ordinary SDK assembly stays small.
if (providers.gradleProperty("includeSample").orNull == "true" ||
    gradle.startParameter.taskNames.any { it.startsWith(":sample:") || it.startsWith("sample:") }) {
    include(":sample")
}
