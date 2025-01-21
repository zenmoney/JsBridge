plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "app.zenmoney.jsbridge"
version = "1.0"

repositories {
    google()
    mavenCentral()
}

ktlint {
    version.set("1.4.1")
}

kotlin {
    jvm()
    jvmToolchain(17)
    androidTarget()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "jsbridge"
        }
    }
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("androidx.collection:collection:1.4.5")
                implementation("com.caoccao.javet:javet:4.1.1")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation("com.caoccao.javet:javet-v8-linux-arm64:4.1.1")
                implementation("com.caoccao.javet:javet-v8-linux-x86_64:4.1.1")
                implementation("com.caoccao.javet:javet-v8-macos-arm64:4.1.1")
                implementation("com.caoccao.javet:javet-v8-macos-x86_64:4.1.1")
                implementation("com.caoccao.javet:javet-v8-windows-x86_64:4.1.1")
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation("com.caoccao.javet:javet-v8-android:4.1.1")
            }
        }
        val darwinMain by creating {
            dependsOn(commonMain)
        }
        val iosX64Main by getting {
            dependsOn(darwinMain)
        }
        val iosArm64Main by getting {
            dependsOn(darwinMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(darwinMain)
        }
        val macosX64Main by getting {
            dependsOn(darwinMain)
        }
        val macosArm64Main by getting {
            dependsOn(darwinMain)
        }
    }

    sourceSets.all {
        languageSettings.apply {
            optIn("kotlinx.cinterop.ExperimentalForeignApi")
            optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            optIn("kotlinx.coroutines.FlowPreview")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
            optIn("kotlin.RequiresOptIn")
            optIn("kotlin.contracts.ExperimentalContracts")
            optIn("kotlin.experimental.ExperimentalNativeApi")
            optIn("kotlin.js.ExperimentalJsExport")
        }
    }
}

android {
    namespace = "app.zenmoney.jsbridge"
    compileSdk = 34
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions
        .jvmTarget
        .set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}

// kotlin.targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java) {
//     binaries.all {
//         freeCompilerArgs += "-Xgc=cms"
//         freeCompilerArgs += "-Xallocator=custom"
//     }
// }

// See https://youtrack.jetbrains.com/issue/KT-55751
configurations.names.forEach { name ->
    if (name.endsWith("Fat")) {
        configurations.named(name).configure {
            attributes {
                attribute(Attribute.of("isFat", String::class.java), "true")
            }
        }
    }
}
