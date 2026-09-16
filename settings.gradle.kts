pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional, checksum-verified local dependency cache for interrupted downloads.
        if (file(".dependency-cache").exists()) maven {
            url = uri(file(".dependency-cache"))
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Tongpin"
include(":app")
