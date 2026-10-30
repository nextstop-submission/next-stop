// -Pnextstop.coreOnly=true 일 때만 쓰는 루트 빌드 파일. Android 플러그인을 해석하지 않는다.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
