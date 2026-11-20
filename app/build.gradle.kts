import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.files"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.files"
        minSdk = 24
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Drop Kotlin's runtime null-check intrinsics: smaller dex, no behavior change for our code.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

// No runtime dependencies: Android framework + Kotlin stdlib only. JUnit is for JVM unit tests and never ships.
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    testImplementation("junit:junit:4.13.2")
}
