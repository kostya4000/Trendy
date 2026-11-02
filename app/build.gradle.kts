import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val appVersionCode = versionProps.getProperty("versionCode", "1").toInt()
val appVersionName = versionProps.getProperty("versionName", "1.0")

android {
    namespace = "com.promodj.trendy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.promodj.trendy"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
