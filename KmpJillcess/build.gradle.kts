import org.gradle.kotlin.dsl.signing
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("com.android.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.6.10"
    kotlin("native.cocoapods")
    id("maven-publish")
    id("signing")
    id("kotlinx-atomicfu")
    id("org.jetbrains.dokka") version "1.6.10"
    id("com.github.ben-manes.versions") version "0.42.0"
}

val mavenArtifactId = "kmp-jillcess"
val appleFrameworkName = "KmpJillcess"
group = "com.oldguy"
version = "0.1.0"

val androidMinSdk = 26
val androidTargetSdkVersion = 32
val iosMinSdk = "14"
val kmpPackageName = "com.oldguy.jillcess"

val androidMainDirectory = projectDir.resolve("src").resolve("androidMain")
val javadocTaskName = "javadocJar"

android {
    compileSdk = androidTargetSdkVersion
    buildToolsVersion = "33.0.0-rc1"

    sourceSets {
        getByName("main") {
            java.srcDir(androidMainDirectory.resolve("kotlin"))
            manifest.srcFile(androidMainDirectory.resolve("AndroidManifest.xml"))
        }
        getByName("test") {
            java.srcDir("src/androidTest/kotlin")
        }
        getByName("androidTest") {
            java.srcDir("src/androidAndroidTest/kotlin")
        }
    }

    defaultConfig {
        minSdk = androidMinSdk
        targetSdk = androidTargetSdkVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("tools/proguard-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_11
    }

    dependencies {
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test:core:1.4.0")
        androidTestImplementation("androidx.test:runner:1.4.0")
        androidTestImplementation("androidx.test.ext:junit:1.1.3")
    }
}

tasks {
    dokkaHtml {
        moduleName.set("Kotlin Multiplatform Cryptography Library")
        dokkaSourceSets {
            named("commonMain") {
                noAndroidSdkLink.set(false)
                includes.from("$appleFrameworkName.md")
            }
        }
    }
    create<Jar>(javadocTaskName) {
        dependsOn(dokkaHtml)
        archiveClassifier.set("javadoc")
        from(dokkaHtml.get().outputDirectory)
    }
}

val junitVersion = "5.8.2"
val junit5 = "org.junit.jupiter:junit-jupiter-api:$junitVersion"
val junit5Runtime = "org.junit.jupiter:junit-jupiter-engine:$junitVersion"
val kotlinCoroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.0"

kotlin {
    android {
        publishLibraryVariants("release", "debug")
        mavenPublication {
            artifactId = artifactId.replace(project.name, mavenArtifactId)
        }
    }

    val githubUri = "skolson/$appleFrameworkName"
    val githubUrl = "https://github.com/$githubUri"
    cocoapods {
        ios.deploymentTarget = iosMinSdk
        summary = "Kotlin Multiplatform Read MS-Access database files"
        homepage = githubUrl
        license = "Apache 2.0"
        authors = "Steven Olson"
        framework {
            baseName = appleFrameworkName
            isStatic = true
            embedBitcode(org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode.BITCODE)
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
    iosX64 {
        binaries {
            framework {
                appleXcf.add(this)
                isStatic = true
                freeCompilerArgs = freeCompilerArgs + listOf("-Xoverride-konan-properties=osVersionMin=$iosMinSdk")
            }
        }
    }
    iosArm64 {
        binaries {
            framework {
                appleXcf.add(this)
                isStatic = true
                embedBitcode("bitcode")
                freeCompilerArgs = freeCompilerArgs + listOf("-Xoverride-konan-properties=osVersionMin=$iosMinSdk")
            }
        }
    }
    jvm()

    @Suppress("UNUSED_VARIABLE")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
                implementation("com.oldguy:kmp-io:0.1.2")
                implementation("com.oldguy:kmp-crypto:0.1.2")
                implementation("com.soywiz.korlibs.klock:klock:2.5.3")
                implementation("com.ionspin.kotlin:bignum:0.3.4")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.0")
            }
        }
        val androidMain by getting {
            dependsOn(commonMain)
        }

        val androidTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("junit:junit:4.13.2")
            }
        }
        val androidAndroidTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("junit:junit:4.13.2")
            }
        }
        val appleNativeMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleNativeMain/kotlin")
        }
        val appleNativeTest by creating {
            kotlin.srcDir("src/appleNativeTest/kotlin")
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.0")
            }
        }
        val iosX64Main by getting {
            dependsOn(appleNativeMain)
        }
        val iosX64Test by getting {
            dependsOn(appleNativeTest)
        }
        val iosArm64Main by getting {
            dependsOn(appleNativeMain)
        }
        val macosX64Main by getting {
            dependsOn(appleNativeMain)
        }
        val macosX64Test by getting {
            dependsOn(appleNativeTest)
        }
        val jvmMain by getting {
            dependsOn(commonMain)
            dependencies {
                implementation("net.sf.kxml:kxml2:2.3.0")
            }
        }
        val jvmTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlinCoroutinesTest)
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
            }
        }
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalCoroutinesApi")
            }
        }
    }

    publishing {
        publications.withType(MavenPublication::class) {
            artifactId = artifactId.replace(project.name, mavenArtifactId)
            artifact(tasks.getByPath(javadocTaskName))
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

signing {
    isRequired = false
    sign(publishing.publications)
}