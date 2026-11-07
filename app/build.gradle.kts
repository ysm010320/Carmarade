plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ai.camarade.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.camarade.app.demo"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-demo"
    }
    sourceSets["main"].java.srcDirs("src/main/java", "../core")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
