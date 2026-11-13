import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mist.connect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mist.connect.hermeslab"
        minSdk = 26
        targetSdk = 36
        versionCode = 79
        versionName = "0.28.0-phone-assistant-preview.10"
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Explicit external pinned-NDK artifact only. Termux probes MUST NOT enter the APK.
    providers.gradleProperty("duplexNativeDir").orNull?.let { directory ->
        val root = file(directory)
        val report = groovy.json.JsonSlurper().parse(root.resolve("native-build.json")) as Map<*, *>
        check(report["mode"] == "ndk-arm64-api26") { "Refuse non-NDK duplex library" }
        val library = root.resolve("jni/arm64-v8a/libhermes_duplex.so")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(library.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == report["sha256"]) { "Duplex artifact hash mismatch" }
        check(root.resolve("assets/licenses").isDirectory) { "NDK runtime notices missing" }
        sourceSets.getByName("main").jniLibs.srcDir(root.resolve("jni"))
        sourceSets.getByName("main").assets.srcDir(root.resolve("assets"))
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("com.alphacephei:vosk-android:0.3.75")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}