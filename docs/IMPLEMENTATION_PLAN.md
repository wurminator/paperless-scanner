# Implementierungsplan: Paperless Scanner Fork

**Erstellt:** 2026-05-04  
**Ziel:** Schneller Scan/Upload-Workflow + Smart Caching für Metadata  
**Basis:** Fork von [napoleonmm83/paperless-scanner](https://github.com/napoleonmm83/paperless-scanner)

---

## Problemstellung

### P1: Langsamer App-Start durch Metadata-Refresh
Bei jedem Screen-Wechsel werden Tags, Correspondents, DocumentTypes und CustomFields mit `forceRefresh = true` komplett neu vom Server geladen. Bei vielen Tags (100+) dauert das spürbar.

**Betroffene Stellen:**
- `LabelsViewModel` (Zeile 384-387) — 4x forceRefresh parallel
- `UploadViewModel` (Zeile 674) — forceRefresh beim Upload-Screen
- `DocumentsViewModel` (Zeile 249) — forceRefresh beim Laden
- `HomeViewModel` (Zeilen 423-562) — massiv forceRefresh überall
- `ScanViewModel` (Zeile 252) — `loadTags()` lädt Tags neu

### P2: Suboptimaler "Senden an"-Flow
- Share-Intent wird in `MainActivity` korrekt empfangen (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`)
- URIs werden an `PaperlessNavGraph` übergeben und zum Upload-Screen navigiert
- **Aber:** Keine Vorausfüllung, kein Memory der letzten Auswahl, Umweg über Navigation

---

## Phase 1: Smart Metadata Cache (TTL-basiert)

### 1.1 Cache-TTL Entity erweitern

**Dateien:**
- `data/database/entity/CachedTag.kt` — `lastSyncedAt: Long` hinzufügen
- `data/database/entity/CachedCorrespondent.kt` — `lastSyncedAt: Long` hinzufügen
- `data/database/entity/CachedDocumentType.kt` — `lastSyncedAt: Long` hinzufügen

**Neue Room-Migration:** v12 → v13

```kotlin
// In jeder Entity:
@ColumnInfo(name = "last_synced_at")
val lastSyncedAt: Long = System.currentTimeMillis()
```

### 1.2 Cache-TTL Konstanten

**Neue Datei:** `data/repository/CachePolicy.kt`

```kotlin
object CachePolicy {
    const val TAGS_TTL_MS = 5 * 60 * 1000L         // 5 Minuten
    const val CORRESPONDENTS_TTL_MS = 5 * 60 * 1000L
    const val DOCUMENT_TYPES_TTL_MS = 5 * 60 * 1000L
    const val CUSTOM_FIELDS_TTL_MS = 10 * 60 * 1000L // 10 Minuten
    
    fun isStale(lastSyncedAt: Long, ttlMs: Long): Boolean {
        return System.currentTimeMillis() - lastSyncedAt > ttlMs
    }
}
```

### 1.3 Repository-Logik anpassen

**Datei:** `data/repository/TagRepository.kt`

```kotlin
suspend fun getTags(forceRefresh: Boolean = false): Result<List<Tag>> {
    // Cache prüfen
    val cachedTags = cachedTagDao.getAllTags()
    
    if (cachedTags.isNotEmpty() && !forceRefresh) {
        // TTL prüfen: Nur refreshen wenn Cache abgelaufen
        val newestSync = cachedTags.maxOf { it.lastSyncedAt }
        if (!CachePolicy.isStale(newestSync, CachePolicy.TAGS_TTL_MS)) {
            return Result.success(cachedTags.map { it.toCachedDomain() })
        }
    }
    
    // Netzwerk-Fetch (wenn forceRefresh ODER Cache leer/abgelaufen)
    if (networkMonitor.checkOnlineStatus()) {
        // ... bestehende Fetch-Logik ...
        // lastSyncedAt = now() beim Schreiben in Cache
    } else if (cachedTags.isNotEmpty()) {
        // Offline: abgelaufenen Cache trotzdem nutzen
        return Result.success(cachedTags.map { it.toCachedDomain() })
    }
}
```

**Gleiches Pattern für:**
- `CorrespondentRepository.kt`
- `DocumentTypeRepository.kt`
- `CustomFieldRepository.kt`

### 1.4 forceRefresh-Verwendung reduzieren

**Änderungen in ViewModels:**

| Datei | Vorher | Nachher |
|---|---|---|
| `LabelsViewModel.kt:384` | `forceRefresh = true` | `forceRefresh = false` (TTL reicht) |
| `UploadViewModel.kt:674` | `forceRefresh = true` | `forceRefresh = false` |
| `DocumentsViewModel.kt:249` | `forceRefresh = true` | `forceRefresh = false` |
| `ScanViewModel.kt:252` | `loadTags()`每次neu | `loadTags(forceRefresh = false)` |
| `HomeViewModel.kt` | 10x forceRefresh | Nur bei Pull-to-Refresh forceRefresh |

**forceRefresh = true bleibt NUR bei:**
- Pull-to-Refresh Gesture
- Nach CRUD-Operationen (Tag erstellt/gelöscht)
- Manueller Refresh-Button

### 1.5 Hintergrund-Sync beim App-Start

**Datei:** `data/sync/SyncManager.kt` erweitern

```kotlin
// Neuer Aufruf in PaperlessApp.onCreate() oder HomeViewModel.init()
// Non-blocking: Lädt Metadata im Hintergrund, Cache wird aktualisiert
// UI zeigt sofort Cache-Daten, reaktiviert Flow aktualisiert automatisch
fun syncMetadataBackground() {
    // Tags, Correspondents, DocTypes parallel laden
    // lastSyncedAt aktualisieren → Flow triggert UI-Update
}
```

### 1.6 DAOs erweitern

**Dateien:**
- `data/database/dao/CachedTagDao.kt` — Query für `MAX(lastSyncedAt)`
- `data/database/dao/CachedCorrespondentDao.kt` — gleiches Pattern
- `data/database/dao/CachedDocumentTypeDao.kt` — gleiches Pattern

---

## Phase 2: Schneller Upload-Workflow

### 2.1 "Senden an" optimieren

**Datei:** `ui/navigation/PaperlessNavGraph.kt`

```kotlin
// Share-Intent → Direkt zum Upload-Screen (nicht über Home)
composable(
    route = Screen.Upload.route,
    arguments = listOf(
        navArgument("sharedUris") { type = NavType.StringType; nullable = true },
        navArgument("quickMode") { type = NavType.BoolType; defaultValue = false }
    )
)
```

**Neuer deep-link Route:** `paperless://upload?sharedUris=...&quickMode=true`

### 2.2 Quick-Upload: Letzte Auswahl merken

**Neue DataStore Keys** in `data/datastore/TokenManager.kt` (oder besser: neues `UploadPreferences.kt`):

```kotlin
// Letzte Metadata-Auswahl speichern
object UploadPreferences {
    val LAST_TAG_IDS = longPreferencesKey("last_tag_ids")
    val LAST_CORRESPONDENT_ID = longPreferencesKey("last_correspondent_id")
    val LAST_DOCUMENT_TYPE_ID = longPreferencesKey("last_document_type_id")
    val LAST_TITLE_TEMPLATE = stringPreferencesKey("last_title_template")
}
```

**Datei:** `ui/screens/upload/UploadViewModel.kt`

```kotlin
// Beim Öffnen des Upload-Screens:
// 1. Gespeicherte Tags/Correspondent laden
// 2. Sofort als Vorauswahl anzeigen
// 3. Tags aus Cache (nicht forceRefresh!)

fun loadSavedMetadata() {
    val savedTagIds = uploadPreferences.getLastTagIds()
    val savedCorrespondentId = uploadPreferences.getLastCorrespondentId()
    // Vorausfüllen im UI-State
}
```

### 2.3 Quick-Upload Screen (vereinfacht)

**Neuer Composable:** `ui/screens/upload/QuickUploadScreen.kt`

Minimaler Upload-Flow für "Senden an":
```
┌─────────────────────────┐
│ 📄 Dokument hochladen   │
│─────────────────────────│
│ Title: [Rechnung ___]   │
│ Tags:   [Rechnung] [×]  │
│         [+ Tag hinzuf.]  │
│ Von:    [Letzte Ausw.]  │
│ Typ:    [Rechnung]      │
│─────────────────────────│
│    [📤 Sofort hochladen] │
│    [📋 Details →]        │
└─────────────────────────┘
```

- **3-Click-Upload**: Senden an → Titel bestätigen → Hochladen
- Tags/Correspondent vorab aus `UploadPreferences`
- "Details →" öffnet den vollständigen Upload-Screen
- Direkter Upload ohne Umweg über Home/Dashboard

### 2.4 Scan-to-Upload verbessern

**Datei:** `ui/screens/scan/ScanViewModel.kt`

```kotlin
// Scan → Sofort Metadata zuweisen → Upload
// Nicht: Scan → Vorschau → Separater Upload-Screen
// Merge ScanScreen + UploadScreen zu einem Flow

fun onScanComplete(pages: List<Uri>) {
    // Gespeicherte Metadata laden
    // PDF generieren
    // Direkt hochladen (optional mit Bestätigung)
}
```

---

## Phase 3: Weitere Optimierungen (Optional)

### 3.1 Startup-Optimierung
- `runBlocking` in `MainActivity` eliminieren (Zeile 126)
- Startup-Ziel asynchron bestimmen
- Splash-Screen mit Lade-Indicator

### 3.2 Metadata-Prefetch
- `Application.onCreate()`: Metadata im Hintergrund laden
- Wenn User den Upload-Screen öffnet → Daten schon da
- Reaktiver Flow: UI updatet automatisch wenn Daten ankommen

### 3.3 Inkrementelles Laden
- Tags/Correspondents: Nur geänderte seit `lastSyncedAt` laden
- Paperless-ngx API unterstützt Pagination — fürs Erste reicht Full-Sync mit TTL

---

## Implementierungsreihenfolge

```
Phase 1 (Cache-TTL) ──────────────────────────── ~4-6 Stunden
  ├── 1.1 Entity erweitern + Migration v13
  ├── 1.2 CachePolicy.kt erstellen
  ├── 1.3 TagRepository anpassen
  ├── 1.4 CorrespondentRepository anpassen
  ├── 1.5 DocumentTypeRepository anpassen
  ├── 1.6 forceRefresh in ViewModels reduzieren
  ├── 1.7 Hintergrund-Sync in SyncManager
  └── 1.8 Test: App starten, Screen wechseln → kein Spinner

Phase 2 (Schneller Upload) ───────────────────── ~6-8 Stunden
  ├── 2.1 UploadPreferences (DataStore)
  ├── 2.2 UploadViewModel: Gespeicherte Metadata laden
  ├── 2.3 QuickUploadScreen Composable
  ├── 2.4 "Senden an" → QuickUpload Route
  ├── 2.5 Scan-Flow optimieren
  └── 2.6 Test: Teilen → 3 Klicks → Upload fertig

Phase 3 (Polish) ─────────────────────────────── optional
  ├── 3.1 runBlocking eliminieren
  ├── 3.2 Startup-Prefetch
  └── 3.3 Inkrementelles Laden
```

---

## Betroffene Dateien (Übersicht)

| Datei | Änderung |
|---|---|
| `data/database/entity/CachedTag.kt` | `lastSyncedAt` Feld |
| `data/database/entity/CachedCorrespondent.kt` | `lastSyncedAt` Feld |
| `data/database/entity/CachedDocumentType.kt` | `lastSyncedAt` Feld |
| `data/database/AppDatabase.kt` | Migration v12→v13 |
| `data/repository/CachePolicy.kt` | **NEU** — TTL-Konfiguration |
| `data/repository/TagRepository.kt` | TTL-Logik in `getTags()` |
| `data/repository/CorrespondentRepository.kt` | TTL-Logik in `getCorrespondents()` |
| `data/repository/DocumentTypeRepository.kt` | TTL-Logik |
| `data/repository/CustomFieldRepository.kt` | TTL-Logik |
| `data/datastore/UploadPreferences.kt` | **NEU** — Letzte Upload-Auswahl |
| `data/database/dao/CachedTagDao.kt` | `lastSyncedAt` Queries |
| `data/database/dao/CachedCorrespondentDao.kt` | `lastSyncedAt` Queries |
| `data/database/dao/CachedDocumentTypeDao.kt` | `lastSyncedAt` Queries |
| `data/sync/SyncManager.kt` | `syncMetadataBackground()` |
| `ui/screens/upload/UploadViewModel.kt` | Saved metadata + cache-first |
| `ui/screens/upload/QuickUploadScreen.kt` | **NEU** — Minimaler Upload-Screen |
| `ui/screens/scan/ScanViewModel.kt` | Direct-upload nach Scan |
| `ui/screens/labels/LabelsViewModel.kt` | `forceRefresh = false` |
| `ui/screens/documents/DocumentsViewModel.kt` | `forceRefresh = false` |
| `ui/screens/home/HomeViewModel.kt` | forceRefresh reduzieren |
| `ui/navigation/PaperlessNavGraph.kt` | QuickUpload Route |
| `MainActivity.kt` | Share → QuickUpload Navigation |

---

## Erfolgsmetriken

| Metrik | Vorher | Ziel |
|---|---|---|
| Zeit bis Tags geladen (2. Start) | 2-5s Network | <100ms Cache |
| "Senden an" → Upload fertig | 5+ Klicks | 3 Klicks |
| Screen-Wechsel Latenz | Spinner sichtbar | Instant aus Cache |
| Offline-Fähigkeit Labels | ❌ | ✅ (mit TTL-Verlängerung) |
