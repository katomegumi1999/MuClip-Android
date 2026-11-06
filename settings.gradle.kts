pluginManagement {
    repositories {
        // AGP / Android 工具链构件优先走阿里云 google 镜像。
        // 新版 AGP 9.4.0 的部分传递依赖（如 com.android.tools.analytics-library:crash:32.4.0）
        // 在 public 镜像上取不到，public 排首位会导致整条 classpath 解析直接失败。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "MuClip-Android"
include(":app")
