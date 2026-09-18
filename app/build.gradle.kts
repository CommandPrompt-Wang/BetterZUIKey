import java.util.Properties
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
}

// Load signing config from keystore.properties (local + CI)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val signingConfig = if (keystorePropertiesFile.exists()) {
    Properties().apply { load(keystorePropertiesFile.inputStream()) }
} else {
    null
}

android {
    namespace = "moe.lovefirefly.betterzuikey"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.lovefirefly.betterzuikey"
        minSdk = 27
        targetSdk = 36
        versionCode = 20
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingConfig != null) {
            create("appSign") {
                keyAlias = signingConfig["keyAlias"] as String
                keyPassword = signingConfig["keyPassword"] as String
                storeFile = rootProject.file(signingConfig["storeFile"] as String)
                storePassword = signingConfig["storePassword"] as String
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // DEBUG_TMP_LOG: 编译期常量，debug 构建下 LogHelper 无视优先级输出 [TMP] logcat 行
    buildTypes {
        debug {
            if (signingConfig != null) {
                signingConfig = signingConfigs.getByName("appSign")
            }
            buildConfigField("boolean", "DEBUG_TMP_LOG", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_TMP_LOG", "false")
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

    packaging {
        jniLibs {
            // libdexkit.so 要在 hooked 进程（输入法）里从 module APK 解出来再 System.load(absPath)，
            // 所以必须随 APK 分发并按 ZipEntry 可读。legacy packaging 同时保留
            // nativeLibraryDir 中有实体文件这条兜底加载路径。
            useLegacyPackaging = true
        }
    }

}

// AGP 9 removed VariantOutput.outputFileName from the public API; the internal
// VariantOutputImpl is the workaround (see AGP issue 480062612).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as VariantOutputImpl).outputFileName.set(
                "BetterZUIKey-v${output.versionName.get()}.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Markdown
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2") {
        // 排除 prism4j 拉进来的 annotations-java5
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties:prism4j:2.0.0") {
        // prism4j 自己也要排除
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    compileOnly("io.github.libxposed:api:101.0.0")
    implementation("io.github.libxposed:service:101.0.0")

    // DexKit — 运行期 dex 解析。用「混淆器改不了的锚点」（父类/框架 override 名/字符串）
    // 定位输入法内部类与方法，替代反射遍历声明方法。
    // 许可：Apache-2.0（Core/ 目录 LGPL-3.0），与本项目 GPL-3.0 兼容。
    implementation("org.luckypray:dexkit:2.2.0")

    // TermuxAm — inject am via app_process bypassing shell UID requirement
    implementation(project(":termuxam:app"))
}