plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tama-world-policy"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
