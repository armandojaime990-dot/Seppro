import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    jacoco // Práctica 11: cobertura de pruebas
}

jacoco {
    toolVersion = "0.8.12" // soporta clases de Java 23 (el 0.8.8 de AGP 8.2 no)
}

android {
    namespace = "com.seppro.movil"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seppro.movil"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    // Firma del APK de release. La llave vive en
    // ../firma (fuera de Git). Sin ella, release se firma con la llave de depuración.
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
        debug {
            // Práctica 11: cobertura de las pruebas unitarias con JaCoCo
            // Reporte: ./gradlew createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
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

    // JaCoCo 0.8.12 entiende las clases de Java 21-23 (el 0.8.8 de AGP 8.2 no)
    testCoverage {
        jacocoVersion = "0.8.12"
    }

    lint {
        // Prácticas 13 y 14: el CI falla si lint encuentra errores (no solo advertencias)
        abortOnError = true
        warningsAsErrors = false
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        // Las clases de Android (Log, Handler) devuelven valores por defecto en las pruebas JVM
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Últimas versiones estables compatibles con compileSdk 34 y AGP 8.2
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.android.material:material:1.12.0")
    // HTTP (PATCH, subida de archivos) y WebSocket de Supabase Realtime (Práctica 7)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Entregable 2: ViewModel + StateFlow y corrutinas
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Práctica 11: pruebas unitarias de caja blanca
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303") // org.json real (en Android es un stub en las pruebas JVM)
    // Prácticas 13 y 14: pruebas del ViewModel (StateFlow) con corrutinas controladas
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

