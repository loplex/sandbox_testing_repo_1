import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Finds and runs ffmpeg and ffprobe: on the PATH the app started with,
// where they were installed after a window started, or on Windows where a window downloaded them.
// Probes a video, reads its frames, and writes an .mp4.
// Needs no toolkit and no GPU.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    id("cz.loplex.dogvision.detekt")
    id("cz.loplex.dogvision.ktlint")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// The app's English name, app_name in texts, read from the file the way the MSI reads it,
// so that this module need not use texts.
val appName = run {
    val strings = rootProject.layout.projectDirectory.file("texts/strings/values/strings.xml")
    val path = strings.asFile.path
    providers.fileContents(strings).asText.map { text ->
        checkNotNull(Regex("""<string name="app_name">([^<]+)</string>""").find(text)) { "$path has no app_name" }
            .groupValues[1]
    }
}

// The release branch of ffmpeg's Windows builds that FfmpegDownload uses, from libs.versions.toml,
// and the app's name, which names the folder under %ProgramData% that the builds are downloaded into.
val ffmpegProperties = tasks.register<WriteProperties>("ffmpegProperties") {
    description = "Writes build/generated/ffmpeg/ffmpeg.properties, the branch of ffmpeg the windows download and " +
        "the app's name."
    destinationFile = layout.buildDirectory.file("generated/ffmpeg/ffmpeg.properties")
    property("windowsBranch", libs.versions.ffmpeg.windows)
    property("appName", appName)
}

tasks.named<ProcessResources>("jvmProcessResources") {
    from(ffmpegProperties) {
        into("cz/loplex/dogvision/ffmpeg")
    }
}
