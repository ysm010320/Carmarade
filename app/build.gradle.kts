plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ai.camarade.app"
    compileSdk = 34
    useLibrary("android.car") // CarUxRestrictions(주행 중 제한) 확인용. 차량 기기에만 있음.
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
dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}
