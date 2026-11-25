import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.files.ftp"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.files.ftp"
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

    sourceSets["main"].java.srcDirs("src/core/kotlin", "src/shared/kotlin")

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
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

// Distinct file name for the module APK: pFiles-FTP-<version>-release.apk
extensions.configure<BasePluginExtension> {
    archivesName.set("pFiles-FTP-" + (extra["appVersionName"] as String))
}

dependencies {
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:2.27.3")
    // SFTP server (the app has no server code of its own)
    implementation("org.apache.sshd:sshd-sftp:2.20.1")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}
