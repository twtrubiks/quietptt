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
    }
}

rootProject.name = "quietptt"
// core：純 Kotlin（連線、終端模擬、畫面辨識），沒有 Android；app：畫面與 Android；tools/capture：在自己電腦上抓畫面當測試 fixture
include(":core", ":app", ":tools:capture")
