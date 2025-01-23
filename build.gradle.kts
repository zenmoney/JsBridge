import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("maven-publish")
    id("signing")
}

group = "app.zenmoney.jsbridge"
version = "1.0.1"

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
    androidTarget {
        publishLibraryVariants("release", "debug")
    }
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
                compileOnly("com.caoccao.javet:javet:4.1.1")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation("com.caoccao.javet:javet:4.1.1")
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

extra.apply {
    val publishPropFile = rootProject.file("publish.properties")
    if (publishPropFile.exists()) {
        Properties()
            .apply {
                load(publishPropFile.inputStream())
            }.forEach { name, value ->
                if (name == "signing.secretKeyRingFile") {
                    set(name.toString(), rootProject.file(value.toString()).absolutePath)
                } else {
                    set(name.toString(), value)
                }
            }
    } else {
        for ((envKey, key) in listOf(
            "SIGNING_KEY_ID" to "signing.keyId",
            "SIGNING_PASSWORD" to "signing.password",
            "SIGNING_SECRET_KEY_RING_FILE" to "signing.secretKeyRingFile",
            "OSSRH_USERNAME" to "ossrhUsername",
            "OSSRH_PASSWORD" to "ossrhPassword",
        )) {
            if (envKey in System.getenv()) {
                set(key, System.getenv(envKey))
            }
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}
// https://github.com/gradle/gradle/issues/26091
val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}
tasks.register("publishToBuildDir") {
    doLast {
        extra.apply {
            set("publishToBuildDir", true)
        }
    }
    finalizedBy("publish")
}

publishing {
    repositories {
        maven {
            url =
                uri(
                    if (rootProject.hasProperty("publishToBuildDir")) {
                        layout.buildDirectory.dir("m2")
                    } else if (version.toString().endsWith("SNAPSHOT")) {
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    } else {
                        "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    },
                )
            if (listOf("ossrhUsername", "ossrhPassword").all { rootProject.hasProperty(it) }) {
                credentials {
                    username = rootProject.findProperty("ossrhUsername").toString()
                    password = rootProject.findProperty("ossrhPassword").toString()
                }
            }
        }
    }

    publications.withType<MavenPublication> {
        artifact(javadocJar)
        pom {
            name.set("JsBridge")
            description.set("A Kotlin Multiplatform library that provides JavaScript engine integration.")
            url.set("https://github.com/zenmoney/JsBridge")

            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("Zenmoney")
                    name.set("Zenmoney")
                    email.set("support@zenmoney.app")
                    organization.set("Zenmoney OU")
                    organizationUrl.set("https://zenmoney.app")
                }
            }
            scm {
                url.set("https://github.com/zenmoney/JsBridge")
                connection.set("scm:git:https://github.com/zenmoney/JsBridge.git")
                developerConnection.set("scm:git:ssh://github.com:zenmoney/JsBridge.git")
            }
        }
    }
}

if (listOf("signing.keyId", "signing.password", "signing.secretKeyRingFile").all { rootProject.hasProperty(it) }) {
    signing {
        sign(publishing.publications)
    }
}
