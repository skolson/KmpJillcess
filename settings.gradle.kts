pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

val projectNameMavenName = "kmp-jillcess"
rootProject.name = projectNameMavenName

include(":KmpJillcess")
project(":KmpJillcess").name = projectNameMavenName