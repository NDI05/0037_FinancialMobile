import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}


// Build properties can come from three places, in this order:
//   1. -PPAM_... on the command line (CI, ./scripts/build.sh)
//   2. gradle.properties
//   3. local.properties — the file Android Studio already manages per machine, so pressing
//      Run in the IDE points at the local backend without any extra Gradle arguments.
// Without this fallback an IDE Run produces an empty PAM_BACKEND_BASE_URL and the app silently
// falls back to the offline demo, which looks like a broken backend.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun buildProperty(name: String): String =
    project.findProperty(name)?.toString() ?: localProperties.getProperty(name) ?: ""

android {
    namespace = "com.pam.recap"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.pam.recap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // OI-07: optional remote analysis backend; blank keeps the on-device engine.
        // Precedence in `BackendConfig`: PAM_BACKEND_BASE_URL wins; PAM_ANALYSIS_BACKEND_URL stays
        // supported as a full-URL override so older invocations keep working.
        buildConfigField(
            "String",
            "PAM_BACKEND_BASE_URL",
            "\"${buildProperty("PAM_BACKEND_BASE_URL")}\"",
        )
        buildConfigField(
            "String",
            "PAM_ANALYSIS_BACKEND_URL",
            "\"${buildProperty("PAM_ANALYSIS_BACKEND_URL")}\"",
        )
        // Debug builds allow screen capture so the app can be mirrored in Android Studio and
        // inspected while testing. Release always keeps FLAG_SECURE (NFR-16), so this cannot leak
        // into a shipped build.
        buildConfigField(
            "boolean",
            "PAM_ALLOW_SCREEN_CAPTURE",
            buildProperty("PAM_ALLOW_SCREEN_CAPTURE").ifBlank { "true" },
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            buildConfigField("boolean", "PAM_ALLOW_SCREEN_CAPTURE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        // A project-local debug keystore keeps the build self-contained; ~/.android is not
        // writable in this environment.
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // kotlinx-datetime 0.7 exposes kotlin.time.Instant; opt in once for the module.
            optIn.add("kotlin.time.ExperimentalTime")
        }
    }

    ksp {
        // exportSchema = true needs a schema directory, and the exported JSON is committed so
        // future migrations can be diffed.
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // javax.mail ships descriptor files that would collide across the two artifacts.
            excludes += "/META-INF/{mailcap,mailcap.default,MANIFEST.MF,INDEX.LIST,NOTICE.md,LICENSE.md,LICENSE.txt}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Robolectric resolves android-all jars from here; the machine-wide caches are
                // not writable in this workspace.
                test.systemProperty("robolectric.dependency.dir", rootProject.layout.projectDirectory.dir(".robolectric-deps").asFile.absolutePath)
                test.systemProperty("robolectric.offline", "false")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // G1/G2: IMAP over TLS. android-mail ships javax.mail 1.6.x (no jakarta split, no
    // duplicate classes); activation is its companion artifact.
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
