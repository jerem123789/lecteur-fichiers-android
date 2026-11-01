plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.jeremyai.lecteur"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.jeremyai.lecteur"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Le site du lecteur est embarqué tel quel : l'app ne touche jamais au réseau.
    sourceSets["main"].assets.srcDirs("../site-source")
    androidResources { noCompress += listOf("wasm") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
