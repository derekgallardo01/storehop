package com.storehop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.ui.items.ItemFormScreen
import com.storehop.app.ui.items.ItemsListScreen
import com.storehop.app.ui.nav.Routes
import com.storehop.app.ui.shop.StorePickerScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
                    AppRoot()
                }
            }
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    val session: UserSessionProvider,
    private val auth: FirebaseAuth,
) : ViewModel() {
    fun isAnonymous(): Boolean = auth.currentUser?.isAnonymous == true
}

@Composable
private fun AppRoot(viewModel: RootViewModel = hiltViewModel()) {
    val uid by viewModel.session.userId.collectAsState()
    when (uid) {
        null -> LoadingPlaceholder()
        else -> SignedInRoot()
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

/**
 * Two-tab scaffold: Shop and Items. Each tab is a separate Compose Navigation
 * branch under a single `NavHost`; tapping a bottom-nav item navigates to that
 * tab's root and resets to the saved state for that tab (so going Shop → ...
 * → Items → Shop returns you where you were in the Shop tab).
 */
@Composable
private fun SignedInRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = Routes.isShopTabRoute(currentRoute),
                    onClick = {
                        navController.navigate(Routes.SHOP) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Storefront, contentDescription = null) },
                    label = { Text("Shop") },
                )
                NavigationBarItem(
                    selected = Routes.isItemsTabRoute(currentRoute),
                    onClick = {
                        navController.navigate(Routes.ITEMS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Items") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SHOP,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SHOP) { StorePickerScreen() }
            composable(Routes.SHOP_AT_STORE) {
                // M2.2 lands the real screen; for now just a placeholder.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Shop-at-Store (M2.2)")
                }
            }

            composable(Routes.ITEMS) {
                ItemsListScreen(
                    onAddItem = { navController.navigate(Routes.ITEM_ADD) },
                    onEditItem = { id -> navController.navigate(Routes.itemEdit(id)) },
                )
            }
            composable(Routes.ITEM_ADD) {
                ItemFormScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ITEM_EDIT,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) {
                ItemFormScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
