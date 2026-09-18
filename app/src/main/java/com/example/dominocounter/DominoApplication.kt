package com.example.dominocounter

import android.app.Application
import android.util.Log
import com.example.dominocounter.notifications.MatchNotifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

@HiltAndroidApp
class DominoApplication : Application() {

    @Inject lateinit var matchNotifier: MatchNotifier

    /** Outlives any single screen — the notifier must keep watching even with no UI open. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Synchronous, native-only load — no OpenCV Manager APK, no BaseLoaderCallback.
        openCvLoaded = OpenCVLoader.initLocal()
        if (!openCvLoaded) {
            Log.e("DominoApplication", "OpenCV failed to load; the scanner will be unavailable")
        }

        matchNotifier.start(appScope)
    }

    companion object {
        var openCvLoaded: Boolean = false
            private set
    }
}
