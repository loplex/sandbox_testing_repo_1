import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "ij-inspection-filter"

pluginManagement {
    plugins {
        // Pinned by the oldest IDE the plugin installs into (sinceBuild in build.gradle.kts), not by the one it
        // compiles against: the plugin ships no Kotlin stdlib of its own (kotlin.stdlib.default.dependency in
        // gradle.properties), so its code runs on whichever stdlib that IDE bundles, and a newer compiler can emit
        // metadata an older stdlib cannot read.
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
