@file:Suppress("UnstableApiUsage")

package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/** The usage of the variant that web hands :packaging its page by. */
const val WEB_PAGE_USAGE = "dog-vision-web-page"

/**
 * Registers webPageElements, the variant of the module that hands :packaging the folder [distribution] writes: the page
 * as a browser opens it, the script's source map with it.
 */
fun Project.webPage(distribution: TaskProvider<Sync>) {
    configurations.consumable("webPageElements") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, WEB_PAGE_USAGE)) }
        outgoing.artifact(layout.dir(distribution.map { it.destinationDir })) { builtBy(distribution) }
    }
}
