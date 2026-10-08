import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    // Said here rather than left to whichever JDK runs Gradle; 21 is what the platform this compiles against
    // requires.
    jvmToolchain(21)
}

// Tests that start the IDE itself, with the plugin installed, and drive its UI: Starter and Driver. Run them with
// scripts/ui-tests.sh, which gives them a display - see docs/development.md.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdea("2025.3.5")
        // Optional at runtime: for what "Unused declaration" says of an element it reports no problem in.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = integrationTest.implementationConfigurationName)
    }
    integrationTest.implementationConfigurationName("org.junit.jupiter:junit-jupiter:5.13.4")
    // The test runs outside the IDE, so it needs a stdlib of its own, and one as recent as the kotlin-reflect Starter
    // brings, which is not what comes along with Starter. Without a version, it is the one of the Kotlin plugin.
    integrationTest.implementationConfigurationName(kotlin("stdlib"))
    // Gradle no longer brings its own; the version comes from the JUnit BOM Starter depends on.
    integrationTest.runtimeOnlyConfigurationName("org.junit.platform:junit-platform-launcher")
    integrationTest.implementationConfigurationName("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTest.implementationConfigurationName("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        // The IDE Gradle has already unpacked, which the test starts rather than having Starter download one.
        systemProperty("path.to.platform", intellijPlatform.platformPath.toString())
        // Where Starter keeps the IDE's configuration, logs and reports, which it otherwise puts under out/ in the
        // root of the repository.
        systemProperty("ui.tests.dir", layout.buildDirectory.dir("ui-tests").get().asFile.path)
        systemProperty("allure.results.directory", layout.buildDirectory.dir("ui-tests/allure-results").get().asFile.path)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        changeNotes = providers.environmentVariable("CHANGE_NOTES")

        ideaVersion {
            sinceBuild = "253"

            // Left unbounded: an upper bound would make every IDE update a release the plugin needs in order to
            // keep working. `verifyPlugin` is what finds an IDE that breaks it.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
        // Sandbox copy: the plugin's internal API usage is not what this run tries.
        failureLevel = listOf(org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS)
    }

    // What signPlugin signs with, handed in as these variables by the release workflow; buildPlugin needs none of
    // them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

tasks.test {
    // ShortDescriptionTest reads both from the sources, so a change to either has to run the tests again.
    inputs.files("README.md", "src/main/resources/META-INF/plugin.xml")
}
