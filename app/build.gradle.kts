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
        // Google SSO (contract: GET /v1/auth/google/nonce, POST /v1/auth/google, link). The value
        // is the **Web** OAuth client ID, i.e. the audience the backend verifies; no client secret
        // ever ships on Android. Blank is a supported configuration: the Google button and the
        // link card stay hidden and email/password sign-in works exactly as before.
        val googleSsoClientId = buildProperty("PAM_GOOGLE_SSO_CLIENT_ID")
        buildConfigField("String", "PAM_GOOGLE_SSO_CLIENT_ID", "\"$googleSsoClientId\"")
        buildConfigField(
            "boolean",
            "PAM_GOOGLE_SSO_ENABLED",
            googleSsoClientId.isNotBlank().toString(),
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
        // S4.4 assessment variant: same debuggable build as `debug`, but Room/SQLCipher are not on
        // its classpath. The finance/identity adapters are the server-backed ones in
        // `src/assessment/java`; `src/standard/java` (Room + SQLCipher) is only compiled into
        // debug/release. Task names stay `assembleDebug` / `testDebugUnitTest` for the standard
        // candidate, so the existing delivery evidence keeps its meaning.
        // No applicationIdSuffix: the shared Robolectric UI/screenshot tests resolve activities
        // from the merged manifest package, and a suffixed id makes every one of them fail with
        // "Unable to resolve activity". The assessment artifact is identified by this build type
        // and its APK path, not by a second package name.
        create("assessment") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug", "release")
            isMinifyEnabled = false
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

    sourceSets {
        // `standard` = the Room/SQLCipher-backed adapters shared by debug and release. Keeping them
        // out of `main` is what lets the assessment variant compile and package without Room.
        getByName("debug").java.srcDir("src/standard/java")
        getByName("release").java.srcDir("src/standard/java")
        getByName("assessment").java.srcDir("src/assessment/java")
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
                // Compose-based Robolectric screens (dialogs in particular) keep several
                // activity/window graphs alive per test; the default worker heap OOMs once a
                // class renders more than a handful of compositions.
                test.maxHeapSize = "2g"
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
    // The assessment variant is debuggable too, and its merged manifest needs the debug-only
    // ComponentActivity that `createComposeRule()` launches; without it every Robolectric UI test
    // fails to resolve the activity.
    "assessmentImplementation"(libs.androidx.compose.ui.tooling)
    "assessmentImplementation"(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // S4.4: Room/SQLCipher stay available for the standard (debug/release) variants only. The
    // assessment variant must not link, bundle or initialize them at all.
    "debugImplementation"(libs.androidx.room.runtime)
    "debugImplementation"(libs.androidx.room.ktx)
    "releaseImplementation"(libs.androidx.room.runtime)
    "releaseImplementation"(libs.androidx.room.ktx)
    "kspDebug"(libs.androidx.room.compiler)
    "kspRelease"(libs.androidx.room.compiler)

    "debugImplementation"(libs.sqlcipher.android)
    "debugImplementation"(libs.androidx.sqlite.ktx)
    "releaseImplementation"(libs.sqlcipher.android)
    "releaseImplementation"(libs.androidx.sqlite.ktx)

    implementation(libs.androidx.datastore.preferences)
    // Google SSO uses Credential Manager + the Google ID helper. The Play Services auth artifact
    // is a runtime/provider dependency of the former; both are stable releases.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Auto-sync scheduling: OS-owned periodic work, never a permanent foreground service.
    // S4.4: WorkManager is standard-only. Its WorkDatabase is Room-backed, so keeping it in the
    // assessment variant would re-introduce Room on the classpath no matter what our own code does.
    "debugImplementation"(libs.androidx.work.runtime.ktx)
    "releaseImplementation"(libs.androidx.work.runtime.ktx)
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
    "testDebugImplementation"(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
