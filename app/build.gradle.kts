plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "dev.hudater.localtube"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hudater.localtube"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing is optional locally — if the env vars below are
    // unset (normal local `assembleRelease` run), Gradle falls back to
    // the debug signing config so the build still succeeds unsigned-for-
    // release-store purposes. CI supplies these via GitHub Actions
    // secrets — see .github/workflows/build-and-release.yml and
    // docs/BUILD.md, "Release signing".
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigningConfig = listOf(
        releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // personal app, single user — not worth the ProGuard debugging cost yet
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-metadata"))
    implementation(project(":core-filesystem"))
    implementation(project(":core-data"))
    implementation(project(":core-player"))

    // DEBUG: print resolved compose versions at configuration time
    configurations.named("debugCompileClasspath").configure {
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("androidx.compose")) {
                println("RESOLVED: ${requested.group}:${requested.name} -> ${useTarget.toString()}")
            }
        }
    }

    // Explicit version overrides: the BOM platform() import is being
    // shadowed by transitives from activity-compose/lifecycle/navigation
    // and resolutionStrategy.force isn't winning. Direct versions on
    // these two artifacts sidestep the conflict entirely.
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.compose.material:material-icons-core:1.7.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-graphics:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.foundation:foundation-layout:1.7.5")
    implementation("androidx.compose.runtime:runtime:1.7.5")
    implementation("androidx.compose.material:material-ripple:1.7.5")

    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.coil.compose)
    implementation(libs.coil)
    implementation(libs.smbj)
    implementation(libs.androidx.room.runtime)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// DEBUG: dump resolved compose versions
tasks.register("dumpComposeVersions") {
    doLast {
        configurations.getByName("debugCompileClasspath").resolvedConfiguration
            .resolvedDependencies
            .filter { it.moduleGroup.startsWith("androidx.compose") }
            .sortedBy { "${it.moduleGroup}:${it.moduleName}" }
            .forEach { dep ->
                println("RESOLVED ${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}")
            }
    }
}
