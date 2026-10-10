plugins {
    kotlin("jvm")
    id("dev.detekt")
}

dependencies {
    implementation(project(":lib"))
}
