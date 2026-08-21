plugins {
    id("com.android.application")
}

android {
    namespace = "com.imagine.livelingo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imagine.livelingo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.12.4")
    testImplementation("junit:junit:4.13.2")
}
