import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kover)
}

// Release signing config -- read from `keystore.properties` at the repo root
// (not in git). When the file is absent (CI / fresh checkout), the release
// build is left unsigned and won't install, but the build itself still
// succeeds so unit tests + assembleDebug stay reproducible.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.storehop.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.storehop.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.6.1"

        // Custom runner swaps in HiltTestApplication so @HiltAndroidTest works.
        testInstrumentationRunner = "com.storehop.app.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Bundle native debug symbols so Play Console can symbolicate
            // crashes/ANRs in the third-party native code we ship (Firebase
            // SDKs, Compose runtime). SYMBOL_TABLE is the smaller variant --
            // function names but no source line numbers, which is plenty
            // for the kinds of stack traces we'd actually see.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // mockk-android (used in androidTest) pulls in junit-jupiter
            // which ships its own LICENSE.md; collides with other deps.
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    // AAB splits resources by language by default; Play Store then delivers
    // only the language packs matching the user's preinstalled system
    // locales. On Pixel devices the user's "Languages" list might be just
    // English, in which case Play strips the other locale resources from
    // their on-device install -- our in-app picker can no longer find
    // values-it/, values-pt-rPT/, etc. Disable the language split so the
    // base APK in the AAB always carries every locale we ship.
    bundle {
        language {
            enableSplit = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

// Kover line-coverage config. Excludes generated Hilt code, Room entities,
// pure-data classes, theme/preview-only Composables, and the App / Activity
// shell -- the things that add green-bar noise without adding signal.
// Run `./gradlew :app:koverHtmlReportDebug` for the HTML report.
kover {
    reports {
        filters {
            excludes {
                packages(
                    "com.storehop.app.di",
                    "com.storehop.app.data.entity",
                    "com.storehop.app.data.db.relations",
                    "com.storehop.app.sync.dto",
                    "com.storehop.app.ui.theme",
                    "hilt_aggregated_deps",
                    "dagger.hilt.internal.aggregatedroot.codegen",
                )
                classes(
                    "com.storehop.app.MainActivity",
                    "com.storehop.app.MainActivity\$*",
                    "com.storehop.app.StorehopApplication",
                    "com.storehop.app.StorehopApplication_*",
                    "*_Hilt*",
                    "*_HiltModules*",
                    "*_Factory",
                    "*_MembersInjector",
                    "*_Provide*",
                    "*_GeneratedInjector",
                    "Hilt_*",
                    "*ComposableSingletons*",
                    // R + BuildConfig.
                    "com.storehop.app.R",
                    "com.storehop.app.R\$*",
                    "com.storehop.app.BuildConfig",
                )
                annotatedBy(
                    "androidx.compose.runtime.Composable",
                    "androidx.compose.ui.tooling.preview.Preview",
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.appcompat)
    implementation(libs.play.app.update.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    kspAndroidTest(libs.hilt.compiler)
}
