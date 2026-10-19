plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Release signing: path + passwords come from ~/.gradle/gradle.properties or environment
 * variables — never from this repository.
 *   LISTENSTAT_KEYSTORE, LISTENSTAT_KEYSTORE_PASSWORD, LISTENSTAT_KEY_ALIAS, LISTENSTAT_KEY_PASSWORD
 * If absent, release builds fall back to the local debug key (with a warning); such an APK
 * will NOT upgrade an installation signed with the project key.
 */
fun signingValue(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystore: File? = signingValue("LISTENSTAT_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.dongzhongcen.listenstat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dongzhongcen.listenstat"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.3.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("LISTENSTAT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("LISTENSTAT_KEY_ALIAS")
                keyPassword = signingValue("LISTENSTAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lab testing against http://IP; release stays HTTPS-only.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("ListenStat: LISTENSTAT_KEYSTORE not set — release is signed with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
