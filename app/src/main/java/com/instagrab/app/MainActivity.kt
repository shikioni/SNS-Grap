package com.instagrab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.instagrab.app.data.auth.SessionManager
import com.instagrab.app.ui.MainScreen
import com.instagrab.app.ui.theme.InstaGrabTheme
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager

    // Permission launcher for Android 13+ Download notifications
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Download notification permission handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(applicationContext)

        checkPermissions()

        val sharedUrl = extractUrlFromIntent(intent)

        setContent {
            InstaGrabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        sessionManager = sessionManager,
                        initialUrl = sharedUrl.orEmpty()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUrl = extractUrlFromIntent(intent)
        if (!sharedUrl.isNullOrBlank()) {
            setContent {
                InstaGrabTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(
                            sessionManager = sessionManager,
                            initialUrl = sharedUrl
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            val urlPattern = Pattern.compile("https?://[^\\s]+")
            val matcher = urlPattern.matcher(sharedText)
            if (matcher.find()) {
                return matcher.group(0)
            }
            return sharedText.trim()
        }
        return null
    }
}
