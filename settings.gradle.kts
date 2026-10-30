pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Pleos Playground Maven (공식 샘플과 동일, 토큰 불필요)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos.*") }
        }
        mavenCentral()
    }
}

rootProject.name = "next-stop"

include(":core")

// Android SDK / Pleos Maven 없이 순수 Kotlin 로직만 검증할 때: -Pnextstop.coreOnly=true
val coreOnly = providers.gradleProperty("nextstop.coreOnly").orNull == "true"
if (coreOnly) {
    // AGP 플러그인 해석 없이 :core만 빌드 (Android SDK·Google Maven 접근 불가 환경용)
    rootProject.buildFileName = "build-core.gradle.kts"
} else {
    include(":app")
}
