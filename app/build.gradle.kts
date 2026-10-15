plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hawky.nascraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hawky.nascraft"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The production host must be supplied when this app is signed for release.
        // The placeholder does not claim a verified production App Link association.
        val appLinksHost = providers.gradleProperty("nascraftAppLinksHost")
            .orElse("nascraft.invalid")
            .get()
            .trim()
            .lowercase()
        require(appLinksHost.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) {
            "nascraftAppLinksHost must be a complete DNS hostname"
        }
        manifestPlaceholders["appLinksHost"] = appLinksHost
        buildConfigField("String", "APP_LINKS_HOST", "\"$appLinksHost\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // 添加 Material 图标库 - 通过版本目录引用
    implementation(libs.androidx.compose.material.icons.extended)

    // Accompanist Pager for swipeable image preview
    implementation(libs.accompanist.pager)
    implementation(libs.accompanist.pager.indicators)

    // Coroutines - 通过版本目录引用
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Network - 通过版本目录引用
    implementation(libs.okhttp)
    // Native Android passkeys: use the OS Credential Manager and its Play services provider adapter.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.code.scanner)

    // Image loading with Coil for Jetpack Compose - v2.x for better mirror compatibility
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("io.coil-kt:coil:2.4.0")
    // 视频首帧缩略图（本地相册视频格子）
    implementation("io.coil-kt:coil-video:2.4.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
