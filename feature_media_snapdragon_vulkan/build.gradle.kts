plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.example.llamadroid.feature.media.snapdragon.vulkan"
    compileSdk = 36

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

tasks.configureEach {
    if (name == "extractReleaseNativeSymbolTables") {
        enabled = false
    }
}
