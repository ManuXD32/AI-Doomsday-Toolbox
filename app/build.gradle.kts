plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    id("kotlin-parcelize")
}

val isFatApkBuild = providers.gradleProperty("fatApkBuild")
    .map(String::toBoolean)
    .orElse(false)

val parquetVersion = "1.15.2"
val parquetHadoopAndroid by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val strippedParquetHadoopJar by tasks.registering(Jar::class) {
    archiveBaseName.set("parquet-hadoop-android")
    archiveVersion.set(parquetVersion)
    destinationDirectory.set(layout.buildDirectory.dir("generated/parquet"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        val sourceJar = parquetHadoopAndroid.incoming.files.files.single { file ->
            file.name == "parquet-hadoop-$parquetVersion.jar"
        }
        zipTree(sourceJar)
    }) {
        // parquet-column carries the complete shaded fastutil package. Removing the partial
        // copy from parquet-hadoop avoids Android release duplicate-class failures.
        exclude("shaded/parquet/it/unimi/dsi/fastutil/**")
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.serialization.get()}",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:${libs.versions.serialization.get()}"
    )
}

android {
    namespace = "com.example.llamadroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manuxd32.aidoomsdaytoolbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 946
        versionName = "0.946"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("boolean", "IS_FAT_APK_BUILD", isFatApkBuild.get().toString())
        
        // Limit to arm64 only (CPU features detection uses ARM-specific headers)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude version files that conflict between dynamic feature modules
            excludes += "META-INF/*.version"
            excludes += "META-INF/versions/**"
        }
        jniLibs {
            useLegacyPackaging = true
            // LiteRT-LM GPU should mirror Gallery's native surface. Keep retired QNN/NPU
            // experiments out of the base APK/AAB so their LiteRT dispatch plugins cannot be
            // discovered during ordinary CPU/GPU Engine initialization.
            excludes += setOf(
                "**/libLiteRtCompilerPlugin_Qualcomm.so",
                "**/libLiteRtDispatch_Qualcomm.so",
                "**/libQnn*.so"
            )
        }
    }
    
    // NDK for CPU features detection
    ndkVersion = "29.0.14206865"
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // Asset Packs for on-demand native binary delivery
    assetPacks += setOf(
        ":asset_upscaler"
    )
    
    // Dynamic Features for native binaries execution (Install-Time/On-Demand)
    dynamicFeatures += setOf(
        ":feature_llm_baseline", ":feature_llm_dotprod", ":feature_llm_armv9",
        ":feature_llm_snapdragon_opencl",
        ":feature_kiwix_baseline", ":feature_kiwix_dotprod", ":feature_kiwix_armv9",
        ":feature_media_baseline", ":feature_media_dotprod", ":feature_media_armv9",
        ":feature_upscaler"
    )
}

val tamaDialogWorkbook = layout.projectDirectory.file("src/main/tama-dialogs/pet_dialogs.xlsx")
val tamaDialogGeneratedAssets = layout.buildDirectory.dir("generated/assets/tamaDialogs")
val tamaDialogGeneratedJson = tamaDialogGeneratedAssets.map { it.file("tama/dialogs/pet_dialogs.json") }

val generateTamaDialogCatalog by tasks.registering(Exec::class) {
    inputs.file(tamaDialogWorkbook)
    outputs.file(tamaDialogGeneratedJson)
    commandLine(
        "python3",
        rootProject.file("tools/tama_dialog_excel.py").absolutePath,
        "--workbook",
        tamaDialogWorkbook.asFile.absolutePath,
        "--output",
        tamaDialogGeneratedJson.get().asFile.absolutePath
    )
}

android.sourceSets["main"].assets.srcDir(tamaDialogGeneratedAssets)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateTamaDialogCatalog)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)
    
    // Document file support for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // Play Asset Delivery for on-demand native binaries
    implementation("com.google.android.play:asset-delivery:2.2.2")
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // Image Loading
    implementation(libs.coil.compose)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Parquet dataset import for Hugging Face-style training shards
    add("parquetHadoopAndroid", "org.apache.parquet:parquet-hadoop:$parquetVersion")
    implementation(files(strippedParquetHadoopJar))
    implementation("org.apache.parquet:parquet-column:$parquetVersion")
    implementation("org.apache.parquet:parquet-format-structures:$parquetVersion")
    implementation("org.apache.parquet:parquet-common:$parquetVersion")
    implementation("org.apache.parquet:parquet-jackson:$parquetVersion")
    implementation("org.xerial.snappy:snappy-java:1.1.10.7")
    implementation("io.airlift:aircompressor:2.0.2")
    implementation("commons-pool:commons-pool:1.6")
    implementation("com.github.luben:zstd-jni:1.5.6-6")
    implementation("org.apache.hadoop:hadoop-client-api:3.4.1")
    implementation("org.apache.hadoop:hadoop-client-runtime:3.4.1")

    // Official ONNX Runtime Android backend for local ONNX execution
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    // LiteRT-LM chat backend for CPU/GPU packaged models
    runtimeOnly("com.google.ai.edge.litertlm:litertlm-android:0.12.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    
    // Apache Commons Compress for tar extraction (handles hardlinks)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    
    // PDF
    implementation(libs.pdfbox)
    
    // ML Kit Text Recognition for OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    
    // Model Sharing - HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")
    
    // SSH client (Termux integration)
    implementation("com.jcraft:jsch:0.1.55")
    
    // Play Feature Delivery for dynamic modules
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")

    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    
    // Retrofit with kotlinx.serialization
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
}

val releaseFeatureSizeLimitBytes = 190L * 1024L * 1024L
val releaseFeatureSizeExtensions = setOf("apk", "aab", "so")
val releaseFeatureSizeScanRoots = listOf(
    "outputs",
    "intermediates/merged_native_libs/release",
    "intermediates/stripped_native_libs/release"
)
val releaseFeatureModules = listOf(
    ":feature_llm_baseline",
    ":feature_llm_dotprod",
    ":feature_llm_armv9",
    ":feature_llm_snapdragon_opencl",
    ":feature_kiwix_baseline",
    ":feature_kiwix_dotprod",
    ":feature_kiwix_armv9",
    ":feature_media_baseline",
    ":feature_media_dotprod",
    ":feature_media_armv9",
    ":feature_upscaler"
)

tasks.register("checkReleaseFeatureSplitSizes") {
    group = "verification"
    description = "Fails release verification if a dynamic feature split exceeds 190 MiB."
    mustRunAfter("bundleRelease")

    doLast {
        val oversized = releaseFeatureModules.flatMap { path ->
            val buildDir = project(path).layout.buildDirectory.asFile.get()
            releaseFeatureSizeScanRoots
                .map { relativePath -> buildDir.resolve(relativePath) }
                .filter { root -> root.exists() }
                .flatMap { root ->
                    root.walkTopDown()
                        .filter { file ->
                            file.isFile &&
                                file.extension in releaseFeatureSizeExtensions &&
                                file.length() > releaseFeatureSizeLimitBytes
                        }
                        .map { file -> path to file }
                        .toList()
                }
        }

        if (oversized.isNotEmpty()) {
            val details = oversized.joinToString("\n") { (module, file) ->
                "$module ${file.name} ${file.length() / (1024L * 1024L)} MiB"
            }
            throw org.gradle.api.GradleException(
                "Dynamic feature payloads must stay below 190 MiB:\n$details"
            )
        }
    }
}

tasks.register<Exec>("repackReleaseBundleForSigning") {
    group = "verification"
    description = "Compresses the release AAB and removes bulky upload-only metadata before signing."
    dependsOn("shrinkBundleReleaseResources")
    val intermediaryBundle = layout.buildDirectory.file(
        "intermediates/intermediary_bundle/release/shrinkBundleReleaseResources/intermediary-bundle.aab"
    )
    inputs.file(intermediaryBundle)
    outputs.file(intermediaryBundle)
    outputs.upToDateWhen { false }
    commandLine(
        "python3",
        rootProject.file("tools/repack_aab_for_release.py").absolutePath,
        intermediaryBundle.get().asFile.absolutePath,
        intermediaryBundle.get().asFile.absolutePath
    )
}

tasks.register<Exec>("checkReleaseBundle16KbAlignment") {
    group = "verification"
    description = "Fails release verification if any arm64 native library in the release bundle is not 16 KB page-size compatible."
    mustRunAfter("bundleRelease")
    commandLine(
        "python3",
        rootProject.file("tools/check_aab_16kb_alignment.py").absolutePath,
        layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile.absolutePath
    )
}
tasks.matching { it.name == "signReleaseBundle" }.configureEach {
    dependsOn("repackReleaseBundleForSigning")
}
tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy("checkReleaseFeatureSplitSizes", "checkReleaseBundle16KbAlignment")
}
