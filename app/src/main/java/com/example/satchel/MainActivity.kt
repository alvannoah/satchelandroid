package com.example.satchel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.net.toUri
import com.example.satchel.ui.Navigator
import com.example.satchel.ui.SatchelApp
import com.example.satchel.ui.theme.SatchelTheme

class MainActivity : ComponentActivity() {
    private val navigator: Navigator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleSignInReturn(intent)
        setContent {
            SatchelTheme {
                // The Surface sets the background and the default text colour for every screen.
                Surface(color = MaterialTheme.colorScheme.background) {
                    SatchelApp(navigator, onOpenBrowser = ::openSignIn)
                }
            }
        }
    }

    // launchMode="singleTask": the browser's satchel://auth/callback arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSignInReturn(intent)
    }

    private fun handleSignInReturn(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "satchel" && uri.host == "auth" && uri.path == "/callback") {
            container.session.finishSso(uri)
        }
    }

    /**
     * Opens the provider's sign-in page in a Custom Tab (Google refuses sign-ins
     * inside a WebView). The extra below is the Custom Tabs protocol itself, so
     * no library is needed; browsers without Custom Tabs open a normal tab.
     */
    private fun openSignIn(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Install a web browser to sign in this way.", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    }
}
