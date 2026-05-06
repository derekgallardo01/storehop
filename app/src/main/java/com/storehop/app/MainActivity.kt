package com.storehop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.ui.auth.SignInScreen
import com.storehop.app.ui.theme.StorehopTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StorehopTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRouter()
                }
            }
        }
    }
}

/**
 * View-model exposing the simplest possible auth state for routing:
 *   - currentUid is null while the very first signInAnonymously is in flight
 *   - isAnonymous tells us whether the user has opted to upgrade
 *
 * We don't gate the placeholder on Google sign-in; the user can use the app
 * anonymously. SignInScreen is shown manually when the user wants to upgrade.
 */
@HiltViewModel
class RouterViewModel @Inject constructor(
    val session: UserSessionProvider,
    private val auth: FirebaseAuth,
) : ViewModel() {
    fun isAnonymous(): Boolean = auth.currentUser?.isAnonymous == true
}

@Composable
private fun AppRouter(viewModel: RouterViewModel = hiltViewModel()) {
    val uid by viewModel.session.userId.collectAsState()
    var showingSignIn by remember { mutableStateOf(false) }

    when {
        uid == null -> LoadingPlaceholder()
        showingSignIn -> SignInScreen(
            onContinue = { showingSignIn = false },
            onSkip = { showingSignIn = false },
        )
        else -> Placeholder(
            uid = uid!!,
            isAnonymous = viewModel.isAnonymous(),
            onUpgradeToGoogle = { showingSignIn = true },
        )
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Signing in...")
    }
}

@Composable
private fun Placeholder(
    uid: String,
    isAnonymous: Boolean,
    onUpgradeToGoogle: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(id = R.string.placeholder_screen_title))
            Text(text = "uid: ${uid.take(12)}...")
            Text(text = if (isAnonymous) "(anonymous)" else "(signed in with Google)")
            if (isAnonymous) {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                androidx.compose.material3.TextButton(onClick = onUpgradeToGoogle) {
                    Text("Sign in with Google")
                }
            }
        }
    }
}
