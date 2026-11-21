import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

// 本机使用被 gitignore 的配置；CI 优先从环境变量读取签名，避免把口令写入仓库或构建参数。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingProperty(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull ?: keystoreProperties.getProperty(propertyName)

val cloudProperties = Properties().apply {
    val file = rootProject.file("cloud.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun cloudProperty(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
        ?: cloudProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

fun quoteGradleString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val cloudWebUrl = (cloudProperty("FITSYNC_CLOUD_WEB_URL", "webUrl") ?: "https://fitsync.example.com")
    .trim()
    .trimEnd('/')
val cloudWebHost = runCatching { URI(cloudWebUrl).host }
    .getOrNull()
    ?.takeIf { it.isNotBlank() }
    ?: "fitsync.example.com"
val cloudApiBaseUrl = (cloudProperty("FITSYNC_CLOUD_API_BASE_URL", "apiBaseUrl") ?: "$cloudWebUrl/api")
    .trim()
    .trimEnd('/')
val cloudApplicationId = cloudProperty("FITSYNC_CLOUD_APPLICATION_ID", "applicationId") ?: "com.fitsync.cloud"
val admobEnabled = (cloudProperty("FITSYNC_ADMOB_ENABLED", "admobEnabled") ?: "false").toBoolean()
// 未填真实 AdMob App ID 时回落 Google 官方测试 ID，避免 MobileAdsInitProvider 在启动期崩溃。
val admobAppId = cloudProperty("FITSYNC_ADMOB_APP_ID", "admobAppId")
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerUnitId = cloudProperty("FITSYNC_ADMOB_BANNER_UNIT_ID", "admobBannerUnitId") ?: ""

android {
    namespace = "com.fitsync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitsync.app"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.environmentVariable("FITSYNC_VERSION_CODE").orNull?.toInt() ?: 2
        versionName = providers.environmentVariable("FITSYNC_VERSION_NAME").orNull ?: "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("local") {
            dimension = "edition"
            applicationId = "com.fitsync.app"
            targetSdk = 34
            buildConfigField("boolean", "IS_CLOUD_EDITION", "false")
        }
        create("cloud") {
            dimension = "edition"
            applicationId = cloudApplicationId
            targetSdk = 36
            cloudProperty("FITSYNC_CLOUD_VERSION_CODE", "versionCode")?.toIntOrNull()?.let { versionCode = it }
            cloudProperty("FITSYNC_CLOUD_VERSION_NAME", "versionName")?.let { versionName = it }
            buildConfigField("boolean", "IS_CLOUD_EDITION", "true")
            buildConfigField("String", "CLOUD_WEB_URL", quoteGradleString(cloudWebUrl))
            buildConfigField("String", "CLOUD_API_BASE_URL", quoteGradleString(cloudApiBaseUrl))
            buildConfigField("boolean", "ADMOB_ENABLED", admobEnabled.toString())
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", quoteGradleString(admobBannerUnitId))
            manifestPlaceholders["admobAppId"] = admobAppId
            manifestPlaceholders["admobDelayAppMeasurementInit"] = (!admobEnabled).toString()
            manifestPlaceholders["cloudWebHost"] = cloudWebHost
        }
    }

    signingConfigs {
        create("release") {
            signingProperty("FITSYNC_KEYSTORE_FILE", "storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = signingProperty("FITSYNC_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingProperty("FITSYNC_KEY_ALIAS", "keyAlias")
            keyPassword = signingProperty("FITSYNC_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:strongSkipping=true",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    baselineProfile(project(":benchmark"))

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    "cloudImplementation"("androidx.browser:browser:1.8.0")
    "cloudImplementation"("com.google.android.gms:play-services-ads:23.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("com.google.truth:truth:1.4.2")
}

baselineProfile {
    automaticGenerationDuringBuild = false
}
