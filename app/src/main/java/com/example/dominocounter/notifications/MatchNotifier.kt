package com.example.dominocounter.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.dominocounter.MainActivity
import com.example.dominocounter.R
import com.example.dominocounter.data.repo.MatchRepository
import com.example.dominocounter.domain.MatchEngine
import com.example.dominocounter.domain.model.MatchState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A dismissible, silent notification pointing at whichever match is currently IN_PROGRESS,
 * with a live score line. The score can only change while the app is in the foreground —
 * there's no remote play — so this just reacts to the same "resumable match" flow the main
 * menu's Resume card uses, no foreground service required.
 */
@Singleton
class MatchNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matchRepository: MatchRepository
) {

    /** Runs for as long as [scope] lives — call once, from the application's own scope. */
    fun start(scope: CoroutineScope) {
        createChannel()
        observeCurrentMatch()
            .onEach { state -> if (state == null) cancel() else post(state) }
            .launchIn(scope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCurrentMatch() =
        matchRepository.observeResumable()
            .flatMapLatest { summary ->
                if (summary == null) {
                    flowOf(null)
                } else {
                    matchRepository.observeMatch(summary.id).map { it?.let(MatchEngine::reduce) }
                }
            }
            .distinctUntilChanged()

    private fun post(state: MatchState) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val scoreLine = state.sides.joinToString("  ·  ") { "${it.label} ${it.score}" }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_MATCH_ID, state.matchId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_content, scoreLine))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** LOW importance: shows silently in the shade, never a sound/heads-up per round added. */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "match_in_progress"
        const val NOTIFICATION_ID = 1001
        const val PENDING_INTENT_REQUEST_CODE = 2001
    }
}
