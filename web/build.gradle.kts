// The web page: a photo shown as a species sees it, rendered by the shared shaders in WebGL 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The Android app's string resources, which the page reads its wording from, compiled into it as the text of each
// values folder's strings.xml by language, the default folder's as English: a page opened as a file may fetch nothing.
val androidStrings = tasks.register("androidStrings") {
    description = "Writes the Android app's strings.xml files into a Kotlin source of the web page."
    val sources = fileTree(rootProject.file("android/src/main/res")) {
        include("values/strings.xml", "values-*/strings.xml")
    }
    val output = layout.buildDirectory.dir("generated/androidStrings")
    inputs.files(sources)
    outputs.dir(output)
    doLast {
        val entries = sources.files.sortedBy { it.parentFile.name }.joinToString("") { file ->
            val language = file.parentFile.name.removePrefix("values").removePrefix("-").ifEmpty { "en" }
            val text = file.readText()
            check("\"\"\"" !in text) { "$file holds three quotes in a row, which would end the raw string" }
            "    \"$language\" to \"\"\"" + text.replace("$", "\${'$'}") + "\"\"\",\n"
        }
        val directory = output.get().asFile.resolve("cz/loplex/dogvision/web")
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("AndroidStrings.kt").writeText(
            "package cz.loplex.dogvision.web\n\n" +
                "// Written by the androidStrings task of web/build.gradle.kts from android/src/main/res.\n" +
                "internal val ANDROID_STRINGS: Map<String, String> = mapOf(\n$entries)\n",
        )
    }
}

// The strings compiled in are the app's files as they are, not code to hold to the style.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "dog-vision.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain {
            kotlin.srcDir(androidStrings)
            dependencies {
                implementation(project(":core"))
                implementation(project(":gl"))
            }
        }
    }
}
