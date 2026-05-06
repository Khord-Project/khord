package org.khord.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.khord.android.nav.Routes
import org.khord.android.ui.screens.AddContactScreen
import org.khord.android.ui.screens.ChatScreen
import org.khord.android.ui.screens.ContactListScreen
import org.khord.android.ui.screens.RegistrationScreen
import org.khord.android.ui.screens.SeedConfirmScreen
import org.khord.android.ui.screens.SeedDisplayScreen
import org.khord.android.ui.screens.SettingsScreen
import org.khord.android.ui.screens.SplashScreen
import org.khord.android.ui.screens.WelcomeScreen
import org.khord.android.ui.theme.KhordTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KhordTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KhordNavGraph()
                }
            }
        }
    }
}

@Composable
private fun KhordNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(nav) }
        composable(Routes.WELCOME) { WelcomeScreen(nav) }
        composable(Routes.SEED_DISPLAY) { SeedDisplayScreen(nav) }
        composable(Routes.SEED_CONFIRM) { SeedConfirmScreen(nav) }
        composable(Routes.REGISTRATION) { RegistrationScreen(nav) }
        composable(Routes.CONTACTS) { ContactListScreen(nav) }
        composable(Routes.ADD_CONTACT) { AddContactScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(
            route = Routes.CHAT_PATTERN,
            arguments = listOf(navArgument("fingerprint") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fp = backStackEntry.arguments?.getString("fingerprint") ?: return@composable
            ChatScreen(nav, contactFingerprint = fp)
        }
    }
}
