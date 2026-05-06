import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.curax.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.curax.app"
        minSdk = 24
        targetSdk = 33
        versionCode = 7
        versionName = "1.0.6"

        // Vercel databus URL + Ably subscribe key: curax_app/android/local.properties (gitignored), or env vars for CI.
        val localProps = Properties()
        val lp = rootProject.file("local.properties")
        if (lp.exists()) lp.inputStream().use { localProps.load(it) }
        val databusUrl = (
            localProps.getProperty("databus.public.url")
                ?: System.getenv("DATABUS_PUBLIC_URL")
                ?: ""
            ).trim()
        val ablySubscribe = (
            localProps.getProperty("databus.ably.subscribe.key")
                ?: System.getenv("DATABUS_ABLY_SUBSCRIBE_KEY")
                ?: ""
            ).trim()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DATABUS_PUBLIC_URL", "\"${esc(databusUrl)}\"")
        buildConfigField("String", "DATABUS_ABLY_SUBSCRIBE_KEY", "\"${esc(ablySubscribe)}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Data bus on Vercel: subscribe to Ably channels (HTTP notify publishes via serverless).
    implementation("io.ably:ably-android:1.2.39")
    // Firebase Cloud Messaging - receive alerts when app is closed (like WhatsApp)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    // Sign-up: Google + Facebook profile (email / name) to prefill registration form
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.facebook.android:facebook-login:18.0.1")
}
