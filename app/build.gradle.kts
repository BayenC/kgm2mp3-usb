import java.util.Properties
plugins { id("com.android.application"); kotlin("android") }
val signingFile = rootProject.file("signing/kgm2mp3-usb.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }
android {
    namespace = "com.kgm2mp3_usb.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kgm2mp3_usb.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Opt in to exercising the signed production APK with its matching test signature.
    testBuildType = providers.gradleProperty("instrumentationBuildType").getOrElse("debug")
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = rootProject.file(signingValues.getProperty("storeFile"))
            storePassword = signingValues.getProperty("storePassword")
            keyAlias = signingValues.getProperty("keyAlias")
            keyPassword = signingValues.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":kgm-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    implementation("com.arthenica:smart-exception-java:0.2.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
