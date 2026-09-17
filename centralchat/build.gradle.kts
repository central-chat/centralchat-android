plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

/**
 * chat.central:centralchat — the Android chat library.
 *
 * Two dependencies, and neither is optional:
 *
 *  - androidx.activity  ComponentActivity + ActivityResultRegistry, which is how
 *                       the file chooser and the runtime permissions work
 *  - androidx.core      the window-insets API that keeps the composer clear of
 *                       the navigation bar and the keyboard
 *
 * The keystore the session lives in is written against the platform's own
 * Keystore API rather than androidx.security:security-crypto — see
 * SecureStorage.kt for why a fourth dependency was not worth it.
 *
 * Everything else the chat needs — the sync loop, the encryption engine, the
 * composer, the attachment pipeline — is the page, not this library.
 */
group = "chat.central"
version = project.findProperty("centralchat.version") as String? ?: "0.0.1"

android {
    namespace = "chat.central.widget"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // The LOWEST versions whose API this library actually calls, never the
    // newest. Gradle resolves a dependency to the highest version in the graph,
    // so a floor declared here is a floor forced on every integrator: pinning
    // the current release would drag an app on an older AndroidX forward, and
    // `activity` 1.9 alone would require compileSdk 34 of them. An app already
    // on something newer is unaffected — AndroidX is binary-compatible within a
    // major, so theirs wins and this still links.
    //
    //   activity 1.2.0  ComponentActivity, registerForActivityResult,
    //                   ActivityResultContracts, onBackPressedDispatcher
    //   core     1.5.0  WindowCompat.setDecorFitsSystemWindows,
    //                   WindowInsetsCompat.Type.ime(), updatePadding
    implementation("androidx.activity:activity-ktx:1.2.0")
    implementation("androidx.core:core-ktx:1.5.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "chat.central"
            artifactId = "centralchat"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        // A DIRECTORY, not a server. A Maven repository is a file layout, so
        // publishing into one and copying it onto the static bucket is the
        // whole of "we have a Maven repo" — publish-maven.sh does the copy.
        //
        // Cumulative on purpose: every version ever published stays, because an
        // app pinning 0.1.0 must keep resolving after 0.2.0 ships.
        maven {
            name = "cdn"
            url = uri(layout.buildDirectory.dir("maven"))
        }
    }
}
