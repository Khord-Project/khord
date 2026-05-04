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

    // JVM target — exists primarily so commonTest can run as plain JUnit
    // unit tests (fast, no Android device or emulator required). The
    // crypto module's correctness tests (RFC 5869 vectors, X3DH parity,
    // Double Ratchet behaviour) all live in commonTest and need a working
    // libsodium native at test time.
    jvm()

    // iOS targets are reserved for a future ADR-009 follow-up. Left commented
    // so the project still builds without an iOS toolchain installed.
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.libsodium)
            implementation(libs.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
