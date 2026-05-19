package org.khord.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.khord.android.nav.Routes
import org.khord.android.push.KhordNotifications
import org.khord.android.ui.screens.AddContactScreen
import org.khord.android.ui.screens.ChatScreen
import org.khord.android.ui.screens.ContactListScreen
import org.khord.android.ui.screens.CreateGroupScreen
import org.khord.android.ui.screens.GroupChatScreen
import org.khord.android.ui.screens.GroupInfoScreen
import org.khord.android.ui.screens.RegistrationScreen
import org.khord.android.ui.screens.SeedConfirmScreen
import org.khord.android.ui.screens.SeedDisplayScreen
import org.khord.android.ui.screens.ServerSetupScreen
import org.khord.android.ui.screens.SettingsScreen
import org.khord.android.ui.screens.SplashScreen
import org.khord.android.ui.screens.WelcomeScreen
import org.khord.android.ui.theme.KhordTheme

class MainActivity : ComponentActivity() {

    /**
     * Pending chat-deep-link fingerprint pulled from the launch intent.
     * Read by [KhordNavGraph] in a LaunchedEffect once the NavHost is mounted.
     * Wrapped in a Compose mutableStateOf so a fresh onNewIntent triggers
     * navigation even when the Activity is already running (singleTop).
     */
    private val pendingDeepLinkFingerprint = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkFingerprint.value = extractDeepLinkFingerprint(intent)
        setContent {
            KhordTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KhordNavGraph(pendingDeepLinkFingerprint)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkFingerprint.value = extractDeepLinkFingerprint(intent)
    }

    private fun extractDeepLinkFingerprint(intent: Intent?): String? =
        intent?.getStringExtra(KhordNotifications.EXTRA_OPEN_CHAT_FINGERPRINT)
}

@Composable
private fun KhordNavGraph(
    pendingDeepLinkFingerprint: androidx.compose.runtime.MutableState<String?>,
) {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Respond to notification deep-links: when a fingerprint appears on
    // the pending state, navigate to the chat. Consume the value so the
    // same intent doesn't fire twice on configuration change.
    //
    // Dead-contact guard: if the target fingerprint isn't in the live
    // contact session list (e.g. the user panic-wiped and the
    // notification's PendingIntent points at an erased contact), drop
    // the user on the contact list with a brief toast rather than
    // routing to a chat screen that can't load.
    LaunchedEffect(pendingDeepLinkFingerprint.value) {
        val fp = pendingDeepLinkFingerprint.value ?: return@LaunchedEffect
        pendingDeepLinkFingerprint.value = null
        val contactExists = AppContainer.messaging
            ?.contacts()
            ?.any { it.contactFingerprint == fp } == true
        if (contactExists) {
            nav.navigate(Routes.chat(fp)) {
                // Don't pile up duplicate chat instances if the user keeps
                // tapping notifications.
                launchSingleTop = true
            }
        } else {
            Toast.makeText(
                context,
                "This conversation is no longer available.",
                Toast.LENGTH_SHORT,
            ).show()
            // Fall back to the contact list. Clear the entire back stack
            // — leaking the (now-gone) chat route, or Splash, would let
            // the user back-button into broken states.
            nav.navigate(Routes.CONTACTS) {
                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(nav) }
        composable(Routes.WELCOME) { WelcomeScreen(nav) }
        composable(Routes.SERVER_SETUP) { ServerSetupScreen(nav) }
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
        composable(Routes.CREATE_GROUP) { CreateGroupScreen(nav) }
        composable(
            route = Routes.GROUP_CHAT_PATTERN,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val gid = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupChatScreen(nav, groupId = gid)
        }
        composable(
            route = Routes.GROUP_INFO_PATTERN,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val gid = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupInfoScreen(nav, groupId = gid)
        }
    }
}
