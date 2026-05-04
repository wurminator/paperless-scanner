package com.paperless.scanner.quickupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickUploadHandler — Direct share-to-upload without UI.
 *
 * Handles share intents by copying files to local storage,
 * queuing them via UploadQueueRepository, and triggering
 * background upload via WorkManager. No metadata is assigned —
 * AI tagging is handled by Paperless-ngx / Paperless-GPT.
 *
 * Flow:
 * ```
 * Share Intent → copy URI to local → queueUpload → scheduleImmediateUpload → notification → finish()
 * ```
 */
@Singleton
class QuickUploadHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "QuickUpload"
        private const val CHANNEL_ID = "quick_upload_channel"
        private const val CHANNEL_NAME = "Quick Upload"
        private const val QUICK_UPLOAD_DIR = "pending_uploads"  // Same dir as FileUtils — auto-cleanup after upload
    }

    /**
     * Handle quick upload of shared files.
     * Copies URIs to local storage, queues them, triggers background upload.
     * No metadata — AI handles tagging in Paperless-ngx.
     *
     * @param uris Content URIs from share intent
     * @return Number of successfully queued files
     */
    suspend fun handleQuickUpload(uris: List<Uri>): Int {
        Log.d(TAG, "Quick upload requested: ${uris.size} file(s)")

        // Auth check — no server configured means can't upload
        val serverUrl = tokenManager.getServerUrlSync()
        val token = tokenManager.getTokenSync()
        if (serverUrl.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "Not logged in, skipping quick upload")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Bitte zuerst bei Paperless-ngx anmelden",
                    Toast.LENGTH_LONG
                ).show()
            }
            return 0
        }

        ensureNotificationChannel()

        var queued = 0
        for (uri in uris) {
            try {
                val localUri = copyToLocal(uri)
                val fileName = getFileName(uri)
                uploadQueueRepository.queueUpload(
                    uri = localUri,
                    title = fileName
                )
                queued++
                Log.d(TAG, "Queued: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue ${uri.lastPathSegment}", e)
            }
        }

        if (queued > 0) {
            uploadWorkManager.scheduleImmediateUpload()
            showNotification(queued)
        }

        return queued
    }

    /**
     * Copy content URI to app's local storage.
     * SAF URIs are transient — must persist before WorkManager processes them.
     */
    private suspend fun copyToLocal(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val fileName = getFileName(uri)
        val uploadDir = File(context.filesDir, QUICK_UPLOAD_DIR).apply { mkdirs() }
        val localFile = File(uploadDir, "${UUID.randomUUID()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open URI: $uri")

        Uri.fromFile(localFile)
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment ?: "document_${System.currentTimeMillis()}"
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Quick upload notifications"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(fileCount: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Paperless Scanner")
            .setContentText("$fileCount Dokument(e) in Upload-Warteschlange")
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
