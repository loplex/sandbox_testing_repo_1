// Plugins are declared here once, so that every module shares one version of each.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

// The Kotlin/JS modules download Node.js from the repository declared in settings.gradle.kts, which is where the build
// declares every repository, instead of adding one of their own; the root project sets it up for them.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl = null
    }
}

// Every module's Kotlin, the build scripts' included, is held to .editorconfig by ktlint in `check`.
val ktlintPlugin = libs.plugins.ktlint.get().pluginId
val ktlintVersion = libs.versions.ktlint.cli.get()
subprojects {
    @Suppress("AvoidApplyPluginMethod")
    apply(plugin = ktlintPlugin)
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = ktlintVersion
    }
}

// ktlint leaves a line that holds a comment and nothing else as long as it is, so every line of Kotlin is held to
// .editorconfig's max_line_length here, in each module's `check`.
val maxLineLength = file(".editorconfig").readLines()
    .dropWhile { it.trim() != "[*.{kt,kts}]" }.drop(1)
    .takeWhile { !it.startsWith("[") }
    .firstNotNullOf { Regex("""max_line_length\s*=\s*(\d+)""").matchEntire(it.trim())?.groupValues?.get(1)?.toInt() }
val checkLineLength = tasks.register("checkLineLength") {
    description = "Fails on a line of Kotlin longer than .editorconfig's max_line_length, comments included."
    val root = rootDir
    val limit = maxLineLength
    // What git tracks, and the new files it does not ignore, as tools/check_links.py reads them.
    val sources = files(
        providers.exec {
            commandLine("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "*.kt", "*.kts")
        }.standardOutput.asText.map { listed ->
            listed.split('\u0000').filter { it.isNotEmpty() && File(root, it).isFile }
        },
    )
    inputs.files(sources)
    inputs.property("maxLineLength", limit)
    doLast {
        val tooLong = sources.files.sorted().flatMap { source ->
            source.readLines().mapIndexedNotNull { i, line ->
                val length = line.codePointCount(0, line.length)
                if (length > limit) "${source.relativeTo(root)}:${i + 1}: $length > $limit characters" else null
            }
        }
        if (tooLong.isNotEmpty()) throw GradleException(tooLong.joinToString("\n"))
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkLineLength) }
}
