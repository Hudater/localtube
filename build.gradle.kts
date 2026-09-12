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

// Pin the whole Compose dependency graph to one consistent set:
// material3 1.3.0 + the rest of Compose at 1.7.5. The Kotlin Compose
// compiler plugin (2.0.20) generates code for the 1.7.x runtime slot-table
// format — running app code against an older runtime (1.5.4/1.6.1) crashes
// at first composition with ComposeRuntimeError "Unexpected anchor value".
// eachDependency by group covers artifacts a per-artifact force() list
// would miss.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "androidx.compose.material3" && requested.name == "material3" ->
                    useVersion("1.3.0")
                requested.group == "androidx.compose.ui" ||
                    requested.group == "androidx.compose.foundation" ||
                    requested.group == "androidx.compose.runtime" ||
                    requested.group == "androidx.compose.animation" ||
                    requested.group == "androidx.compose.material" ->
                    useVersion("1.7.5")
            }
        }
    }
}
