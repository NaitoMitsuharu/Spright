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

rootProject.name = "BLE1507Android"
include(":app")
include(":sprboxlib-std")
include(":sprboxlib-imuadv")

project(":sprboxlib-std").projectDir = file("third_party/sprbox-SDK/sprboxlib-std")
project(":sprboxlib-imuadv").projectDir = file("third_party/sprbox-SDK/sprboxlib-imuadv")
