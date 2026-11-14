plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parktrace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parktrace"
        // Pleos Connect requires minSdk >= 28 (signing key rotation / lineage requirement).
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Static page that renders the parking record on the phone (see /web/index.html).
        // Replace with your GitHub Pages URL after deploying /web.
        buildConfigField("String", "FIND_PAGE_URL", "\"https://YOUR_GITHUB_ID.github.io/parktrace/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Contest submission: sign with your own release keystore (Build > Generate Signed APK).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Pleos Connect SDK (versions as documented on document.pleos.ai)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // spoken confirmation only ("B2 C-15에 저장했어요")

    // Camera recognition is a future-plan item: no camera / OCR dependency in this build.
    // Sign text is simulated (sdk/SimulatedSignReader).

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // QR code generation for the car -> phone handoff (no server needed)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
