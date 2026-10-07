plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.taskmesh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taskmesh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // Version must stay in sync with SPEC §13 (all components report 0.1.0).
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Unsigned release build: no signingConfig is configured by design.
            // Release APKs must be signed before distribution (see README.md).
            // Minification is intentionally disabled for the 0.1.0 release so the
            // artifact matches sources 1:1; proguard-rules.pro still ships
            // kotlinx-serialization keep rules for when it gets enabled.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM pins consistent Compose artifact versions (ui 1.7.3, material3 1.3.0).
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // Core icon set (Add/Refresh/Settings/...); explicitly declared because the
    // app references androidx.compose.material.icons directly.
    implementation(libs.compose.material.icons.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Networking: Retrofit + kotlinx-serialization converter over OkHttp.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests: pure JVM (DTOs, interceptor, repository against MockWebServer,
    // notification dedup logic, state mapping). No instrumented tests in 0.1.0.
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
