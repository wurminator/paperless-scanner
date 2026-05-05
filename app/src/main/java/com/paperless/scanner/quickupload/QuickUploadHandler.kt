package com.paperless.scanner.quickupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickUploadHandler — Hardened share-to-upload without UI.
 *
 * Phase 3A hardening:
 * - File validation (size, format, empty check)
 * - Duplicate detection (content hash based)
 * - Error notifications with details
 * - Proper string resources (no hardcoded strings)
 *
 * Flow:
 * ```
 * Share Intent → validate → dedup → copy URI → queueUpload → notification → finish()
 * ```
 */
@Singleton
class QuickUploadHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val tokenManager: TokenManager,
    private val tagMatchingEngine: com.paperless.scanner.data.ai.TagMatchingEngine,
    private val tagRepository: com.paperless.scanner.data.repository.TagRepository
) {
    // Result tracking for the current batch
    data class UploadResult(
        val queued: Int,
        val skipped: Int,
        val errors: List<String>,
        val total: Int
    )

    /**
     * Handle quick upload of shared files.
     * Validates, deduplicates, copies to local, queues for upload.
     *
     * @param uris Content URIs from share intent
     * @return UploadResult with counts and error details
     */
    suspend fun handleQuickUpload(uris: List<Uri>): UploadResult {
        Log.d(TAG, "Quick upload requested: ${uris.size} file(s)")

        // Auth check
        val serverUrl = tokenManager.getServerUrlSync()
        val token = tokenManager.getTokenSync()
        if (serverUrl.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "Not logged in, skipping quick upload")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.quick_upload_not_logged_in),
                    Toast.LENGTH_LONG
                ).show()
            }
            return UploadResult(0, 0, listOf(context.getString(R.string.quick_upload_error_auth)), uris.size)
        }

        ensureNotificationChannels()

        var queued = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (uri in uris) {
            val fileName = getFileName(uri)

            // 1. Validate file
            val validationError = validateFile(uri, fileName)
            if (validationError != null) {
                Log.w(TAG, "Validation failed for $fileName: $validationError")
                errors.add(validationError)
                continue
            }

            // 2. Duplicate detection
            val isDuplicate = checkDuplicate(uri)
            if (isDuplicate) {
                Log.i(TAG, "Duplicate skipped: $fileName")
                skipped++
                continue
            }

            // 3. Copy to local + auto-tag + queue
            try {
                val localUri = copyToLocal(uri)

                // Auto-tag: match filename against server tag rules
                val autoTagIds = try {
                    val tags = tagRepository.getTags().getOrDefault(emptyList())
                    val suggestions = tagMatchingEngine.findMatchingTags(fileName, tags)
                    suggestions.mapNotNull { it.tagId }
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-tag failed for $fileName, uploading without tags", e)
                    emptyList()
                }

                if (autoTagIds.isNotEmpty()) {
                    Log.d(TAG, "Auto-tagged '$fileName' with ${autoTagIds.size} tag(s)")
                }

                uploadQueueRepository.queueUpload(
                    uri = localUri,
                    title = fileName,
                    tagIds = autoTagIds
                )
                queued++
                Log.d(TAG, "Queued: $fileName")
            } catch (e: Exception) {
                val errMsg = context.getString(R.string.quick_upload_error_copy_failed, fileName)
                Log.e(TAG, "Failed to queue $fileName", e)
                errors.add(errMsg)
            }
        }

        // 4. Trigger upload + show notification
        if (queued > 0) {
            uploadWorkManager.scheduleImmediateUpload()
        }

        showResultNotification(queued, skipped, errors, uris.size)

        return UploadResult(queued, skipped, errors, uris.size)
    }

    // ── Validation ──────────────────────────────────────────────

    /**
     * Validate a file before upload. Returns null if valid, error message if not.
     */
    private fun validateFile(uri: Uri, fileName: String): String? {
        // Check MIME type
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            if (BLOCKED_MIME_TYPES.contains(mimeType)) {
                return context.getString(R.string.quick_upload_error_unsupported_format, mimeType)
            }
            val isSupported = SUPPORTED_MIME_PREFIXES.any { prefix ->
                mimeType.startsWith(prefix)
            }
            if (!isSupported) {
                return context.getString(R.string.quick_upload_error_unsupported_format, mimeType)
            }
        }

        // Check file size via ContentResolver
        val fileSize = getFileSize(uri)

        // Empty file check
        if (fileSize >= 0 && fileSize == 0L) {
            return context.getString(R.string.quick_upload_error_file_empty, fileName)
        }

        // Too large check
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            val humanSize = formatFileSize(fileSize)
            return context.getString(R.string.quick_upload_error_file_too_large, humanSize, fileName)
        }

        return null
    }

    /**
     * Get file size from ContentResolver or file scheme.
     */
    private fun getFileSize(uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
            }
        }
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return -1)
            if (file.exists()) return file.length()
        }
        return -1 // Unknown size — allow upload
    }

    // ── Duplicate Detection ─────────────────────────────────────

    /**
     * Check if this file was already uploaded in the current session.
     * Uses SHA-256 hash of the file content for reliable dedup.
     */
    private fun checkDuplicate(uri: Uri): Boolean {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            } ?: return false

            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // Check if we've seen this hash before
            if (uploadHashes.containsKey(hash)) {
                return true
            }

            // Remember this hash
            uploadHashes[hash] = getFileName(uri)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Could not compute hash for dedup, allowing upload", e)
            return false // Don't block upload if hash fails
        }
    }

    /**
     * Clear the duplicate detection cache.
     * Call when the upload session is complete or the app resumes.
     */
    fun clearDuplicateCache() {
        uploadHashes.clear()
    }

    // ── File Operations ─────────────────────────────────────────

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

    // ── Notifications ───────────────────────────────────────────

    private fun ensureNotificationChannels() {
        val notifyManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Success / progress channel
        notifyManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.quick_upload_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.quick_upload_channel_description)
            }
        )

        // Error channel — higher importance
        notifyManager.createNotificationChannel(
            NotificationChannel(
                ERROR_CHANNEL_ID,
                context.getString(R.string.quick_upload_error_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.quick_upload_channel_description)
                enableVibration(true)
            }
        )
    }

    private fun showResultNotification(queued: Int, skipped: Int, errors: List<String>, total: Int) {
        val notifyManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Success / partial success notification
        if (queued > 0) {
            val title = context.getString(R.string.quick_upload_channel_name)
            val text = if (errors.isEmpty() && skipped == 0) {
                context.getString(R.string.quick_upload_queued, queued)
            } else {
                context.getString(R.string.quick_upload_partial_success, queued, total)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            notifyManager.notify(NOTIFICATION_ID_SUCCESS, notification)
        }

        // Error notification — separate, high priority
        if (errors.isNotEmpty()) {
            val errorDetails = errors.take(5).joinToString("\n")
            val more = if (errors.size > 5) "\n…+${errors.size - 5} weitere" else ""

            val errorNotification = NotificationCompat.Builder(context, ERROR_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.quick_upload_error_title))
                .setStyle(NotificationCompat.BigTextStyle().bigText(errorDetails + more))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            notifyManager.notify(NOTIFICATION_ID_ERROR, errorNotification)
        }

        // Dedup notification
        if (skipped > 0 && queued == 0) {
            val dedupNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(context.getString(R.string.quick_upload_channel_name))
                .setContentText(context.getString(R.string.quick_upload_duplicate_skipped, "$skipped Datei(en)"))
                .setAutoCancel(true)
                .build()
            notifyManager.notify(NOTIFICATION_ID_SUCCESS, dedupNotification)
        }
    }

    // ── Utility ─────────────────────────────────────────────────

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val TAG = "QuickUpload"
        private const val CHANNEL_ID = "quick_upload_channel"
        private const val ERROR_CHANNEL_ID = "quick_upload_error_channel"
        private const val QUICK_UPLOAD_DIR = "pending_uploads"

        // 100 MB — Paperless-ngx default limit
        private const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024

        // Supported MIME type prefixes for quick upload
        private val SUPPORTED_MIME_PREFIXES = setOf(
            "image/", "application/pdf", "text/",
            "application/octet-stream"
        )

        // MIME types that are explicitly rejected even if prefix matches
        private val BLOCKED_MIME_TYPES = setOf(
            "application/javascript", "application/x-executable",
            "application/x-msdownload", "application/x-sh"
        )

        // In-memory hash cache for duplicate detection within a single session
        // Key: SHA-256 hex, Value: filename
        private val uploadHashes = ConcurrentHashMap<String, String>()

        private const val NOTIFICATION_ID_SUCCESS = 2001
        private const val NOTIFICATION_ID_ERROR = 2002
    }
}
