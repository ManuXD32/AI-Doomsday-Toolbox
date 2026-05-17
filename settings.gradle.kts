pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LlamaDroid"
include(":app")

// Asset Packs for native binary delivery
include(":asset_upscaler")
include(":feature_llm_baseline", ":feature_llm_dotprod", ":feature_llm_armv9")
include(":feature_llm_snapdragon_opencl")
include(":feature_kiwix_baseline", ":feature_kiwix_dotprod", ":feature_kiwix_armv9")
include(":feature_media_baseline", ":feature_media_dotprod", ":feature_media_armv9")
include(":feature_upscaler")
