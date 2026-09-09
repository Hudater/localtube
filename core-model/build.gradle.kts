// Pure Kotlin/JVM module — deliberately has NO Android Gradle plugin applied.
// These are plain data classes with zero framework dependency, so this
// module can be copy-pasted into a KMP shared module, a Ktor server, or
// any other Kotlin target without modification.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // No dependencies on purpose. If you feel the urge to add one here,
    // that's a signal the type you're adding doesn't belong in this module.
}

kotlin {
    jvmToolchain(17)
}
