plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Read signing credentials from CI or an untracked local.properties file.
val signProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.let { FileInputStream(it).use { fis -> signProps.load(fis) } }

fun signingValue(environmentName: String, propertyName: String, defaultValue: String = ""): String =
    providers.environmentVariable(environmentName).orNull
        ?: signProps.getProperty(propertyName, defaultValue)

android {
    namespace = "io.github.kiriashi.biopay"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kiriashi.biopay"
        minSdk = 28
        targetSdk = 35
        versionCode = 260930
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(signingValue("BIOPAY_RELEASE_STORE_FILE", "RELEASE_STORE_FILE", "../biopay.keystore"))
            storePassword = signingValue("BIOPAY_RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("BIOPAY_RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
            keyPassword = signingValue("BIOPAY_RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // Keep local release builds usable when no keystore is configured.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
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

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.libxposed:api:102.0.0")
}
