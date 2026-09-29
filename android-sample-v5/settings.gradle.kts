pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenLocal(); google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "VisionSDKv5"
include(":app", ":baselineprofile")
