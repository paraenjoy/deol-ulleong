import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 서명 정보는 저장소 밖 파일에서 읽는다(기본: ~/.motioncare/keystore.properties).
// 파일이 없으면 release는 서명 없이 만들어진다.
val keystorePropsFile = file(
    System.getenv("DEOL_KEYSTORE_PROPS")
        ?: "${System.getProperty("user.home")}/.motioncare/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.motioncare.deolulleong"
    compileSdk = 35

    // AAOS Car API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.motioncare.deolulleong"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Pleos Vehicle SDK (Gradle 의존성으로만 받는다. 바이너리는 저장소에 넣지 않는다)
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    // 화면 (Jetpack Compose)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
