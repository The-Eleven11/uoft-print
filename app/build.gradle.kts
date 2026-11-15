import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore.properties in the project root (never committed), or environment
// variables for CI. Without either, release builds are left unsigned.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

android {
    namespace = "dev.uoftprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.the_eleven11.uoftprint"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "UOFTPRINT_STORE_FILE")
        if (storeFilePath != null) {
            create("upload") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "UOFTPRINT_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "UOFTPRINT_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "UOFTPRINT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
            if (signingConfig == null) logger.warn("No keystore.properties: release build will be unsigned.")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test ->
            // Generated PostScript / PCL is rendered by the real Ghostscript interpreters when present.
            // Local: interpreters unpacked into tools/. CI: GS_PATH / PCL_PATH environment variables.
            val gs = System.getenv("GS_PATH")?.let(::file) ?: rootProject.file("tools/gs/bin/gswin64c.exe")
            val pcl = System.getenv("PCL_PATH")?.let(::file) ?: rootProject.file("tools/gpcl/ghostpcl-10.08.0-win64/gpcl6win64.exe")
            if (gs.exists()) test.systemProperty("gs.path", gs.absolutePath)
            if (pcl.exists()) test.systemProperty("pcl.path", pcl.absolutePath)
            test.systemProperty("test.out", layout.buildDirectory.dir("test-prints").get().asFile.absolutePath)
            test.maxHeapSize = "2g"
            test.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
