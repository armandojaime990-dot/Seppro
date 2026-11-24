import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seppro.wearos"
    compileSdk = 34

    defaultConfig {
        // Mismo applicationId que el teléfono: requisito de Wear OS Data Layer (Entregable 2)
        applicationId = "com.seppro.movil"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    // Misma llave que el teléfono (../firma, fuera de Git)
    val firma = rootProject.file("../firma/keystore.properties")
    signingConfigs {
        if (firma.exists()) {
            val p = Properties().apply { firma.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file("../firma/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    // Wear OS UI
    implementation("androidx.wear:wear-input:1.1.0")
}
