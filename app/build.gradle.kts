plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.naitae.connect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.naitae.connect"
        minSdk = 28            // Pleos Connect 요구 사항: Android API 28 이상
        targetSdk = 34         // Pleos Connect Emulator v2.1.0 = API 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 프로토타입: 디버그 키로 서명해 바로 설치할 수 있게 함
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }   // Kotlin 1.9.25 대응
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric 이 병합된 매니페스트·리소스를 읽어 실제 NaitaeApp·MainActivity 를 JVM 에서 띄운다.
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "3g"
                test.jvmArgs("-Dfile.encoding=UTF-8")
                // e2e 화면 캡처는 디버그 단위 테스트에서만 docs/screenshots 에 남긴다(릴리스에서는 건너뜀).
                if (test.name == "testDebugUnitTest") {
                    val shots = rootProject.file("docs/screenshots")
                    test.systemProperty("roborazzi.test.record", "true")
                    test.systemProperty("naitae.screenshot.dir", shots.absolutePath)
                    // 캡처도 이 작업의 결과물이다(지우면 다시 돈다). 빌드 캐시가 문서 폴더를 덮어쓰지 않게 캐시는 쓰지 않는다.
                    test.outputs.dir(shots)
                    test.outputs.cacheIf { false }
                }
            }
        }
    }
}

dependencies {
    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // e2e 시나리오 검증·화면 캡처 (에뮬레이터 없이 JVM 에서 실행)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
}
