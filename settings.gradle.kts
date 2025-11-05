pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:8.13.0")
            }
        }
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
        id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.6.0"
}

rootProject.name = "jsbridge"
