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
        // 방 서버 기본 주소. 배포 서버로 빌드할 때: ./gradlew assembleRelease -Pcarmarade.serverUrl=https://rooms.example.com
        val serverUrl = (project.findProperty("carmarade.serverUrl") as String?) ?: "http://10.0.2.2:8787"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].java.srcDirs("src/main/java", "../core")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // release 서명 키는 저장소에 두지 않고 환경변수로 받는다: CARMARADE_KEYSTORE(경로), CARMARADE_KS_PASS, CARMARADE_KEY_ALIAS(기본 carmarade)
    val releaseKeystore = System.getenv("CARMARADE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("CARMARADE_KS_PASS")
            keyAlias = System.getenv("CARMARADE_KEY_ALIAS") ?: "carmarade"
            keyPassword = System.getenv("CARMARADE_KS_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
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
