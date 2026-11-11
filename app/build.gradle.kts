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
        // 지도(MapLibre) 네이티브 라이브러리는 실차(arm64)와 에뮬레이터(x86_64)용만 넣어 APK 크기를 줄임.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    // 주행 화면 지도. OpenFreeMap 타일(무료, 키 없음)을 그린다. 13.x는 Kotlin 2.2 기반이라 12.x 사용.
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    // 방 서버 REST·WebSocket. MapLibre가 쓰는 것과 같은 버전.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
