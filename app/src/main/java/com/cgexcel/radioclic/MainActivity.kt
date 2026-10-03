/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.cgexcel.radioclic.playback.AppMessages
import com.cgexcel.radioclic.ui.AppViewModel
import com.cgexcel.radioclic.ui.RadioClicRoot
import com.cgexcel.radioclic.ui.RadioClicTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadioClicTheme {
                RadioClicRoot(viewModel)
            }
        }
        if (savedInstanceState == null) askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        AppMessages.uiVisible = true
    }

    override fun onPause() {
        AppMessages.uiVisible = false
        super.onPause()
    }

    /** Android 13+ : la notification de lecture (commandes) demande une autorisation. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
