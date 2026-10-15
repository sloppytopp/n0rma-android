plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.sloppytopp.homewatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.sloppytopp.homewatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0-beta1"
        buildConfigField("boolean", "SDR", "false")
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -PdebugSigned=true builds a release-type APK signed with the debug key, only for testing minified code on a dev phone
            signingConfig = if (project.hasProperty("debugSigned")) signingConfigs.getByName("debug")
                            else if (keystoreProps.getProperty("storeFile") != null) signingConfigs.getByName("release") else null
        }
    }
    // Optional SDR build (./gradlew assembleSdr): same app plus the Mic tab (RTL-SDR hidden-microphone sweep). It is a separate app
    // (applicationId suffix ".sdr") because it needs the INTERNET permission, only to reach the RTL-SDR driver app on 127.0.0.1.
    // The standard build never has that permission and has no Mic tab.
    buildTypes.create("sdr") {
        initWith(buildTypes.getByName("release"))
        matchingFallbacks += "release"
        applicationIdSuffix = ".sdr"
        versionNameSuffix = "-sdr"
        buildConfigField("boolean", "SDR", "true")
    }
    // F-Droid: no Google-signed dependency blob inside the APK/bundle
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
