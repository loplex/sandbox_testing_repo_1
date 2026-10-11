package cz.loplex.dogvision.packaging.jvm

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.CopySpec
import org.gradle.api.provider.Provider

/**
 * The names under which a package installs JARs of [artifacts] side by side in one folder,
 * for the JARs whose own name will not do, keyed by the JAR's path:
 * where two JARs have the same name, each gets its module's group in front of its name.
 * `org.jetbrains.compose.runtime`'s runtime-desktop is one such JAR,
 * a shim for `androidx.compose.runtime`'s JAR of the same name and version.\
 * A JAR the map has no key for keeps its own name.
 */
fun installedJarNames(artifacts: Set<ResolvedArtifactResult>): Map<String, String> =
    artifacts.groupBy { it.file.name }.values.filter { it.size > 1 }.flatten().associate { artifact ->
        val id = artifact.id.componentIdentifier
        check(id is ModuleComponentIdentifier) { "Two JARs named ${artifact.file.name}, one of them $id's" }
        artifact.file.path to "${id.group}-${artifact.file.name}"
    }

/** Copies each JAR [names] has a key for under the name it gives, as [installedJarNames] makes them. */
fun CopySpec.renameJars(names: Provider<Map<String, String>>) {
    eachFile { names.get()[file.path]?.let { name = it } }
}
