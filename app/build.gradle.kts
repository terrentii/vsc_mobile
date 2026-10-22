plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "ru.mobile.mys"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.mobile.mys"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // WebRTC тянет ~12 МБ нативов на ABI: 32-битный x86 не нужен (эмуляторы — x86_64)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":crypto"))
    implementation(project(":p2p"))
    implementation(project(":central"))
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.0")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
