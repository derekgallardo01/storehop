package com.storehop.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.auth.GoogleSignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Continue-with-Google sheet. Anonymous users see this from the
 * placeholder screen as an "upgrade" affordance. After successful sign-in
 * the user is bumped back to the main router via [onContinue].
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val googleSignIn: GoogleSignInUseCase,
) : ViewModel() {

    var isInProgress by androidx.compose.runtime.mutableStateOf(false)
        private set

    var lastError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun signIn(activityContext: android.content.Context, onSuccess: () -> Unit) {
        if (isInProgress) return
        isInProgress = true
        lastError = null
        viewModelScope.launch {
            googleSignIn.signIn(activityContext)
                .onSuccess { onSuccess() }
                .onFailure { lastError = it.message ?: "Sign-in failed" }
            isInProgress = false
        }
    }
}

@Composable
fun SignInScreen(
    onContinue: () -> Unit,
    onSkip: (() -> Unit)? = null,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Sign in to back up your lists",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your data syncs to your Google account so you can pick up the same lists on another device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { viewModel.signIn(ctx) { onContinue() } },
            enabled = !viewModel.isInProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (viewModel.isInProgress) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.height(20.dp),
                )
            } else {
                Text("Continue with Google")
            }
        }

        if (onSkip != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip, enabled = !viewModel.isInProgress) {
                Text("Continue without an account")
            }
        }

        viewModel.lastError?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
