import org.gradle.kotlin.dsl.signing
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    libs.plugins.also {
        alias(it.kotlin.multiplatform)
        alias(it.android.library)
        alias(it.kotlinx.serialization)
        alias(it.kotlinx.atomicfu)
        alias(it.dokka.base)
        alias(it.maven.publish.vannik)
    }
    kotlin("native.cocoapods")
}

val appleFrameworkName = "KmpJillcess"

val iosMinSdk = "14"
val publishDomain = "io.github.skolson"
val appVersion = libs.versions.appVersion.get()

group = "com.oldguy"
version = appVersion

val githubUri = "skolson/$appleFrameworkName"
val githubUrl = "https://github.com/$githubUri"

val kmpPackageName = "com.oldguy.jillcess"

android {
    compileSdk = libs.versions.androidSdk.get().toInt()
    buildToolsVersion = libs.versions.androidBuildTools.get()
    namespace = kmpPackageName

    defaultConfig {
        minSdk = libs.versions.androidSdkMinimum.get().toInt()

        buildFeatures {
            buildConfig = false
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("tools/consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes.addAll( listOf(
        "META-INF/versions/9/*"
    ))

    dependencies {
        testImplementation(libs.junit)
        androidTestImplementation(libs.bundles.androidx.test)
    }
}
kotlin {
    // Turns off warnings about expect/actual class usage
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        publishLibraryVariants("release", "debug")
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
    macosX64 {
        binaries {
            framework {
                baseName = appleFrameworkName
                appleXcf.add(this)
                isStatic = true
            }
        }
    }
    macosArm64 {
        binaries {
            framework {
                baseName = appleFrameworkName
                appleXcf.add(this)
                isStatic = true
            }
        }
    }
    iosX64 {
        binaries {
            framework {
                baseName = appleFrameworkName
                appleXcf.add(this)
                isStatic = true
                freeCompilerArgs = freeCompilerArgs + listOf("-Xoverride-konan-properties=osVersionMin=$iosMinSdk")
            }
        }
    }
    iosArm64 {
        binaries {
            framework {
                baseName = appleFrameworkName
                appleXcf.add(this)
                isStatic = true
                freeCompilerArgs = freeCompilerArgs + listOf("-Xoverride-konan-properties=osVersionMin=$iosMinSdk")
            }
        }
    }
    jvm()
    linuxX64() {
        binaries {
            executable {
                debuggable = true
                linkerOpts.add("-Xlinker")
                linkerOpts.add("--allow-shlib-undefined")
            }
        }
        compilations.getByName("main") {
            val path = "${project.rootDir}/$appleFrameworkName/src/linuxMain/cinterop"
            cinterops {
                val myLibraryCinterop by creating {
                    defFile(project.file("$path/libxml2.def"))
                    includeDirs("/usr/include/libxml2", "/usr/include", "/usr/include/x86_64-linux-gnu")
                }
            }
        }
    }
    linuxArm64() {
        binaries {
            executable {
                debuggable = true
                linkerOpts.add("-Xlinker")
                linkerOpts.add("--allow-shlib-undefined")
            }
        }
        compilations.getByName("main") {
            val path = "${project.rootDir}/$appleFrameworkName/src/linuxMain/cinterop"
            cinterops {
                val myLibraryCinterop by creating {
                    defFile(project.file("$path/libxml2.def"))
                    includeDirs("/usr/include/libxml2", "/usr/include", "/usr/include/x86_64-linux-gnu")
                }
            }
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kmp.io)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kmp.crypto)
                implementation(libs.kotlinx.datetime)
                implementation(libs.bignum)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
            }
        }
        val appleMain by getting {
        }
        val appleTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.xml.pull.parser)
            }
        }
        val jvmTest by getting {
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
            true,
            listOf("debug", "release")
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

tasks.withType<Test> {
    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
        showStackTraces = true
    }
}