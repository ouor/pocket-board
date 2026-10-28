// Android 라이브러리 모듈. 판정·상태기계 같은 순수 로직은 android.* 를 import 하지 않는 패키지에 두고 JVM 단위 테스트한다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ouor.pocketboard.core.ink"
    compileSdk {
        version = release(37)   // androidx.core 1.19.x, Compose BOM 2026.09 요구
    }
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // 현장 사례(src/test/resources/field)를 기기 평가(androidTest, 실제 ML Kit)에서도 그대로 쓴다
    sourceSets { getByName("androidTest") { assets.directories.add("src/test/resources") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)                  // InkReport(전송별 디버그 보고서)
    implementation(libs.mlkit.digital.ink)
    implementation(libs.kotlinx.coroutines.play.services)   // ML Kit Task.await()
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
