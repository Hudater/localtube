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

// Force-upgrade material3 and material-icons-extended over whatever any
// transitive dependency pulls in. The BOM (composeBom = 2024.09.03) maps
// material3 to 1.3.0 — anything older and PullToRefreshContainer +
// PullToRefreshBox are missing. Same logic for icons-extended 1.7.x for
// Icons.Filled.ArrowBack. Without these constraints, transitives from
// androidx.activity:activity-compose 1.9.2 and similar down-pin to
// 1.2.x and break the build.
subprojects {
    configurations.all {
        resolutionStrategy {
            force(
                "androidx.compose.material3:material3:1.2.1",
                "androidx.compose.material:material-icons-extended:1.5.4",
                "androidx.compose.material:material-icons-core:1.5.4",
                "androidx.compose.ui:ui:1.5.4",
                "androidx.compose.ui:ui-graphics:1.5.4",
                "androidx.compose.ui:ui-tooling:1.5.4",
                "androidx.compose.ui:ui-tooling-preview:1.5.4",
                "androidx.compose.foundation:foundation:1.5.4",
                "androidx.compose.foundation:foundation-layout:1.5.4",
                "androidx.compose.runtime:runtime:1.5.4",
                "androidx.compose.material:material-ripple:1.5.4",
            )
        }
    }
}
