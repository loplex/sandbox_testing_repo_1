plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

subprojects {
    plugins.withId("dev.detekt") {
        // `check` runs the detekt task with type resolution, which reads the module's compile classpath.
        tasks.named("check") { dependsOn(tasks.matching { it.name == "detektMain" }) }
        // -Prepro.detektDebug=true prints detekt's classpath and the compiler errors it finds.
        if (providers.gradleProperty("repro.detektDebug").orNull == "true") {
            configure<dev.detekt.gradle.extensions.DetektExtension> { debug = true }
        }
    }
}
