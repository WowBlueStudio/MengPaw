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
    }
}

rootProject.name = "MengPaw"

include(":mengpaw-core")
include(":mengpaw-design-system")
include(":mengpaw-shell")
include(":mengpaw-browser")
