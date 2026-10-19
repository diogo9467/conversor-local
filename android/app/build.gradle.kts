plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val appAbis = providers.gradleProperty("abis").orElse("arm64-v8a").get().split(",")
require(appAbis.all { it in setOf("arm64-v8a", "x86_64") }) { "Esta versão suporta ARM64 e x86_64 de teste." }
android {
    namespace = "dev.diogo.conversorlocal"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.diogo.conversorlocal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += appAbis }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].apply {
        assets.srcDir("build/generated/office/assets")
        jniLibs.srcDir("build/generated/office/jniLibs")
        java.srcDir("build/generated/office/java")
    }
    // The LibreOffice APK asset reader maps stored ZIP members directly.
    androidResources {
        // Only LO assets must be stored. Keep native .so compression in the APK.
        noCompress += file("build/generated/office/assets").walkTopDown().filter { it.isFile }
            .map { it.name.substringAfterLast('.', it.name) }.toSet()
    }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    buildTypes {
        release {
            isMinifyEnabled = false
            val keyPath = providers.environmentVariable("CONVERSOR_KEYSTORE").orNull
            if (keyPath != null) {
                signingConfig = signingConfigs.create("localRelease") {
                    storeFile = file(keyPath)
                    storePassword = providers.environmentVariable("CONVERSOR_STORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("CONVERSOR_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("CONVERSOR_KEY_PASSWORD").get()
                }
            }
        }
    }
    testOptions { animationsDisabled = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files("build/generated/office/fitz.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
tasks.named("preBuild") {
    doFirst {
        check(file("build/generated/office/assets/program/fundamentalrc").isFile) {
            "Execute python tools/prepare_engines.py antes de compilar (veja README.md)."
        }
        check(file("build/generated/office/fitz.aar").isFile && appAbis.all { file("build/generated/office/jniLibs/$it/liblo-native-code.so").isFile && file("build/generated/office/jniLibs/$it/libmupdf_java.so").isFile }) {
            "Prepare todos os motores solicitados com python tools/prepare_engines.py --abis ${appAbis.joinToString(",")}."
        }
    }
}
