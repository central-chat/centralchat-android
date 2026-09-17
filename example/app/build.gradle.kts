plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * One screen that asks for a key and opens the chat.
 *
 * It depends on the library and on nothing else — no appcompat, no Compose, no
 * view binding. That is deliberate: what an integrator has to add to their own
 * app should be visible here, and a dependency in this file that the library
 * does not need reads as one that it does.
 */
android {
    namespace = "chat.central.widget.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "chat.central.widget.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // On in the example on purpose: it is what proves the library's
            // consumer ProGuard rules actually keep the bridge's method names.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("chat.central:centralchat:0.2.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
