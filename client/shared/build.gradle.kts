plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    androidLibrary {
        namespace = "org.khord.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvmToolchain(17)

    // iOS targets are reserved for a future ADR-009 follow-up. Left commented
    // so the project still builds without an iOS toolchain installed.
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Shared crypto/protocol dependencies (e.g. lazysodium-android)
            // will be declared here as Phase 3 implementation lands.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
