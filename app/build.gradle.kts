import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起自带 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * release 签名材料的读取。
 *
 * `keystore.properties` 放在**仓库根目录**，和 `.jks` 一起被 .gitignore 排除，
 * 永远不会进版本库。文件不存在时（比如别人 clone 下来、或 CI 只跑 debug）
 * 这里什么都不读，release 变体退化成未签名包——**构建不会失败**。
 * 这一点很重要：签名配置写死在 gradle 里会让没密钥的人连 assembleDebug 都跑不了。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropertiesFile.exists()

android {
    namespace = "com.courseschedule"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.courseschedule"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // storeFile 的相对路径以 **app 模块目录** 为基准（file() 的默认行为），
                // 所以 keystore.properties 里写 `../release.jks` 才是根目录。
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            /**
             * 没有正式密钥时退化用 debug 签名。
             *
             * 为什么不是"干脆不签名"：不签名的 APK **装不上手机**，
             * 于是 R8 混淆 + 资源压缩这条最容易出事的链路就永远没人验证过
             * （Room 生成的实现类、kotlinx.serialization 生成的序列化器
             * 都是被反射/间接引用的，混淆搞坏它们只会静默崩）。
             * 用 debug 签名跑一个 `assembleRelease` 装上去点一遍，
             * 是发布前最低成本的保险。
             *
             * ⚠️ debug 签名的包**不能对外发布**（密钥是公用的，且和正式包签名不同，
             * 两者无法互相覆盖安装）。正式发版前务必配好 keystore.properties。
             */
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle("⚠ 未找到 keystore.properties：release 包使用 debug 签名，仅供本机测试")
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 关于页要显示版本号。AGP 8 起 BuildConfig 默认关闭，必须显式打开，
        // 否则 `BuildConfig.VERSION_NAME` 编译不过。
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 导出 Room schema，P2/P3 加表时靠它写可验证的 migration
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 备份文件的 JSON 读写（P2a）。只在导出/导入时用，不参与界面渲染。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
