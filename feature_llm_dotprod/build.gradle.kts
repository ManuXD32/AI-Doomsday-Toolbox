plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.example.llamadroid.feature.llm.dotprod"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    
    buildTypes {
        release {
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":app"))
}

// Disable debug symbol extraction to avoid collisions in Bundle
tasks.configureEach {
    if (name == "extractReleaseNativeSymbolTables") {
        enabled = false
    }
}
