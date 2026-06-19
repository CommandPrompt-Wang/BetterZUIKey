plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "moe.lovefirefly.betterzuikey"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.lovefirefly.betterzuikey"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // DEBUG_TMP_LOG: 编译期常量，debug 构建下 LogHelper 无视优先级输出 [TMP] logcat 行
    buildTypes {
        debug {
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

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.remotepreferences)
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

    compileOnly("de.robv.android.xposed:api:82")
}