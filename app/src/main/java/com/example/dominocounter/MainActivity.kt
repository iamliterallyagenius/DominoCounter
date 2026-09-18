package com.example.dominocounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity. It hosts the nav graph and does nothing else — every screen is a
 * Fragment, every piece of state lives in a ViewModel.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fine either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleMatchDeepLink(intent)
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMatchDeepLink(intent)
    }

    /** From the in-progress-match notification: jump straight into that match's scoreboard. */
    private fun handleMatchDeepLink(intent: Intent) {
        val matchId = intent.getLongExtra(EXTRA_MATCH_ID, -1L)
        if (matchId <= 0) return

        // Global by-id navigation, not a scoped action: this can fire from anywhere in the
        // app (whatever screen was open when the notification was tapped), and a scoped
        // action is only valid when the current destination already matches it.
        val navHost = supportFragmentManager.findFragmentById(R.id.navHost) as? NavHostFragment
        navHost?.navController?.navigate(R.id.match_graph, bundleOf("matchId" to matchId))
    }

    /** One-time, silent ask — the in-progress-match notification is simply skipped if denied. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_MATCH_ID = "matchId"
    }
}
