plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.example.llamadroid.feature.upscaler"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    // Explicitly configure splitting
    // This allows the module to be downloaded on demand
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.core.ktx)
}
