plugins {
    alias(libs.plugins.androidApplication)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":shared"))
}
