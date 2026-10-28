import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)   // 내비게이션 경로, 개발자 기록(JSONL)
    alias(libs.plugins.aboutlibraries)         // 오픈소스 라이선스 목록(SC12) 생성
    alias(libs.plugins.roborazzi)              // QA 스크린샷: recordRoborazziDebug → docs/qa/screenshots
}

// 릴리스 서명(signing/SIGNING.md). keystore.properties 가 없으면(CI, 다른 개발자) 릴리스는 서명 없이 빌드된다.
val ksProps = Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.ouor.pocketboard"
    compileSdk {
        version = release(37)   // androidx.core 1.19.x, Compose BOM 2026.09 요구
    }

    defaultConfig {
        applicationId = "com.ouor.pocketboard"
        minSdk = 28   // BluetoothHidDevice
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/release.p12")
            storeType = "PKCS12"
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = "release"
            keyPassword = ksProps.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            if (ksProps.containsKey("storePassword")) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += "arm64-v8a" }   // 배포 APK 는 arm64 전용(ML Kit 네이티브 라이브러리)
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true   // Robolectric
        unitTests.isReturnDefaultValues = true       // 순수 JVM 테스트에서 android.util.Log 호출 허용
        unitTests.all {
            // Robolectric 이 JDK 21 에서 FileDescriptor 내부에 접근하려면 필요
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            it.maxHeapSize = "2g"
        }
    }
}

roborazzi {
    outputDir.set(rootProject.file("docs/qa/screenshots"))
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:hid"))
    implementation(project(":core:keymap"))
    implementation(project(":core:ink"))
    implementation(project(":core:gesture"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)   // 앱 설정(settings)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.aboutlibraries.compose.m3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.composable.preview.scanner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
