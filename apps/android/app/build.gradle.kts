import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Downloads a file once and checks it against a pinned hash, so a build never ships a model that changed under the same URL. */
abstract class FetchPinned : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @TaskAction fun fetch() {
        val file = target.get().asFile
        if (file.exists() && digest(file) == sha256.get()) return
        file.parentFile.mkdirs()
        URI(url.get()).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        if (digest(file) != sha256.get()) { file.delete(); throw GradleException("${url.get()} does not match its pinned SHA-256") }
    }
}

// Untracked local.properties (apps/android/local.properties) holds the Wearables Developer Center credentials.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.lensqi.fieldops"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lensqi.fieldops"
        minSdk = 31 // Meta Wearables DAT 1.0.1 requires API 31 (was 26)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The emulator reaches the host's loopback at 10.0.2.2; a phone on the LAN overrides this in Settings.
        buildConfigField("String", "DEFAULT_API_BASE", "\"http://10.0.2.2:5199/\"")
        // Meta Wearables DAT attestation. Empty unless set in local.properties; never commit real values.
        manifestPlaceholders["mwdat_application_id"] = localProperties.getProperty("mwdat_application_id", "")
        manifestPlaceholders["mwdat_client_token"] = localProperties.getProperty("mwdat_client_token", "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The hand model is read straight from the APK.
    androidResources { noCompress += "task" }
    // Finger pointing ships for 64-bit phones and emulators; elsewhere its detector reports itself unavailable and the app carries on.
    packaging { jniLibs { excludes += setOf("lib/x86/libmediapipe_tasks_jni.so", "lib/armeabi-v7a/libmediapipe_tasks_jni.so") } }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Contract tests decode the JSON recorded from the real API by scripts/record_api.py.
            it.systemProperty("recordings.dir", rootProject.file("../../fixtures/api-recordings").absolutePath)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.video)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    // Meta Wearables Device Access Toolkit (Ray-Ban Meta glasses). The Mock Device Kit is debug-only: release builds
    // get the no-op MockGlasses from src/release and can never produce "MockWearables" evidence.
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    implementation(libs.mwdat.inputs)
    debugImplementation(libs.mwdat.mockdevice)
    implementation(libs.androidx.exifinterface)
    implementation(libs.google.ar.core)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// MediaPipe's Hand Landmarker bundle (Apache 2.0, 7.8 MB). Fetched rather than committed; see docs/ANDROID_HAND_POINTING.md.
val fetchHandModel by tasks.registering(FetchPinned::class) {
    url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    sha256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
    target = layout.projectDirectory.file("src/main/assets/models/hand_landmarker.task")
}
tasks.named("preBuild") { dependsOn(fetchHandModel) }

// MediaPipe's own test photos, for the instrumented check of the detector on real hands. Never committed.
listOf(
    "pointing_up" to "ecf8ca2611d08fa25948a4fc10710af9120e88243a54da6356bacea17ff3e36e",
    "thumb_up" to "5d673c081ab13b8a1812269ff57047066f9c33c07db5f4178089e8cb3fdc0291",
    "victory" to "84cb8853e3df614e0cb5c93a25e3e2f38ea5e4f92fd428ee7d867ed3479d5764",
    "fist" to "43fa1cabf3f90d574accc9a56986e2ee48638ce59fc65af1846487f73bb2ef24",
    "cats_and_dogs" to "a2eaa7ad3a1aae4e623dd362a5f737e8a88d122597ecd1a02b3e1444db56df9c",
).forEach { (name, hash) ->
    val fetch = tasks.register<FetchPinned>("fetchHandPhoto_$name") {
        url = "https://storage.googleapis.com/mediapipe-assets/$name.jpg"
        sha256 = hash
        target = layout.projectDirectory.file("src/androidTest/assets/hands/$name.jpg")
    }
    tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(fetch) }
}
