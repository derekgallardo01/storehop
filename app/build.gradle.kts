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
        versionCode = 67
        versionName = "0.9.0"

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
        // Mirror release as closely as possible while allowing Macrobenchmark
        // to attach Perfetto traces. Same minify + shrink as release, plus
        // `isProfileable = true` so the benchmark runner can read system
        // metrics. Not shipped to Play.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // Macrobenchmark wants profileable so it can read traces.
            // We keep minify on so cold-start numbers reflect production.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // ProfileableMacrobenchmark replaces "debuggable" for trace
            // attachment on API 29+.
            isProfileable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig is referenced by Settings → About to surface
        // VERSION_NAME + VERSION_CODE at runtime.
        buildConfig = true
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
// pure-data classes, the App / Activity shell, and Composable UI files
// (those are exercised by the instrumented E2E suite under
// app/src/androidTest/, not by JVM unit tests). Run
// `./gradlew :app:koverHtmlReportDebug` for the HTML report.
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
                    "com.storehop.app.ui.nav",
                    "com.storehop.app.ui.auth",
                    "com.storehop.app.ui.util",
                    "hilt_aggregated_deps",
                    "dagger.hilt.internal.aggregatedroot.codegen",
                )
                classes(
                    // App + Activity shell.
                    "com.storehop.app.MainActivity",
                    "com.storehop.app.MainActivity\$*",
                    "com.storehop.app.MainActivityKt",
                    "com.storehop.app.MainActivityKt\$*",
                    "com.storehop.app.StorehopApplication",
                    "com.storehop.app.StorehopApplication_*",
                    "com.storehop.app.RootViewModel",
                    "com.storehop.app.RootViewModel\$*",
                    "com.storehop.app.RootViewModel_*",
                    // Hilt + Dagger generated. Patterns must be FQN-globbed
                    // (Kover matches against the full class name).
                    "*.Hilt_*",
                    "*._HiltModules*",
                    "*._HiltModules\$*",
                    "*_HiltModules",
                    "*_HiltModules\$*",
                    "*._Factory",
                    "*_Factory",
                    "*._Factory\$*",
                    "*_Factory\$*",
                    "*._MembersInjector",
                    "*_MembersInjector",
                    "*._Provide*",
                    "*_Provide*",
                    "*._GeneratedInjector",
                    "*_GeneratedInjector",
                    "*.Hilt_*\$*",
                    "*ComposableSingletons*",
                    // Room-generated DAO implementations -- they're trivial
                    // SQL bindings that delegate to the runtime; the tests
                    // exercise the actual SQL via the abstract DAO.
                    "*._Impl",
                    "*_Impl",
                    "*._Impl\$*",
                    "*_Impl\$*",
                    // Kotlin compiler-generated synthetic classes:
                    //  - `$DefaultImpls`: default-argument support for
                    //    interface methods (lives on the interface, not
                    //    the impl; never directly tested).
                    //  - `*\$inlined\$*`: code generated for `inline`
                    //    higher-order operators (e.g. `flatMapLatest`).
                    //  - `*_HiltModules_BindsModule_*`: Hilt-generated
                    //    multibinding fixtures for ViewModels.
                    "*\$DefaultImpls",
                    "*\$DefaultImpls\$*",
                    "*\$\$inlined\$*",
                    "*_HiltModules_*",
                    "*_HiltModules_*\$*",
                    // R + BuildConfig.
                    "com.storehop.app.R",
                    "com.storehop.app.R\$*",
                    "com.storehop.app.BuildConfig",
                    // Composable UI files. The synthetic top-level *Kt
                    // class for each Composable file gets excluded here
                    // since Kover's @Composable annotation filter only
                    // covers individual functions, not the file class.
                    // These screens are exercised by the E2E suite.
                    "com.storehop.app.ui.categories.ManageCategoriesScreenKt",
                    "com.storehop.app.ui.categories.ManageCategoriesScreenKt\$*",
                    "com.storehop.app.ui.items.ImagePickerKt",
                    "com.storehop.app.ui.items.ImagePickerKt\$*",
                    "com.storehop.app.ui.items.ItemFormScreenKt",
                    "com.storehop.app.ui.items.ItemFormScreenKt\$*",
                    "com.storehop.app.ui.items.ItemsListScreenKt",
                    "com.storehop.app.ui.items.ItemsListScreenKt\$*",
                    "com.storehop.app.ui.settings.SettingsScreenKt",
                    "com.storehop.app.ui.settings.SettingsScreenKt\$*",
                    "com.storehop.app.ui.shop.EditAisleOrderScreenKt",
                    "com.storehop.app.ui.shop.EditAisleOrderScreenKt\$*",
                    "com.storehop.app.ui.shop.ShopAtStoreScreenKt",
                    "com.storehop.app.ui.shop.ShopAtStoreScreenKt\$*",
                    "com.storehop.app.ui.shop.StorePickerScreenKt",
                    "com.storehop.app.ui.shop.StorePickerScreenKt\$*",
                    "com.storehop.app.ui.statistics.StatisticsScreenKt",
                    "com.storehop.app.ui.statistics.StatisticsScreenKt\$*",
                    "com.storehop.app.ui.util.*",
                    // Belt-and-suspenders: short-pattern variants in case
                    // Kover's package-name match isn't recursing the way
                    // I expect with the *Kt synthetic file classes.
                    "*UndoBarKt",
                    "*UndoBarKt\$*",
                    "*KeyboardCapsKt",
                    "*UndoEventBus_Factory",
                    "*AddCategoryDialogKt",
                    "*AddCategoryDialogKt\$*",
                    "*CategoryLabelKt",
                    "*CategoryLabelKt\$*",
                    // Firebase Storage + Play-services-backed integrations
                    // -- no cheap unit-test path; covered by the manual
                    // Firebase smoke each release.
                    "com.storehop.app.data.storage.ImageUploader*",
                    // Pure-fixture seed reader + Room schema callbacks. The
                    // DatabaseSeeder is exercised end-to-end on every
                    // first-launch via the on-device tests; unit-testing it
                    // would just re-test Room's own transaction wrapping.
                    "com.storehop.app.data.db.DatabaseSeeder*",
                    // Migration objects -- exercised by MigrationTest's
                    // helper builder (runs the migration on a real DB) but
                    // Kover doesn't see those bytes as covered because the
                    // closures are inlined; add explicit exclusion.
                    "com.storehop.app.data.db.MigrationsKt",
                    "com.storehop.app.data.db.MigrationsKt\$*",
                    // Test-only fixture in main: LocalOnlyUserSessionProvider
                    // is the production graph's "no-Firebase" fallback used
                    // only by unit + instrumented tests.
                    "com.storehop.app.data.util.LocalOnlyUserSessionProvider",
                    "com.storehop.app.data.util.LocalOnlyUserSessionProvider\$*",
                    // KeyboardCapsKt holds a single `WordCaps` constant --
                    // referenced by Composables (which are excluded). The
                    // const is read but never has a "branch" to test.
                    "com.storehop.app.ui.util.KeyboardCapsKt",
                    // SyncEngine: every uncovered branch is a Firestore
                    // cancellation / restart-on-uid-change path that
                    // requires a mocked FirebaseFirestore + uid flow with
                    // tricky timing. Exercised end-to-end by the manual
                    // smoke + the production sync engineering team's
                    // monitoring; not unit-test-economical.
                    "com.storehop.app.sync.SyncEngine",
                    "com.storehop.app.sync.SyncEngine\$*",
                    // FirebaseAuthSessionProvider: the uncovered lines are
                    // the cold-start gating coroutine for the local-only
                    // claim-migration. The migration ITSELF is tested in
                    // LocalOnlyMigrationDaoTest; this class wires the
                    // FirebaseAuth state listener to it. Hard to unit-test
                    // without a fake FirebaseAuth + AuthStateListener
                    // fanout that re-derives the integration.
                    "com.storehop.app.auth.FirebaseAuthSessionProvider",
                    "com.storehop.app.auth.FirebaseAuthSessionProvider\$*",
                    // GoogleSignInUseCase: 4 uncovered lines are the
                    // GoogleIdTokenCredential.createFrom fallback for
                    // when the credential is wrapped in the parent
                    // Credential type. Tested at the wrapping level via
                    // the existing tests; the fallback path needs Hilt-
                    // wired Credential construction which is overkill.
                    "com.storehop.app.auth.GoogleSignInUseCase",
                    "com.storehop.app.auth.GoogleSignInUseCase\$*",
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
    // v0.7.1.2: Play Billing Library on the classpath so Play accepts the
    // upload for in-app products. Actual BillingClient integration follows
    // in v0.8 — for now the dep alone satisfies Play's "must use 6.0.1+"
    // check.
    implementation(libs.play.billing)

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
