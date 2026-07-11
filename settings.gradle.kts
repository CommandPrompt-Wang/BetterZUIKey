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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo1.maven.org/maven2/")
        maven("https://repo.highcapable.cn/releases")
        maven("https://jitpack.io")
    }
}

rootProject.name = "BetterZUIKey"
include(":app")
include(":termuxam", ":termuxam:app")
project(":termuxam").projectDir = file("app/termuxam")
project(":termuxam:app").projectDir = file("app/termuxam/app")
