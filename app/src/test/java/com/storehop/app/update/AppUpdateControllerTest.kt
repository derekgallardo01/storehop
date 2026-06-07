package com.storehop.app.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * Pins the in-app update controller's three branches inside `checkForUpdate`,
 * the listener register / unregister contract, and the documented DOWNLOADED
 * flip from the install listener. Mike-reported regression coverage:
 * a re-prompt while an install is already PENDING/DOWNLOADING/INSTALLING
 * caused the "double-tap Update" bug, so each in-progress status is asserted
 * independently rather than collapsed.
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateControllerTest {

    private fun pumpMain() = shadowOf(Looper.getMainLooper()).idle()


    private val manager: AppUpdateManager = mockk(relaxed = true)
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)

    private fun fakeInfo(
        installStatus: Int = InstallStatus.UNKNOWN,
        availability: Int = UpdateAvailability.UPDATE_NOT_AVAILABLE,
        flexibleAllowed: Boolean = true,
        availableVersionCode: Int = 0,
    ): AppUpdateInfo {
        val info = mockk<AppUpdateInfo>(relaxed = true)
        every { info.installStatus() } returns installStatus
        every { info.updateAvailability() } returns availability
        every { info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) } returns flexibleAllowed
        every { info.availableVersionCode() } returns availableVersionCode
        return info
    }

    @Test fun `start registers the install listener`() {
        val controller = AppUpdateController(manager)
        controller.start()
        verify { manager.registerListener(any<InstallStateUpdatedListener>()) }
    }

    @Test fun `stop unregisters the install listener`() {
        val controller = AppUpdateController(manager)
        controller.start()
        controller.stop()
        verify { manager.unregisterListener(any<InstallStateUpdatedListener>()) }
    }

    @Test fun `install listener flips isUpdateReadyToInstall when status becomes DOWNLOADED`() {
        val captured = slot<InstallStateUpdatedListener>()
        every { manager.registerListener(capture(captured)) } returns Unit

        val controller = AppUpdateController(manager)
        controller.start()
        assertThat(controller.isUpdateReadyToInstall.value).isFalse()

        val downloaded: InstallState = mockk(relaxed = true)
        every { downloaded.installStatus() } returns InstallStatus.DOWNLOADED
        captured.captured.onStateUpdate(downloaded)
        assertThat(controller.isUpdateReadyToInstall.value).isTrue()
    }

    @Test fun `checkForUpdate with DOWNLOADED skips startUpdateFlow and flips isUpdateReadyToInstall`() {
        every { manager.appUpdateInfo } returns Tasks.forResult(
            fakeInfo(installStatus = InstallStatus.DOWNLOADED),
        )

        val controller = AppUpdateController(manager)
        controller.checkForUpdate(launcher)
        pumpMain()

        assertThat(controller.isUpdateReadyToInstall.value).isTrue()
        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate with PENDING is a no-op (no re-prompt while install in progress)`() {
        every { manager.appUpdateInfo } returns Tasks.forResult(
            fakeInfo(
                installStatus = InstallStatus.PENDING,
                availability = UpdateAvailability.UPDATE_AVAILABLE,
            ),
        )

        val controller = AppUpdateController(manager)
        controller.checkForUpdate(launcher)
        pumpMain()

        assertThat(controller.isUpdateReadyToInstall.value).isFalse()
        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate with DOWNLOADING is a no-op`() {
        every { manager.appUpdateInfo } returns Tasks.forResult(
            fakeInfo(
                installStatus = InstallStatus.DOWNLOADING,
                availability = UpdateAvailability.UPDATE_AVAILABLE,
            ),
        )

        AppUpdateController(manager).checkForUpdate(launcher)
        pumpMain()

        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate with INSTALLING is a no-op`() {
        every { manager.appUpdateInfo } returns Tasks.forResult(
            fakeInfo(
                installStatus = InstallStatus.INSTALLING,
                availability = UpdateAvailability.UPDATE_AVAILABLE,
            ),
        )

        AppUpdateController(manager).checkForUpdate(launcher)
        pumpMain()

        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate with UPDATE_AVAILABLE and flexible allowed launches the flow`() {
        val info = fakeInfo(
            installStatus = InstallStatus.UNKNOWN,
            availability = UpdateAvailability.UPDATE_AVAILABLE,
            flexibleAllowed = true,
            availableVersionCode = 99,
        )
        every { manager.appUpdateInfo } returns Tasks.forResult(info)

        AppUpdateController(manager).checkForUpdate(launcher)
        pumpMain()

        verify(exactly = 1) {
            manager.startUpdateFlowForResult(info, launcher, any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate with UPDATE_AVAILABLE but flexible NOT allowed does not launch`() {
        every { manager.appUpdateInfo } returns Tasks.forResult(
            fakeInfo(
                availability = UpdateAvailability.UPDATE_AVAILABLE,
                flexibleAllowed = false,
            ),
        )

        AppUpdateController(manager).checkForUpdate(launcher)
        pumpMain()

        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `checkForUpdate swallows appUpdateInfo failure (sideloaded path)`() {
        every { manager.appUpdateInfo } returns Tasks.forException(
            RuntimeException("App is not owned by any user on this device"),
        )

        // Must not throw.
        AppUpdateController(manager).checkForUpdate(launcher)
        pumpMain()

        verify(exactly = 0) {
            manager.startUpdateFlowForResult(any<AppUpdateInfo>(), any(), any<AppUpdateOptions>())
        }
    }

    @Test fun `completeUpdate delegates to AppUpdateManager`() {
        AppUpdateController(manager).completeUpdate()
        verify { manager.completeUpdate() }
    }

    // ---- v0.6.3: race-window guard ----------------------------------------

    @Test fun `second checkForUpdate within the same activity does NOT re-launch the sheet even when installStatus is still UNKNOWN`() {
        // The Play race: user tapped Update on the bottom sheet, the
        // dialog dismissed and onResume fired BEFORE Play registered the
        // transition to PENDING. installStatus() is still UNKNOWN with
        // updateAvailability() still UPDATE_AVAILABLE. Without the
        // session flag, this would re-prompt -- Mike's "I have to hit
        // Update twice" regression.
        val info = fakeInfo(
            installStatus = InstallStatus.UNKNOWN,
            availability = UpdateAvailability.UPDATE_AVAILABLE,
            flexibleAllowed = true,
        )
        every { manager.appUpdateInfo } returns Tasks.forResult(info)

        val controller = AppUpdateController(manager)
        controller.checkForUpdate(launcher)
        pumpMain()
        // First call launched the sheet.
        verify(exactly = 1) {
            manager.startUpdateFlowForResult(info, launcher, any<AppUpdateOptions>())
        }

        // Simulated onResume re-entry: Play still says UNKNOWN /
        // UPDATE_AVAILABLE, but we've already prompted this activity.
        controller.checkForUpdate(launcher)
        pumpMain()
        // Still ONE launch total -- second call short-circuited.
        verify(exactly = 1) {
            manager.startUpdateFlowForResult(info, launcher, any<AppUpdateOptions>())
        }
    }

    @Test fun `stop clears the session flag so a fresh activity instance can prompt again`() {
        val info = fakeInfo(
            installStatus = InstallStatus.UNKNOWN,
            availability = UpdateAvailability.UPDATE_AVAILABLE,
            flexibleAllowed = true,
        )
        every { manager.appUpdateInfo } returns Tasks.forResult(info)

        val controller = AppUpdateController(manager)
        controller.checkForUpdate(launcher)
        pumpMain()
        controller.stop()
        // After stop (activity destroyed) the next checkForUpdate
        // on the SAME controller instance is allowed to prompt again --
        // mirrors what happens when MainActivity is recreated.
        controller.checkForUpdate(launcher)
        pumpMain()
        verify(exactly = 2) {
            manager.startUpdateFlowForResult(info, launcher, any<AppUpdateOptions>())
        }
    }
}
