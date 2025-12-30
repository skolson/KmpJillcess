import org.gradle.kotlin.dsl.signing
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.gradle.internal.os.OperatingSystem

plugins {
    libs.plugins.also {
        alias(it.kotlin.multiplatform)
        alias(it.android.kmp.library)
        alias(it.kotlinx.serialization)
        alias(it.kotlinx.atomicfu)
        alias(it.dokka.base)
        alias(it.maven.publish.vannik)
        alias(it.kotlin.cocoapods)
    }
}

val appleFrameworkName = "KmpJillcess"

val iosMinSdk = "14"
val publishDomain = "io.github.skolson"
val appVersion: String = libs.versions.appVersion.get()

group = "com.oldguy"
version = appVersion

val githubUri = "skolson/$appleFrameworkName"
val githubUrl = "https://github.com/$githubUri"

val kmpPackageName = "com.oldguy.jillcess"

kotlin {
    // Turns off warnings about expect/actual class usage
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = libs.versions.androidSdk.get().toInt()
        buildToolsVersion = libs.versions.androidBuildTools.get()
        namespace = kmpPackageName

        minSdk = libs.versions.androidSdkMinimum.get().toInt()

        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }

        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files.add(project.file("proguard-rules.pro"))
        }
    }

    cocoapods {
        name = appleFrameworkName
        ios.deploymentTarget = iosMinSdk
        summary = "Kotlin Multiplatform Read MS-Access database files"
        homepage = githubUrl
        license = "Apache 2.0"
        authors = "Steven Olson"
        framework {
            baseName = appleFrameworkName
            isStatic = true
        }
        // Maps custom Xcode configuration to NativeBuildType
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }

    val appleXcf = XCFramework()
    listOf(
        macosX64(), macosArm64(), iosX64(), iosArm64(), iosSimulatorArm64()
    ).forEach {
        val name = it.name
        it.binaries.framework {
            baseName = appleFrameworkName
            appleXcf.add(this)
            isStatic = true
            if (name.contains("ios")) {
                freeCompilerArgs = freeCompilerArgs + listOf("-Xoverride-konan-properties=osVersionMin=$iosMinSdk")
            }
        }
    }
    jvm()
    linuxX64()
    linuxArm64()

    applyDefaultHierarchyTemplate()
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kmp.io)
                implementation(libs.kmp.markup)
                implementation(libs.kmp.crypto)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.bignum)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }
        getByName("appleTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.bouncycastle)
            }
        }
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
            }
        }
    }
}

dokka {
    moduleName.set("Kotlin Multiplatform Read MS-Access database files Library")
    dokkaSourceSets.commonMain {
        includes.from("$appleFrameworkName.md")
    }
    dokkaPublications.html {
        includes.from("$appleFrameworkName.md")
    }
}

mavenPublishing {
    coordinates(publishDomain, name, appVersion)
    configure(
        KotlinMultiplatform(
            JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            true
        )
    )

    pom {
        name.set("$appleFrameworkName Kotlin Multiplatform Read MS Access database files")
        description.set("Read only database API for reading MS Access database files. Supported 64 bit platforms; Android, IOS, Windows, Linux, MacOS")
        url.set(githubUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("oldguy")
                name.set("Steve Olson")
                email.set("skolson5903@gmail.com")
            }
        }
        scm {
            url.set(githubUrl)
            connection.set("scm:git:git://git@github.com:${githubUri}.git")
            developerConnection.set("cm:git:ssh://git@github.com:${githubUri}.git")
        }
    }
}