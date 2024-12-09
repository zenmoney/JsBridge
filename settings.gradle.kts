pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:8.5.2")
            }
        }
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.0.21"
        kotlin("plugin.serialization") version "2.0.21"
        id("org.jetbrains.kotlin.xcode-compat") version "0.2.5"
        id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.6.0"
}

rootProject.name = "jsbridge"
