import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    libs.plugins.also {
        alias(it.kotlin.multiplatform) apply false
        alias(it.android.library) apply false
        alias(it.kotlinx.atomicfu) apply false
        alias(it.kotlinx.serialization) apply false
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
