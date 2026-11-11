import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ============================================================================
// Release 签名
//
// 仓库里不含任何签名密钥。签名材料从 rootProject 下的 keystore.properties 读取
// （该文件已在 .gitignore 中），CI 侧用 Gradle Secrets 注入同名属性。
//
// 为什么不用 debug.keystore 签 release：
//   ① debug key 是公开的（密码固定为 android），任何人拿到都能伪造「官方更新」；
//   ② 一旦用它分发过，将来换成正式密钥时签名不一致，老用户必须先卸载才能安装；
//   ③ 开源仓库里出现共享私钥是评审红旗。
//
// 文件不存在时回退 debug 签名 —— 保证任何人 clone 后都能构建、能本地验证，
// 但这样产出的包只能自用，不能分发。
// ============================================================================
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

android {
    namespace = "com.muclip.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.muclip.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 之前 release 是未混淆构建：一个「只有几个界面 + 一个 Rust .so」的
            // 应用 dex 膨胀到 38MB 量级，其中绝大部分是 material-icons-extended
            // 里没用到的图标和未触碰到的 Compose 代码路径。
            // 开启后需要 proguard-rules.pro 保留 JNI 回调方法名，见该文件注释。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 21 是这里刻意选的上限，不要再往上追：
    // ① 项目里 0 个 .java 文件（36 个 .kt），compileOptions 实际不参与任何编译；
    // ② jvmTarget 只决定 class 文件版本号，D8 打包时会降级成 dex，
    //    手机上跑的 ART 完全看不到这个数字，调高对运行时零收益；
    // ③ Java 22 以上均为非 LTS，半年一换，追新只会不断产生新的兼容账单。
    // Java 21 是 LTS，也是当前 Android 生态的安全上界。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileSdkMinor = 2
}

// 注意：这里曾经有过
//   tasks.matching { it.name.contains("AarMetadata") }.configureEach { enabled = false }
// 用来绕过依赖的 minCompileSdk / minAgpVersion 校验。全局关掉元数据校验等于拆掉
// 依赖兼容性警报器：AGP 与 AAR 的要求不匹配时，构建会一路通过，然后在运行时或
// 下一次升级时才以更难排查的方式爆出来。compileSdk 37 已经能满足当前全部依赖，
// 该绕过已无必要，故移除。
//
// 若将来升依赖时确实被 AarMetadata 拦下，正确处理是提高 compileSdk 或降级那个依赖，
// 而不是重新关掉这项校验。

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose BOM & Material 3
    // 2026.09.00 -> Compose 1.12.1 + Material3 1.4.x。
    // 升上来主要是为了 rememberGraphicsLayer()/GraphicsLayer.renderEffect（1.8+），
    // 玻璃底栏要靠它拿背后内容做真高斯模糊。
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Chris Banes Haze for true Android 12+ GPU Backdrop Blur & Liquid Glass
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON for parsing
    implementation("org.json:json:20240303")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
