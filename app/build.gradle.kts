import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.sichimi"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.sichimi"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 전국 랭킹(Firebase, REST로 부른다). app/google-services.json(저장소에 넣지 않음)이 있으면 프로젝트 ID와 API 키를 읽는다.
        // 없으면 빈 값이고, 앱은 랭킹을 "연결 안 됨"으로 두고 나머지는 그대로 동작한다.
        val gs = file("google-services.json")
        var rankProject = ""
        var rankKey = ""
        if (gs.exists()) {
            @Suppress("UNCHECKED_CAST")
            val j = groovy.json.JsonSlurper().parse(gs) as Map<String, Any?>
            rankProject = (j["project_info"] as Map<String, Any?>)["project_id"] as String
            val client = (j["client"] as List<Map<String, Any?>>).first()
            rankKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"] as String
        }
        buildConfigField("String", "RANK_PROJECT", "\"$rankProject\"")
        buildConfigField("String", "RANK_API_KEY", "\"$rankKey\"")
    }

    useLibrary("android.car")

    // release 서명 키는 저장소 밖에 두고 keystore.properties(저장소에 넣지 않음)로 읽는다.
    // 파일이 없으면 release는 서명 없이 만들어진다.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val p = Properties().apply { keystoreFile.reader(Charsets.UTF_8).use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
        // 문서용 캡처(디자인 미리보기)를 앱 데이터를 지우지 않고 깔 때만: ./gradlew :app:assembleDebug -PsignDebugWithRelease
        debug {
            if (project.hasProperty("signDebugWithRelease") && releaseSigning != null) signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDirs("../songs")
    // 개발용 임시 음원은 songs-dev/에 두고 debug 빌드에만 넣는다(release APK에는 들어가지 않는다).
    sourceSets["debug"].assets.srcDirs("../songs-dev")
}

dependencies {
    implementation(project(":gate"))

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

