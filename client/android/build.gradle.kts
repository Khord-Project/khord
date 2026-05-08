plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "org.khord.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.khord.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Server URLs are no longer baked at build time — they're picked at
        // runtime via the ServerSetupScreen onboarding step (with a "Use
        // Khord community servers" default and "custom" override).
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.srcDirs("src/main/kotlin")
        res.srcDirs("src/main/res")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ktor 3.x ships JVM-11+ bytecode that uses post-API-26 java.time
        // shapes; core-library desugaring backports them at dex time so the
        // PoC can keep minSdk = 26 without forcing every consumer up.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/LICENSE*", "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.coroutines.android)
    // KhordJson (a Json instance) is part of :shared's public API, so the
    // android module needs the kotlinx-serialization-json dep visible at
    // compile time — :shared declares it `implementation`-scoped so it
    // doesn't transit to consumers automatically.
    implementation(libs.kotlinx.serialization.json)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Camera + permissions for QR scanning
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.accompanist.permissions)

    // SQLCipher for at-rest DB encryption + the matching SQLDelight driver
    implementation(libs.sqlcipher.android)
    implementation(libs.sqldelight.driver.android)

    // Ktor OkHttp engine for the Android HttpClient
    implementation(libs.ktor.client.okhttp)

    // Robolectric tests for Keystore round-trip (SQLCipher's native libs
    // can't load under Robolectric, so SQLCipher is verified by manual
    // on-device testing per the prompt).
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
}
