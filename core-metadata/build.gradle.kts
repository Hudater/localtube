// Pure Kotlin/JVM module. Parses yt-dlp .info.json sidecars into
// core-model types. No Android dependency — this parsing logic should be
// directly reusable in a future web-backend service without change.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core-model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}
