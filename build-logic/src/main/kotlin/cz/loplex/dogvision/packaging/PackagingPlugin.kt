package cz.loplex.dogvision.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by a module that makes Linux packages. It registers nothing: applying it puts this package's tasks and
 * actions on the module's build script classpath, where the script registers them and wires them to what it builds.
 */
class PackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) = Unit
}
