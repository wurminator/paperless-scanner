package com.paperless.scanner.data.cache

/**
 * CachePolicy - TTL configuration for offline-first metadata caching.
 * 
 * Controls how long cached tags, correspondents, and document types
 * are considered fresh before requiring a server re-fetch.
 */
object CachePolicy {
    /** Default TTL for metadata (tags, correspondents, document types) — 5 minutes */
    const val METADATA_TTL_MS: Long = 5 * 60 * 1000L
    
    /** TTL for document counts — 2 minutes (changes more frequently) */
    const val DOC_COUNT_TTL_MS: Long = 2 * 60 * 1000L
    
    /** Check if a timestamp is still within TTL */
    fun isFresh(lastSyncedAt: Long, ttlMs: Long = METADATA_TTL_MS): Boolean {
        return (System.currentTimeMillis() - lastSyncedAt) < ttlMs
    }
    
    /** Check if cache is stale (inverse of isFresh) */
    fun isStale(lastSyncedAt: Long, ttlMs: Long = METADATA_TTL_MS): Boolean {
        return !isFresh(lastSyncedAt, ttlMs)
    }
}
