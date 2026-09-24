import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.madtreasures.g2direct"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.madtreasures.g2direct"
        // Wear OS 3 (API 30) and newer. The Pixel Watch 5 runs a much newer release;
        // the older API paths are kept only for the legacy BLE permission model.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The touchpad gesture test runs the UI under Robolectric; the first run downloads its Android
// runtime jar (~100 MB). Rewriting docs/screenshots only happens on request:
// ./gradlew :app:testDebugUnitTest -Pscreenshots
tasks.withType<Test>().configureEach {
    // Robolectric's SDK 36 runtime needs this on JDK 17+.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    (project.findProperty("robolectricRepo") as String?)?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
    if (project.hasProperty("screenshots")) {
        systemProperty("screenshotDir", rootProject.file("docs/screenshots").absolutePath)
    } else {
        exclude("**/*ScreenshotTest*")
    }
}
