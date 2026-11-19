import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signing = Properties().apply {
    rootDir.resolve("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String? = System.getenv(name) ?: signing.getProperty(name)

android {
    namespace = "io.github.quezka.tether"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.quezka.tether"
        minSdk = 28
        targetSdk = 35
        versionCode = 300
        versionName = "0.3.0"
    }
    signingConfigs {
        create("release") {
            secret("TETHER_KEYSTORE")?.let { store ->
                storeFile = file(store)
                storePassword = secret("TETHER_KEYSTORE_PASSWORD")
                keyAlias = secret("TETHER_KEY_ALIAS") ?: "tether"
                keyPassword = secret("TETHER_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        // debug = own id -> installs beside the signed release (test builds, no key on this machine)
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (secret("TETHER_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
