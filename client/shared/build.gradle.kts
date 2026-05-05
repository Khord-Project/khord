plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "org.khord.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // JVM target — exists primarily so commonTest can run as plain JUnit
    // unit tests (fast, no Android device or emulator required). The
    // crypto module's correctness tests (RFC 5869 vectors, X3DH parity,
    // Double Ratchet behaviour) all live in commonTest and need a working
    // libsodium native at test time. The end-to-end integration test
    // against the Docker Compose stack lives in jvmTest (network-bound,
    // gated by -Dkhord.integration=true).
    jvm()

    // iOS targets are reserved for a future ADR-009 follow-up. Left commented
    // so the project still builds without an iOS toolchain installed.
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    jvmToolchain(17)

    compilerOptions {
        // We use `expect class DriverFactory` for the per-target SQLDelight
        // driver. expect/actual classes are flagged "Beta" in 2.x even
        // though the language committee accepted them; the warning exists
        // mostly as a migration nudge.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.libsodium)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.sqlite.jdbc)
        }

        // Android driver dep wired here so the eventual Android UI module
        // pulls a real driver. Currently no Android tests exercise it.
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
    }
}

sqldelight {
    databases {
        create("KhordDatabase") {
            packageName.set("org.khord.shared.storage.db")
        }
    }
}

// Forward the khord.integration system property from the Gradle daemon JVM
// down to the forked test JVM so EndToEndIntegrationTest's gate works when
// the property is supplied via -Dkhord.integration=true on the gradle CLI.
tasks.withType<Test>().configureEach {
    System.getProperty("khord.integration")?.let { systemProperty("khord.integration", it) }
}
