plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreFile = rootProject.file("keystore/open-signing.jks")

tasks.register("ensureKeystore") {
    doLast {
        if (keystoreFile.exists()) return@doLast
        keystoreFile.parentFile.mkdirs()
        val binary = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$binary")
        if (!keytool.exists()) throw GradleException("找不到 keytool：${keytool.absolutePath}")
        project.exec {
            commandLine(
                keytool.absolutePath,
                "-genkeypair",
                "-keystore", keystoreFile.absolutePath,
                "-storepass", "offlinewallet",
                "-keypass", "offlinewallet",
                "-alias", "open",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=Offline Wallet, O=Offline Wallet",
            )
        }
    }
}

android {
    namespace = "com.offlinewallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offlinewallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        multiDexEnabled = true
    }

    signingConfigs {
        create("open") {
            storeFile = keystoreFile
            storePassword = "offlinewallet"
            keyAlias = "open"
            keyPassword = "offlinewallet"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("open")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn("ensureKeystore") }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
