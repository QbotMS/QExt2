plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

val gateUrl = (localProps.getProperty("QEXT_GATE_URL")
    ?: "https://qbot.cytr.us/gate/open")
    .replace("\"", "\\\"")
val gateToken = (localProps.getProperty("QEXT_GATE_TOKEN") ?: "").replace("\"", "\\\"")
val readinessUrl = (localProps.getProperty("QEXT_READINESS_URL")
    ?: "https://qbot.cytr.us/ride-readiness")
    .replace("\"", "\\\"")
val owmApiKey = (localProps.getProperty("OPENWEATHER_API_KEY") ?: "")
    .replace("\"", "\\\"")
val owmBaseUrl = (localProps.getProperty("OPENWEATHER_BASE_URL")
    ?: "https://api.openweathermap.org/data/2.5/weather")
    .replace("\"", "\\\"")
val weatherLatStr = (localProps.getProperty("WEATHER_LAT") ?: "")
val weatherLonStr = (localProps.getProperty("WEATHER_LON") ?: "")

android {
    namespace = "com.qext2.primary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qext2.primary"
        minSdk = 23
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        versionName = "0.2.0-tuscany"
        buildConfigField("String", "QEXT_GATE_URL", "\"$gateUrl\"")
        buildConfigField("String", "QEXT_GATE_TOKEN", "\"$gateToken\"")
        buildConfigField("String", "QEXT_READINESS_URL", "\"$readinessUrl\"")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"$owmApiKey\"")
        buildConfigField("String", "OPENWEATHER_BASE_URL", "\"$owmBaseUrl\"")
        buildConfigField("String", "WEATHER_LAT", "\"$weatherLatStr\"")
        buildConfigField("String", "WEATHER_LON", "\"$weatherLonStr\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
