plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dotmusic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dotmusic.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the workflow run number, so every published build is newer than the last one.
        versionCode = System.getenv("DOT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("DOT_VERSION_NAME") ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // One fixed key, from CI secrets. CI used to sign with a brand-new debug key every run,
            // so Android saw a different app each time and refused to install over the old one.
            System.getenv("DOT_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("DOT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DOT_KEY_ALIAS") ?: "dot"
                keyPassword = System.getenv("DOT_KEY_PASSWORD") ?: System.getenv("DOT_KEYSTORE_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key every build, so an update installs in place. Local builds without the key
            // fall back to the debug key.
            signingConfig = if (!System.getenv("DOT_KEYSTORE_PATH").isNullOrBlank()) {
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
