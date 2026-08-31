package com.sih.itantra.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device store of captured utterances as WAV files, for the AI team's benchmarking loop.
 *
 * Accuracy is 40% of the score, and word-error rate can only be measured on audio that came out
 * of a real handset — mic response, room noise and codec path all move WER, and none of them
 * are reproducible from a public test set. This makes a recording session a one-tap affair:
 * turn it on, talk, then
 *
 *     adb pull /sdcard/Android/data/com.sih.itantra/files/captures
 *
 * No storage permission is involved: [Context.getExternalFilesDir] is the app's own directory
 * on every supported API level, and it is world-readable over adb.
 */
class CaptureArchive(private val context: Context) {

    val directory: File?
        get() = context.getExternalFilesDir(DIR_NAME)?.also { if (!it.exists()) it.mkdirs() }

    /**
     * Write [samples] to a new timestamped WAV. Returns the file, or null if external storage
     * is unavailable — a failed dump must never take the capture path down with it.
     */
    fun write(samples: ShortArray, label: String = "utt"): File? {
        val dir = directory ?: return null
        return try {
            val stamp = SimpleDateFormat(STAMP_FORMAT, Locale.US).format(Date())
            val file = File(dir, "$label-$stamp.wav")
            WavWriter(file).use { it.write(samples) }
            Log.d(TAG, "wrote ${file.name} (${AudioSpec.millisOf(samples.size)} ms)")
            file
        } catch (e: Exception) {
            Log.w(TAG, "capture dump failed: ${e.message}")
            null
        }
    }

    fun list(): List<File> =
        directory?.listFiles { f -> f.isFile && f.extension == "wav" }?.sortedBy { it.name }.orEmpty()

    fun totalBytes(): Long = list().sumOf { it.length() }

    fun clear(): Int {
        val files = list()
        files.forEach { runCatching { it.delete() } }
        return files.size
    }

    private companion object {
        const val TAG = "CaptureArchive"
        const val DIR_NAME = "captures"
        const val STAMP_FORMAT = "yyyyMMdd-HHmmss-SSS"
    }
}
