package cz.loplex.dogvision.packaging

import org.gradle.api.Project

/**
 * Marks [task], a task or its name, as one that makes an artifact of the module: the module's packageAll runs it, and
 * `./gradlew packageAll` runs every module's. A task of a plugin is named, as it may not be registered yet.
 */
fun Project.artifact(task: Any) {
    val packageAll = if ("packageAll" in tasks.names) {
        tasks.named("packageAll")
    } else {
        tasks.register("packageAll") {
            description = "Builds the module's artifacts, the tasks its build script marks with artifact()."
            group = "distribution"
        }
    }
    packageAll.configure { dependsOn(task) }
}
