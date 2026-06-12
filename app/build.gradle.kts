plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "moe.lovefirefly.betterzuikey"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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

    compileOnly("de.robv.android.xposed:api:82")
}