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
        // Xposed API repository
        maven {
            url = uri("https://api.xposed.info/")
        }
        // JitPack for Xposed API (alternative)
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "TestAutoJs"
include(":app")
 