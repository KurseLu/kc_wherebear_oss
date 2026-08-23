import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// born-clean：端點 / anon key / Maps key 全住 gitignored 的 local.properties，不進 repo。
// 對應 iOS 的 Config.local.swift（gitignored）＋ Config.swift 的 dev/prod 切換。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, fallback: String = ""): String = (localProps.getProperty(key) ?: fallback)

android {
    namespace = "com.kerberosclaw.wherebear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kerberosclaw.wherebear"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Maps SDK 從 manifest placeholder 讀 key
        manifestPlaceholders["mapsApiKey"] = prop("WB_MAPS_API_KEY", "MISSING_MAPS_API_KEY")
    }

    // release 簽章：keystore 放 repo 外（~/.keystores），密碼住 gitignored 的 local.properties。
    // 沒設定時整段跳過 → release 仍可組出未簽章 APK，不會讓沒有 keystore 的人 build 失敗。
    signingConfigs {
        val ksPath = prop("WB_RELEASE_STORE_FILE")
        if (ksPath.isNotBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = prop("WB_RELEASE_STORE_PASSWORD")
                keyAlias = prop("WB_RELEASE_KEY_ALIAS", "wherebear")
                keyPassword = prop("WB_RELEASE_KEY_PASSWORD", prop("WB_RELEASE_STORE_PASSWORD"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"          // 與 iOS 的 dev 變體 app 同構：可與 prod 並存
            // debug = dev backend（本機 supabase）
            buildConfigField("String", "SUPABASE_URL", "\"${prop("WB_DEV_SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${prop("WB_DEV_SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "ENV_NAME", "\"dev\"")
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SUPABASE_URL", "\"${prop("WB_SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${prop("WB_SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "ENV_NAME", "\"prod\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // registerForActivityResult 要求 fragment >= 1.3.0；沒宣告的話 lintVital 會擋掉 release build
    // （debug 不跑 lintVital，所以只有 release 會炸）
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // 位置：FusedLocationProvider（對應 CoreLocation）
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // 地圖
    implementation("com.google.maps.android:maps-compose:6.2.1")
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // 網路：手刻 Supabase client（對齊 iOS 的 WBClient，無 Supabase SDK → 依賴少、行為可控）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // refresh token 加密儲存（對應 iOS Keychain/UserDefaults）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 相簿匯入 EXIF（對應 PHAsset.location）
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    // Android SDK 內建的 org.json 在 unit test 裡是空殼，一呼叫就丟 RuntimeException("Stub!")。
    // Outbox 整個以 JSONObject 為單位，不接真的實作就測不到。
    testImplementation("org.json:json:20240303")
}
