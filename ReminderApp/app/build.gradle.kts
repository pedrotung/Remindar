import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 正式版签名信息从 ReminderApp/keystore.properties 读取（不提交到 git）。
 * 文件不存在时（例如别人克隆仓库自行编译），正式版不签名，调试版不受影响。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // 代码包名保持不变；applicationId 是 App 在系统和应用商店中的唯一身份
    namespace = "com.example.reminder"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.pedrotung.remindar"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试版使用独立包名，可与正式版同时安装（桌面名称见 src/debug 中的“醒达 Dev”）
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // lifecycleScope + 协程：用于在后台调用大模型接口
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // 生成“家人扫码找手机”的二维码（仅编码，不含扫描界面）
    implementation("com.google.zxing:core:3.5.3")
}
