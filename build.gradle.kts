import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ktlint)
    id("maven-publish")
    id("signing")
}

group = "app.zenmoney.jsbridge"
version = "2.0.0-rc12"

repositories {
    google()
    mavenCentral()
    maven(url = "https://jitpack.io") {
        content { includeGroup("com.github.ynab") }
    }
}

ktlint {
    version.set(libs.versions.ktlint.tool)
}

kotlin {
    jvm {
        compilerOptions {
            // Match Javet's Java 8 runtime baseline while building with JDK 17.
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjdk-release=8")
        }
    }
    jvmToolchain(17)

    android {
        namespace = "app.zenmoney.jsbridge"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "JsBridge"
        }
    }
    macosArm64()

    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain =
            getByName("commonMain") {
                dependencies {
                    implementation(libs.stately.concurrency)
                    implementation(libs.androidx.collection)
                    api(libs.kotlinx.coroutines.core)
                }
            }
        named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmAndAndroidMain =
            create("jvmAndAndroidMain") {
                dependsOn(commonMain)
                dependencies {
                    compileOnly(libs.javet)
                }
            }
        named("jvmMain") {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation(libs.javet)
                runtimeOnly(libs.javet.v8.linux.arm64)
                runtimeOnly(libs.javet.v8.linux.x64)
                runtimeOnly(libs.javet.v8.macos.arm64)
                runtimeOnly(libs.javet.v8.windows.x64)
            }
        }
        named("androidMain") {
            dependencies {
                implementation(libs.j2v8)
            }
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
            }
        }
    }

    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlin.contracts.ExperimentalContracts",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    // WKWebView needs the booted simulator's system services.
    standalone.set(false)
}

tasks.register<Test>("jvmTestJava8") {
    group = "verification"
    description = "Runs the JVM tests on Javet's minimum supported Java version."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
            // Azul provides a native macOS ARM64 JDK 8 for the bundled Javet ARM64 runtime.
            vendor.set(JvmVendorSpec.AZUL)
        },
    )
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

val javadocJar =
    tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
    }
// https://github.com/gradle/gradle/issues/26091
val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}
publishing {
    repositories {
        maven {
            name = "buildDir"
            url = uri(layout.buildDirectory.dir("m2"))
        }
        maven {
            name = "sonatype"
            url =
                uri(
                    if (version.toString().endsWith("SNAPSHOT")) {
                        "https://central.sonatype.com/repository/maven-snapshots/"
                    } else {
                        "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
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
