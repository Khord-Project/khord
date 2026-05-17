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
        // Default server URLs are picked at compile time per flavor (see
        // productFlavors below) and exposed via BuildConfig. The user can
        // still override either with "Use custom servers" at runtime via
        // the ServerSetupScreen onboarding step.
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

    // Two flavors so dev (emulator host loopback) and prod (khord.org) can
    // both be installed side-by-side on the same device via the .dev
    // applicationIdSuffix — useful when debugging interop between the two
    // environments simultaneously.
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // 10.0.2.2 is the Android emulator's loopback to the host
            // running `docker compose up` — keyserver on host 8001,
            // relayserver on host 8002 (see khord_local_dev memory).
            buildConfigField("String", "DEFAULT_KEY_SERVER", "\"http://10.0.2.2:8001\"")
            buildConfigField("String", "DEFAULT_RELAY_SERVER", "\"http://10.0.2.2:8002\"")
            buildConfigField("Boolean", "IS_DEV_FLAVOR", "true")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "DEFAULT_KEY_SERVER", "\"https://keys.khord.org\"")
            buildConfigField("String", "DEFAULT_RELAY_SERVER", "\"https://relay.khord.org\"")
            buildConfigField("Boolean", "IS_DEV_FLAVOR", "false")
        }
    }

    buildFeatures {
        compose = true
        // Re-enabled so the per-flavor DEFAULT_KEY_SERVER / DEFAULT_RELAY_SERVER
        // / IS_DEV_FLAVOR constants land in the generated BuildConfig class.
        buildConfig = true
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
