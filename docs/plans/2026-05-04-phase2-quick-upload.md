# Phase 2: Quick Upload — Direct Share-to-Upload

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Share a file to Paperless Scanner → upload starts immediately, no screens, no metadata. The AI (Paperless-GPT / Paperless-ngx) handles tagging/title in the background.

**Architecture:** Intercept share intents in `MainActivity`, copy URI to local storage, queue via `UploadQueueRepository.queueUpload()`, trigger `UploadWorkManager.scheduleImmediateUpload()`, show notification, finish activity. No new screens, no new DataStore.

**Tech Stack:** Existing: Hilt DI, Room, WorkManager, Android ShareSheet API. No new dependencies.

---

## Current Flow (Problem)

```
Share Intent → Home screen → ScanScreen (preview) → Continue → UploadScreen (manual metadata) → Upload button
```
4+ user interactions. Every metadata field empty. App must be foreground.

## Target Flow

```
Share Intent → queue upload (background) → notification "Hochgeladen" → user stays in previous app
```
Zero user interactions for single file. Multi-file: one confirmation.

---

## Bug Fix (Pre-requisite)

### Task 0: Fix `onNewIntent` missing share handling

**Objective:** When app is already running and user shares a file, the share intent is silently dropped because `onNewIntent()` only handles deep links, not `ACTION_SEND`.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/MainActivity.kt`

**Step 1: Read current `onNewIntent` and `handleShareIntent`**

```bash
grep -n "onNewIntent\|handleShareIntent\|sharedUris\|ACTION_SEND" app/src/main/java/com/paperless/scanner/MainActivity.kt
```

**Step 2: Update `onNewIntent` to also handle share intents**

Current code (approximate):
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent?.let { deepLinkHandler.parseIntent(it) }
}
```

Change to:
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent?.let {
        deepLinkHandler.parseIntent(it)
        // Handle share intents when app is already running
        val sharedUris = handleShareIntent(it)
        if (sharedUris.isNotEmpty()) {
            quickUploadHandler.handleQuickUpload(sharedUris)
        }
    }
}
```

Note: `quickUploadHandler` is introduced in Task 1. For now, just flag that `handleShareIntent()` must also be called here. This task can be merged with Task 3 when wiring.

**Step 3: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL (compilation only — `quickUploadHandler` will be added in Task 1)

---

## Core Implementation

### Task 1: Create `QuickUploadHandler`

**Objective:** New class that takes a list of URIs, copies them to local storage, queues them via `UploadQueueRepository`, triggers WorkManager, and shows a notification.

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/quickupload/QuickUploadHandler.kt`

**Step 1: Create the class**

```kotlin
package com.paperless.scanner.quickupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickUploadHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager
) {
    companion object {
        private const val TAG = "QuickUpload"
        private const val CHANNEL_ID = "quick_upload_channel"
        private const val CHANNEL_NAME = "Quick Upload"
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
        Log.d(TAG, "Quick upload: ${uris.size} file(s)")
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
        val uploadDir = File(context.cacheDir, "quick_uploads").apply { mkdirs() }
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
```

**Step 2: Register in Hilt (only if not auto-detected)**

Since the class uses `@Singleton` + `@Inject constructor`, Hilt should discover it automatically. Verify no AppModule registration needed:

```bash
grep -n "UploadQueueRepository\|UploadWorkManager" app/src/main/java/com/paperless/scanner/di/AppModule.kt
```

If both are `@Inject`-provided, no changes needed. `QuickUploadHandler` follows the same pattern.

**Step 3: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/quickupload/QuickUploadHandler.kt
git commit -m "feat: add QuickUploadHandler for direct share-to-upload"
```

---

### Task 2: Wire `QuickUploadHandler` into `MainActivity`

**Objective:** Inject `QuickUploadHandler` into `MainActivity`, use it in both `onCreate` and `onNewIntent` for share intents.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/MainActivity.kt`

**Step 1: Add field injection**

In `MainActivity`, add:
```kotlin
@Inject lateinit var quickUploadHandler: QuickUploadHandler
```

**Step 2: Update `onCreate` — after `handleShareIntent()`**

Current flow:
```kotlin
val sharedUris = handleShareIntent(intent)
setContent { PaperlessNavGraph(sharedUris = sharedUris) }
```

New flow — check if quick upload should trigger:
```kotlin
val sharedUris = handleShareIntent(intent)
val isQuickUpload = sharedUris.isNotEmpty()

if (isQuickUpload) {
    // Quick upload: no UI needed — queue in background and close app
    lifecycleScope.launch {
        val queued = quickUploadHandler.handleQuickUpload(sharedUris)
        Log.d("MainActivity", "Quick upload queued: $queued file(s)")
    }
    // Set minimal content (required by Compose), then finish
    setContent { } 
    finish()
    return // Don't set up NavGraph at all
}

setContent { PaperlessNavGraph(sharedUris = emptyList()) }
```

Add import:
```kotlin
import androidx.lifecycle.lifecycleScope
import com.paperless.scanner.quickupload.QuickUploadHandler
import kotlinx.coroutines.launch
```

**Step 3: Update `onNewIntent`**

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent?.let {
        deepLinkHandler.parseIntent(it)
        val sharedUris = handleShareIntent(it)
        if (sharedUris.isNotEmpty()) {
            lifecycleScope.launch {
                quickUploadHandler.handleQuickUpload(sharedUris)
            }
            finish() // Close app, return to previous app
        }
    }
}
```

**Step 4: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/MainActivity.kt
git commit -m "feat: wire QuickUploadHandler into MainActivity for direct upload"
```

---

### Task 3: Add `text/*` and `application/*` MIME type support

**Objective:** Current intent-filter only handles `image/*` and `application/pdf`. Add support for text files and other document types that Paperless-ngx can ingest (e.g., `.docx`, `.xlsx`, `.odt`).

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Read current intent-filters**

```bash
grep -A 10 "ACTION_SEND" app/src/main/AndroidManifest.xml
```

**Step 2: Add broader MIME types**

Add additional intent-filters after existing ones:
```xml
<!-- Quick Upload: any document type -->
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/*" />
</intent-filter>
```

Note: `application/pdf` is already covered. Other `application/*` types (docx, xlsx) can be added later based on need. Keeping scope small for now.

**Step 3: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add text/* MIME support for quick upload share intent"
```

---

## Edge Cases & Cleanup

### Task 4: Handle "app not logged in" edge case

**Objective:** If user shares a file but hasn't logged in yet, show a Toast and don't queue.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/quickupload/QuickUploadHandler.kt`

**Step 1: Add auth check**

Inject `TokenManager` (or check if server URL + token exist):

```kotlin
@Singleton
class QuickUploadHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val tokenManager: TokenManager
) {
    // In handleQuickUpload(), add at top:
    suspend fun handleQuickUpload(uris: List<Uri>): Int {
        // Auth check — no server configured = can't upload
        if (tokenManager.getServerUrl().isBlank()) {
            Log.w(TAG, "No server configured, skipping quick upload")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Bitte zuerst bei Paperless-ngx anmelden", Toast.LENGTH_LONG).show()
            }
            return 0
        }
        // ... rest of method
    }
}
```

Add imports:
```kotlin
import android.widget.Toast
import com.paperless.scanner.data.datastore.TokenManager

```

**Step 2: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/quickupload/QuickUploadHandler.kt
git commit -m "feat: add auth check to QuickUploadHandler — reject if not logged in"
```

---

### Task 5: Clean up `quick_uploads` cache directory

**Objective:** Uploaded files in `cache/quick_uploads/` should be cleaned up after successful upload to avoid filling storage.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/worker/UploadWorker.kt`

**Step 1: Read UploadWorker to find where completed uploads are handled**

```bash
grep -n "markAsCompleted\|onSuccess\|COMPLETED\|delete\|clean" app/src/main/java/com/paperless/scanner/worker/UploadWorker.kt
```

**Step 2: Add cleanup after successful upload**

After the upload succeeds and `markAsCompleted()` is called, delete the local file if it's in `quick_uploads/`:

```kotlin
// After successful upload, clean up local copy if it was a quick upload
val localFile = File(upload.uri)
if (localFile.exists() && localFile.parentFile?.name == "quick_uploads") {
    localFile.delete()
    Log.d(TAG, "Cleaned up quick upload cache: ${localFile.name}")
}
```

**Step 3: Build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/worker/UploadWorker.kt
git commit -m "feat: auto-cleanup quick_uploads cache after successful upload"
```

---

## Final Verification

### Task 6: Full build + smoke test

**Step 1: Clean build**

```bash
cd ~/projects/paperless-scanner && source ~/.bashrc && ./gradlew clean assembleDebug --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

**Step 2: Verify APK**

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Expected: APK exists, ~15-25 MB

**Step 3: Review all changes**

```bash
git log --oneline -6
```

Expected: 5-6 commits with `feat: quick upload` messages

**Step 4: Push to remote (optional)**

```bash
source ~/.env 2>/dev/null; git push https://wurminator:${GITHUB_TOKEN}@github.com/wurminator/paperless-scanner.git HEAD:main
```

---

## Summary

| Task | Description | Est. Time |
|------|-------------|-----------|
| 0 | Fix onNewIntent share handling | 15 min |
| 1 | Create QuickUploadHandler | 30 min |
| 2 | Wire into MainActivity | 20 min |
| 3 | Add text/* MIME support | 10 min |
| 4 | Auth check edge case | 15 min |
| 5 | Cache cleanup in UploadWorker | 15 min |
| 6 | Full build + verify | 10 min |
| **Total** | | **~2h** |

**New files:** 1 (`QuickUploadHandler.kt`)
**Modified files:** 3 (`MainActivity.kt`, `AndroidManifest.xml`, `UploadWorker.kt`)
**New dependencies:** None
