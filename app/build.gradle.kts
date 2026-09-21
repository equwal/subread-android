plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment, so the key never lives in the
// repository. CI supplies it from repository secrets; without it the release
// build is simply left unsigned.
val keystorePath = providers.environmentVariable("SUBREAD_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("SUBREAD_KEYSTORE_PASSWORD").orNull
val signingReady = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
    file(keystorePath).isFile

android {
    namespace = "space.subread.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    // No list of dependencies, encrypted for Google alone, inside the app: F-Droid
    // does not accept a part of the app that nobody else can read.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "space.subread.app"
        minSdk = 26
        targetSdk = 36
        // Plain numbers, in this file: F-Droid reads them from here to find a new release.
        versionCode = 9
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // A link to the Ko-fi page. Google Play is not given it (-PplayStore=true): its
        // rules on payments outside the store are read differently by each reviewer, and
        // a first listing is not the place to find out. F-Droid and GitHub builds have it.
        buildConfigField("boolean", "DONATE_LINK", (providers.gradleProperty("playStore").orNull != "true").toString())
        // A link to honjimaku.com, a library of subtitles that users share. Not for Google Play either.
        buildConfigField("boolean", "HONJIMAKU_LINK", (providers.gradleProperty("playStore").orNull != "true").toString())

        // Every phone worth transcribing on is 64-bit ARM; the speech library
        // is built for ARMv8.2 specifically (see src/main/cpp/CMakeLists.txt).
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    if (signingReady) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "subread"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // Not shrunk yet: R8 rules want a device to be verified against.
            isMinifyEnabled = false
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The model is copied out of the APK to a real file on first run; storing
    // it uncompressed makes that a straight copy.
    androidResources { noCompress += "bin" }

    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // The intent API decides on plain strings, so JUnit alone tests it on a laptop.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
