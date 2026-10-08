plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.shilapi.xcertplay.board"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shihab.diplay.hudtest"
        minSdk = 28
        targetSdk = 33 // Appliance is pinned to Android 13; do not inherit newer boot restrictions.
        versionCode = 37
        versionName = "0.2.11-wukongpi.1"
        ndk { abiFilters += "armeabi-v7a" }
    }
    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("DIPLAY_DEBUG_KEYSTORE_PATH").orNull?.let {
                storeFile = file(it); storePassword = "android"
                keyAlias = "carprojectiondebug"; keyPassword = "android"
            }
        }
    }
    buildTypes { getByName("debug") { isDebuggable = true } }
    sourceSets.getByName("main") {
        java.srcDir("../common/src/main/java")
        java.include("com/shilapi/xcertplay/board/**", "com/shilapi/xcertplay/AirPlayPersistence.kt",
            "com/shilapi/xcertplay/DiPlayBootstrap.kt", "com/shilapi/xcertplay/DiPlayBluetooth.kt")
        providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull?.let { assets.srcDir(it) }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":shared"))
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
val verifyStandalone by tasks.registering {
    doLast {
        val dir = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull?.let { file(it) }
        check(dir != null && listOf("identity.pk8", "certificate.p7b").all { dir.resolve("offline-mfi/$it").isFile }) {
            "Standalone board APK requires explicitly provisioned DIPLAY_AUTH_ASSETS_DIR"
        }
    }
}
tasks.register("assembleStandaloneDebug") { dependsOn(verifyStandalone, "assembleDebug") }
