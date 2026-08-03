package com.moodecho.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.moodecho.app.ui.navigation.MindEchoNavHost
import com.moodecho.app.ui.theme.MindEchoTheme

/**
 * Main activity that hosts the Compose UI with navigation.
 * Manages runtime permission requests for RECORD_AUDIO and POST_NOTIFICATIONS.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MindEchoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MindEchoApp()
                }
            }
        }
    }
}

/**
 * Top-level composable that handles permission state and navigation.
 * Permission requests are triggered here and the result is passed
 * down to screens that need it.
 */
@Composable
fun MindEchoApp() {
    val context = LocalContext.current

    // Track whether all required permissions have been granted
    var hasAllPermissions by rememberSaveable { mutableStateOf(false) }

    // Build the list of permissions we need to request at runtime
    val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAllPermissions = requiredPermissions.all { perm ->
            permissions[perm] == true
        }
    }

    // Check if permissions are already granted (e.g. after rotation or returning from settings)
    val currentlyGranted = requiredPermissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    if (currentlyGranted && !hasAllPermissions) {
        hasAllPermissions = true
    }

    MindEchoNavHost(
        hasAllPermissions = hasAllPermissions,
        onRequestPermissions = {
            // Check if already granted first
            val allGranted = requiredPermissions.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                hasAllPermissions = true
            } else {
                permissionLauncher.launch(requiredPermissions.toTypedArray())
            }
        }
    )
}
