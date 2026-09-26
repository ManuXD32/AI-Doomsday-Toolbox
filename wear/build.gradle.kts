plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

val sharedPhoneAssets = rootProject.file("app/src/main/assets")
val wearAssetStaging = layout.buildDirectory.dir("generated/wearAssets")
val syncWearAssets = tasks.register<Sync>("syncWearAssets") {
    into(wearAssetStaging)

    // Keep the complete pre-existing phone asset tree. The new world and animation
    // trees are staged separately below so the watch receives only lightweight
    // poster frames from the animation tree.
    from(sharedPhoneAssets) {
        exclude("tama/world/**", "tama/animations/**")
    }
    from(sharedPhoneAssets) {
        include("tama/animations/frames/**/*_0.png")
    }
}

android {
    namespace = "com.example.llamadroid.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.manuxd32.aidoomsdaytoolbox"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("WEAR_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore
        }
        create("release") {
            // Keystore path - set via environment or use default location
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: System.getProperty("user.home") + "/.android/aidoomsdaytoolbox-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            // Use release signing for Play Store
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    sourceSets["main"].assets.setSrcDirs(
        listOf(
            layout.projectDirectory.dir("src/main/assets"),
            wearAssetStaging
        )
    )
}

tasks.named("preBuild").configure {
    dependsOn(syncWearAssets)
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:${libs.versions.serialization.get()}"
    )
}

dependencies {
    implementation(project(":wear-protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.ui.tooling)
}
