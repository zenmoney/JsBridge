pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.kotlin.multiplatform.library") {
                useModule("com.android.tools.build:gradle:9.1.0")
            }
        }
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.3.21"
        kotlin("plugin.serialization") version "2.3.21"
        id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jsbridge"
