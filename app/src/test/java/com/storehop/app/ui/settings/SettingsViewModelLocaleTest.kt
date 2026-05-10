package com.storehop.app.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.auth.GoogleSignInUseCase
import com.storehop.app.data.prefs.ThemeMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locale-flow coverage for [SettingsViewModel]. The main test class
 * uses a fully-mocked Context, which short-circuits the SDK 33+
 * `LocaleManager`-backed branches inside `setLocale` + `readLocaleTag`.
 * Robolectric's real ApplicationContext provides a working
 * LocaleManager so those branches actually execute under test.
 *
 * Lives in its own file so the rest of the suite stays on the plain
 * JUnit runner -- mixing Robolectric there broke ~10 existing tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelLocaleTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val realContext: Context = ApplicationProvider.getApplicationContext()
    private val auth: FirebaseAuth = mockk(relaxed = true)
    private val googleSignIn: GoogleSignInUseCase = mockk()
    private val userPrefs: UserPreferencesRepository = mockk(relaxed = true) {
        every { themeMode } returns flowOf(ThemeMode.SYSTEM)
    }
    private val pullCoordinator: PullCoordinator = mockk(relaxed = true)
    private val pullStateRepo: PullStateRepository = mockk(relaxed = true) {
        every { observe(any()) } returns flowOf(PullState.SUCCEEDED)
    }

    private fun newVm() = SettingsViewModel(
        appContext = realContext,
        auth = auth,
        googleSignIn = googleSignIn,
        userPrefs = userPrefs,
        sessionProvider = FakeSessionProvider("u"),
        pullCoordinator = pullCoordinator,
        pullStateRepo = pullStateRepo,
    )

    @Test fun `setLocale TIRAMISU+ branch writes to LocaleManager and reads back`() = runTest {
        val vm = newVm()
        vm.setLocale("pt-PT")

        // Robolectric's real Context backs a working LocaleManager.
        val lm = realContext.getSystemService(LocaleManager::class.java)
        assertThat(lm.applicationLocales.toLanguageTags()).contains("pt")
        // VM-side state mirrors the write.
        assertThat(vm.currentLocaleTag.value).isEqualTo("pt-PT")
    }

    @Test fun `setLocale empty TIRAMISU+ branch clears the LocaleManager override`() = runTest {
        val vm = newVm()
        // Seed a non-empty override first.
        vm.setLocale("es")
        // Then clear.
        vm.setLocale("")

        val lm = realContext.getSystemService(LocaleManager::class.java)
        assertThat(lm.applicationLocales.isEmpty).isTrue()
        assertThat(vm.currentLocaleTag.value).isEmpty()
    }

    @Test fun `readLocaleTag TIRAMISU+ branch reads back from LocaleManager on construction`() = runTest {
        // Pre-seed the LocaleManager directly.
        val lm = realContext.getSystemService(LocaleManager::class.java)
        lm.applicationLocales = LocaleList.forLanguageTags("it")

        val vm = newVm()
        // The init-time readLocaleTag picked up the pre-seeded value.
        assertThat(vm.currentLocaleTag.value).contains("it")

        // Cleanup: reset to empty so other tests don't see leftover state.
        lm.applicationLocales = LocaleList.getEmptyLocaleList()
    }
}
