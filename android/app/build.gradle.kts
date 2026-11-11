plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.ccu825"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ccu825"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
        doLast {
            val debugDir = file("$buildDir/outputs/apk/debug")
            if (debugDir.exists()) {
                debugDir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".apk") && !f.name.startsWith("ccu825_temp")) {
                        f.renameTo(File(debugDir, "ccu825_temp_debug.apk"))
                    }
                }
            }
            val releaseDir = file("$buildDir/outputs/apk/release")
            if (releaseDir.exists()) {
                releaseDir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".apk") && !f.name.startsWith("ccu825_temp")) {
                        f.renameTo(File(releaseDir, "ccu825_temp.apk"))
                    }
                }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
