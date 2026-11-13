plugins {
    alias(libs.plugins.application)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.reign.bilistudy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reign.bilistudy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // 和 iOS 共用的同步合并用例
    sourceSets["test"].resources.srcDir("../../docs")
}

dependencies {
    implementation(platform(libs.composeBom))
    implementation(libs.composeUi)
    implementation(libs.composeFoundation)
    implementation(libs.composeMaterial3)
    implementation(libs.composeMaterialIcons)
    implementation(libs.composeUiToolingPreview)
    implementation(libs.activityCompose)
    implementation(libs.lifecycleCompose)
    implementation(libs.media3Exoplayer)
    implementation(libs.media3Ui)
    implementation(libs.okHttp)
    implementation(libs.gson)
    implementation(libs.coilCompose)
    implementation(libs.kotlinxCoroutinesAndroid)
    implementation(libs.securityCrypto)
    implementation(libs.anthropic)
    testImplementation(libs.junit)
}
