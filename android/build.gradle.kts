plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cz.loplex.dogvision"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.loplex.dogvision"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The languages the app speaks, from its values-* folders, for the system's per-app language setting.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
