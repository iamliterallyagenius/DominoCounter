package com.example.dominocounter.detect

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps confirmed scan photos so they can become test and training data for the detector.
 *
 * The photo is the exact upright image the model was shown, and the confirmed total and the
 * model's own total are in the file name, so a folder of these is already a labelled test set:
 * `scan_20260919_143012_total14_model12.jpg` means the player settled on 14 pips and the model
 * had said 12.
 *
 * Owns its own scope so a save that starts just as the scanner closes isn't cancelled with the
 * screen. Failures are logged, not shown: this is an opt-in extra and must never get in the way
 * of committing the round.
 */
@Singleton
class ScanPhotoSaver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(jpeg: ByteArray, confirmedTotal: Int, modelTotal: Int) {
        val name = fileName(System.currentTimeMillis(), confirmedTotal, modelTotal)
        scope.launch {
            try {
                write(jpeg, name)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save scan photo $name", e)
            }
        }
    }

    private fun write(jpeg: ByteArray, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeToPictures(jpeg, name) else writeToAppFolder(jpeg, name)
    }

    /** Shows up in the gallery under Pictures/DominoScans; no storage permission needed. */
    private fun writeToPictures(jpeg: ByteArray, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the new image")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open $uri")
            out.use { it.write(jpeg) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-written, invisible file behind
            throw e
        }
    }

    /** Before Android 10 a gallery write needs a storage permission; the app's own folder doesn't. */
    private fun writeToAppFolder(jpeg: ByteArray, name: String) {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("External storage unavailable")
        val folder = File(base, FOLDER).apply { mkdirs() }
        File(folder, "$name.jpg").writeBytes(jpeg)
    }

    companion object {
        private const val TAG = "ScanPhotoSaver"
        const val FOLDER = "DominoScans"

        fun fileName(
            nowMillis: Long,
            confirmedTotal: Int,
            modelTotal: Int,
            timeZone: TimeZone = TimeZone.getDefault()
        ): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .apply { this.timeZone = timeZone }
                .format(Date(nowMillis))
            return "scan_${stamp}_total${confirmedTotal}_model${modelTotal}"
        }
    }
}
