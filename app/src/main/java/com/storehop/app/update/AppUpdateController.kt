package com.storehop.app.update

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the Play Store **in-app updates** flow. Lifecycle-bound to a single
 * Activity (MainActivity) since the update prompt is launched via an
 * `IntentSender` returned from Play.
 *
 * Flow:
 *  1. [checkForUpdate] is called from `Activity.onResume`. If Play reports a
 *     newer build is available and Flexible updates are allowed, the Play
 *     update sheet is shown.
 *  2. The user accepts → Play downloads the new APK in the background while
 *     the user continues using the app.
 *  3. Download completes → [isUpdateReadyToInstall] flips true. UI surfaces a
 *     snackbar with "Restart" so the user installs whenever they're ready.
 *  4. User taps Restart → [completeUpdate] triggers Play to install the new
 *     APK and relaunch the app.
 *
 * On a debug build (or any build installed from outside Play), Play returns
 * `UPDATE_NOT_AVAILABLE` from the start, so this is silently a no-op.
 */
class AppUpdateController(
    private val appUpdateManager: AppUpdateManager,
) {
    private val _isUpdateReadyToInstall = MutableStateFlow(false)
    val isUpdateReadyToInstall: StateFlow<Boolean> = _isUpdateReadyToInstall.asStateFlow()

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            _isUpdateReadyToInstall.value = true
        }
    }

    fun start() {
        appUpdateManager.registerListener(installListener)
    }

    fun stop() {
        appUpdateManager.unregisterListener(installListener)
    }

    /**
     * Probe Play for an available update and prompt the user. Handles the
     * three relevant states:
     *  1. Install is DOWNLOADED — surface the "Restart now" overlay; don't
     *     re-prompt the bottom sheet.
     *  2. Install is in progress (PENDING / DOWNLOADING / INSTALLING) — the
     *     user already accepted in a prior `onResume`; do nothing. (Without
     *     this, returning to the activity after tapping Update on Play's
     *     bottom sheet re-triggered `startUpdateFlowForResult` and showed a
     *     second sheet — Mike-reported "I have to hit Update twice".)
     *  3. UPDATE_AVAILABLE and no install activity yet — launch the flow.
     */
    fun checkForUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        Log.i(TAG, "checkForUpdate(): probing Play")
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                Log.i(
                    TAG,
                    "appUpdateInfo: installStatus=${info.installStatus()} " +
                        "updateAvailability=${info.updateAvailability()} " +
                        "availableVersionCode=${info.availableVersionCode()} " +
                        "flexibleAllowed=${info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}",
                )
                when (info.installStatus()) {
                    InstallStatus.DOWNLOADED -> {
                        _isUpdateReadyToInstall.value = true
                        return@addOnSuccessListener
                    }
                    InstallStatus.PENDING,
                    InstallStatus.DOWNLOADING,
                    InstallStatus.INSTALLING -> {
                        // Flow already in progress; let it finish.
                        return@addOnSuccessListener
                    }
                }
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    Log.i(TAG, "Launching FLEXIBLE update flow for vc=${info.availableVersionCode()}")
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                } else {
                    Log.i(TAG, "No update prompt: availability says no update, or flexible not allowed.")
                }
            }
            .addOnFailureListener { e ->
                // Sideloaded / debug builds throw RuntimeException("App is not
                // owned by any user on this device"). Logged at WARN now so
                // it's visible without a debug-level filter.
                Log.w(TAG, "appUpdateInfo failed (typical on sideloaded / non-Play installs): ${e.message}")
            }
    }

    /** Call from the snackbar's "Restart" action. */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    private companion object {
        const val TAG = "AppUpdateController"
    }
}
