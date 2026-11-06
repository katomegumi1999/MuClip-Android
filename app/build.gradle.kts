plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.muclip.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.muclip.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.1.7"

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
        create("release") {
            val debugKeyStore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (debugKeyStore.exists()) {
                storeFile = debugKeyStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
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
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
                ?: signingConfigs.getByName("debug")
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

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}

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
