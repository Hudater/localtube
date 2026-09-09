// Top-level build file. Plugin versions are declared here (once) and
// applied without a version in each module's build.gradle.kts.
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Pin the whole Compose dependency graph to one consistent set
// (material3 1.2.1 + the rest of Compose at 1.5.4). Mixed Compose versions
// fail dexing with "Duplicate class" because classes moved between
// artifacts across versions (e.g. ExperimentalComposeUiApi: ui 1.5.4 vs
// ui-util 1.7.0). eachDependency by group covers artifacts a per-artifact
// force() list would miss.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "androidx.compose.material3" && requested.name == "material3" ->
                    useVersion("1.2.1")
                requested.group == "androidx.compose.ui" ||
                    requested.group == "androidx.compose.foundation" ||
                    requested.group == "androidx.compose.runtime" ||
                    requested.group == "androidx.compose.animation" ||
                    requested.group == "androidx.compose.material" ->
                    useVersion("1.5.4")
            }
        }
    }
}
