import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.tongpin.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tongpin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.12.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("personalRelease") {
            val propsFile=rootProject.file(".signing/release.properties")
            if(propsFile.exists()) {
                val props=Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile=rootProject.file(props.getProperty("storeFile"))
                storePassword=props.getProperty("storePassword")
                keyAlias=props.getProperty("keyAlias")
                keyPassword=props.getProperty("keyPassword")
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig=signingConfigs.getByName("personalRelease") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.16.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
