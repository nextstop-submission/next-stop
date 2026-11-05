plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val pleosVehicleSdk = providers.gradleProperty("pleosVehicleSdk").get()
val pleosNaviHelperSdk = providers.gradleProperty("pleosNaviHelperSdk").get()

android {
    namespace = "app.nextstop"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nextstop"
        // Pleos 문서 요구 API 28+, NaviHelper 공식 샘플 minSdk 33 → 33으로 맞춘다.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PLEOS_VEHICLE_SDK", "\"$pleosVehicleSdk\"")
        buildConfigField("String", "PLEOS_NAVIHELPER_SDK", "\"$pleosNaviHelperSdk\"")
    }

    // AAOS Car API(기어·UX 제한). compileSdk 플랫폼의 optional/android.car.jar를 참조한다.
    useLibrary("android.car")


    buildTypes {
        release {
            isMinifyEnabled = false
            // 서명 키는 일부러 빌드 환경의 debug 키로 둔다: 공개(익명) 저장소에 비공개 키·암호를 넣을 수 없고,
            // CI에 비밀값으로 두면 심사위원이 같은 APK를 재현 빌드할 수 없다. 마켓 배포 때는 비공개 키를 저장소 밖(로컬·CI 비밀값)에서 주입한다.
            // 실측 화면·외부 시연 신호 같은 개발용 장치는 서명과 무관하게 release에서 빠지거나 막혀 있다(debug 소스셋·DUMP 권한).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // 화면 스냅샷(PNG) 저장 위치. CI가 docs/screens/로 복사한다.
            test.systemProperty("nextstop.screens", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
            // 시연 영상 장면(예비 경로). -Pnextstop.video=true 일 때만 DemoVideoScenesTest가 돈다.
            if (project.findProperty("nextstop.video") == "true") {
                test.systemProperty("nextstop.video", layout.buildDirectory.dir("video").get().asFile.absolutePath)
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:$pleosVehicleSdk")
    implementation("ai.pleos.playground:NaviHelper:$pleosNaviHelperSdk")

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
