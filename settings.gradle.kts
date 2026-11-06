// ============================================================================
// 仓库源顺序
//
// 默认：**官方源优先** —— 开源后海外贡献者与 CI 直接可用，不必连阿里云。
//
// 国内加速：在**用户级** ~/.gradle/gradle.properties 里加一行
//
//     muclip.useCnMirrors=true
//
// 即可把阿里云镜像提到最前。放在用户级而不是仓库里，是因为这是本地网络环境的
// 偏好，不该强加给其他贡献者。
//
// 为什么要保留「镜像不能排最前」这个约束：新版 AGP 的部分传递依赖
// （如 com.android.tools.analytics-library:crash:32.4.0）在 aliyun 的 public
// 镜像上取不到。一旦它排首位，整条 classpath 解析会直接失败，而报错指向的是
// 缺失的 artifact 而非镜像本身，极难排查。
// ============================================================================
pluginManagement {
    val useCnMirrors = startParameter.projectProperties["muclip.useCnMirrors"]
        ?.toBoolean() ?: false

    repositories {
        if (useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        if (!useCnMirrors) {
            // 官方源不通时的兜底，只排在最后
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}
// 这里原本挂了 org.gradle.toolchains.foojay-resolver-convention，已移除。
// 它只在声明了 Java toolchain（jvmToolchain(n) / java { toolchain { ... } }）时
// 才起作用，用于按需下载 JDK；而本项目只在 compileOptions / jvmTarget 里固定
// Java 21，从未声明 toolchain，所以这个插件从来没被真正触发过。
// 留着它的代价却是每个构建者都必须能访问 plugins.gradle.org 才能解析到它 ——
// 对受限网络和离线构建都是纯负担。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    val useCnMirrors = startParameter.projectProperties["muclip.useCnMirrors"]
        ?.toBoolean() ?: false

    repositories {
        if (useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
        if (!useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
    }
}

rootProject.name = "MuClip-Android"
include(":app")
