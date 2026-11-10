plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.summit.sync"
    compileSdk = 36
    defaultConfig {
        applicationId = "ro.summit.sync"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.0-local"
    }
    val ks = System.getenv("KS_FILE")
    signingConfigs {
        if (ks != null) create("rel") {
            storeFile = file(ks)
            storePassword = System.getenv("KS_PASS")
            keyAlias = "summit"
            keyPassword = System.getenv("KS_PASS")
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks != null) signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
