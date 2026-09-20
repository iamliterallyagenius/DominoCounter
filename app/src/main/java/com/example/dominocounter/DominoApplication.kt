package com.example.dominocounter

import android.app.Application
import com.example.dominocounter.notifications.MatchNotifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class DominoApplication : Application() {

    @Inject lateinit var matchNotifier: MatchNotifier

    /** Outlives any single screen — the notifier must keep watching even with no UI open. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        matchNotifier.start(appScope)
    }
}
