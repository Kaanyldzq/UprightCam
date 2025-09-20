plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    kotlin("kapt")
    id("com.google.dagger.hilt.android")
}

val cameraxVersion = "1.3.4"

android {
    namespace = "com.kaanyildiz.videoinspectorapp"
    compileSdk = 36

    buildFeatures {
        buildConfig = true      // BuildConfig.BASE_URL için
        viewBinding = true      // (Gereksinim)
        compose = true
    }

    defaultConfig {
        applicationId = "com.kaanyildiz.videoinspectorapp"
        minSdk = 24            // (Gereksinim)
        targetSdk = 36          // (Gereksinim)
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Yerel backend (HTTP). Cleartext için networkSecurityConfig tanımlı olmalı.
        buildConfigField("String", "BASE_URL", "\"http://192.168.1.136:3000\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // (Opsiyonel) Bazı kütüphanelerde lisans dosyası çakışmalarını susturmak için:
    // packaging {
    //     resources {
    //         excludes += "/META-INF/{AL2.0,LGPL2.1}"
    //     }
    // }
}

dependencies {
    // --- Compose / AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // AppCompat & Material
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.firebase.inappmessaging)
    implementation(libs.androidx.media3.common.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- Fragment & RecyclerView ---
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // --- Lifecycle ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // --- Hilt ---
    implementation("com.google.dagger:hilt-android:2.57.1")
    kapt("com.google.dagger:hilt-compiler:2.57.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // --- Retrofit + OkHttp + Moshi ---
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- WorkManager ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- CameraX ---
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion") // isteğe bağlı

    // --- OpenGL / Sensör yardımcı anotasyonlar ---
    implementation("androidx.annotation:annotation:1.8.2")

    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // --- Konum ---
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- Coil ---
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // --- ListenableFuture & Guava ---
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("com.google.guava:guava:33.2.1-android")

    // --- Media3 (video oynatma) ---
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // --- EXIF (fotoğrafları dik kaydetmek için gerekli) ---
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // --- AppCompat & Material constraint'leri ---
    constraints {
        implementation("androidx.appcompat:appcompat:1.7.0") { because("tema/uyum için tek versiyon") }
        implementation("com.google.android.material:material:1.12.0") { because("Material inflate hatalarını önlemek için") }
    }
}

// Guava sabitleme (bazı transitif sürümlerle çakışmayı önler)
configurations.configureEach {
    resolutionStrategy {
        force("com.google.guava:guava:33.2.1-android")
    }
}

kapt {
    correctErrorTypes = true
}
