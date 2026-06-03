import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    // Enables `@Serializable` in android module sources (used by
    // BugReporter to encode the Report DTO). The shared module
    // applied this plugin already; android picked it up
    // transitively for runtime but not for compile-time codegen.
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "org.khord.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.khord.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 23
        // versionName must match the GitHub release tag (minus the leading
        // `v`) so UpdateChecker's compare doesn't false-positive. Bump
        // this when cutting a new release tag.
        versionName = "0.1.0-beta.2"
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

    // Release signing config — driven by a `release-keystore.properties`
    // file at the client/ root (see release-keystore.properties.example).
    // When the file is absent (e.g. a clean clone with no local keystore
    // and no CI secrets), the config is left null and a release build
    // falls back to debug-signing — useful for local smoke-tests, but
    // the produced APK is NOT shippable. CI populates the properties +
    // keystore file from GitHub Secrets.
    signingConfigs {
        create("release") {
            val props = rootProject.file("release-keystore.properties")
            if (props.exists()) {
                // Explicit step-by-step to avoid Kotlin DSL receiver-
                // scoping confusion: `p["..."]` clashes with Gradle's
                // ExtensionContainer.get, and `apply { load(...) }`
                // resolves the inner `load` against SigningConfig
                // instead of Properties.
                val p = Properties()
                props.reader().use { reader -> p.load(reader) }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Use the release signing config when its file is present.
            // If the keystore file referenced by the properties is
            // missing (storeFile is null), AGP transparently falls back
            // to the debug signing config for local convenience.
            signingConfig = signingConfigs.getByName("release")
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

    // F-Droid prefers APKs without the Play-style signed dependency
    // metadata block (the AGP-injected "dependency info" blob). Turn
    // it off for both APK and bundle so the F-Droid build matches and
    // there's no opaque proprietary metadata embedded. See
    // fastlane/FDROID_NOTES.md.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

// Name output APKs by version so release artifacts are self-describing:
//   prod    -> khord-0.1.0-beta.2.apk
//   dev     -> khord-0.1.0-beta.2-dev.apk
// With AGP's new DSL (android.newDsl=true, the default since AGP 9), the
// legacy `applicationVariants` hook is gone — the `android {}` accessor is
// an ApplicationExtension that doesn't expose it. The supported replacement
// is the androidComponents Variant API. `outputFileName` isn't on the public
// VariantOutput interface, so we cast to the impl (the established hook for
// renaming APK outputs). The variant doesn't surface a resolved versionName,
// so we rebuild it from defaultConfig plus the flavor's "-dev" suffix to keep
// the two flavors distinct.
val baseVersionName = android.defaultConfig.versionName ?: "unknown"
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.name.startsWith("dev")) "-dev" else ""
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set("khord-$baseVersionName$suffix.apk")
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
