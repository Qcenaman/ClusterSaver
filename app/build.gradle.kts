plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.qcenaman.clustersaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qcenaman.clustersaver"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "clustersaver"
            keyAlias = "clustersaver"
            keyPassword = "clustersaver"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
