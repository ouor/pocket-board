// Android 라이브러리 모듈. 판정·상태기계 같은 순수 로직은 android.* 를 import 하지 않는 패키지에 두고 JVM 단위 테스트한다.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ouor.pocketboard.core.gesture"
    compileSdk {
        version = release(37)   // androidx.core 1.19.x, Compose BOM 2026.09 요구
    }
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)   // 제스처 매핑(gesture_mapping)
    api(libs.kotlinx.serialization.json)                  // ContactRecord(F30 기록 형식)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
