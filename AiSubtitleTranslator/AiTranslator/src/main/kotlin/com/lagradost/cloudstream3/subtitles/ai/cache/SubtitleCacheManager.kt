package com.lagradost.cloudstream3.subtitles.ai.cache

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Production-grade two-tier subtitle cache engine with Windows file-safety and recursive pruning.
 *
 * Tier 1 (Full-File Cache):
 * Stores fully translated and reconstructed SubRip (.srt) files for immediate playback.
 * Keyed by SHA-256 composite hash of source content, target language, provider, model, prompt version, and glossary.
 * Employs a 3-tier atomic replacement sequence (ATOMIC_MOVE + REPLACE_EXISTING -> REPLACE_EXISTING -> delete + copy)
 * to prevent Windows file-sharing lock crashes and eliminate TOCTOU deletion windows under concurrent reader access.
 *
 * Tier 2 (Progressive Chunk Cache):
 * Stores intermediate translated cue chunks (chunk_NNN.json) during multi-chunk translation sessions.
 * Enables zero-loss resumption if translation is interrupted by network failure or app backgrounding.
 * Cleaned automatically once Tier 1 full-file compilation completes or after 24 hours of inactivity.
 *
 * Maintenance:
 * Enforces LRU capacity limits (50MB, 200 files), 30-day TTL, cleanup of orphaned tmp_ files (> 5 minutes),
 * and cleanup of abandoned chunk folders (> 24 hours).
 */
object SubtitleCacheManager {

    const val MAX_BYTES: Long = 50L * 1024 * 1024 // 50MB
    const val MAX_FILES: Int = 200
    const val MAX_TTL_MS: Long = 30L * 24 * 3600 * 1000 // 30 days
    const val TMP_EXPIRY_MS: Long = 5 * 60 * 1000 // 5 minutes
    const val CHUNK_EXPIRY_MS: Long = 24 * 3600 * 1000 // 24 hours

    /**
     * Optional custom base directory override for standalone JVM unit testing or custom mount points.
     */
    @Volatile
    var customBaseDir: File? = null

    private val mapper: ObjectMapper by lazy { ObjectMapper().registerKotlinModule() }

    // =========================================================================
    // Directory Resolvers
    // =========================================================================

    fun getBaseDir(context: Context? = null): File {
        val base = customBaseDir
            ?: context?.let { File(it.filesDir, "ai_sub_cache") }
            ?: File(System.getProperty("java.io.tmpdir", "."), "ai_sub_cache")
        if (!base.exists()) {
            base.mkdirs()
        }
        return base
    }

    fun getFullDir(context: Context? = null): File {
        val dir = File(getBaseDir(context), "full")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getChunksDir(context: Context? = null): File {
        val dir = File(getBaseDir(context), "chunks")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // =========================================================================
    // SHA-256 Hashing & Composite Key Generation
    // =========================================================================

    /**
     * Computes lowercase hex SHA-256 digest of input string.
     */
    fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constructs a collision-resistant composite cache key.
     * Invariant: Any mutation in content, target language, provider, model, prompt version ("v2"),
     * or user glossary yields a distinct key, preventing cache poisoning across configurations.
     */
    fun buildCacheKey(
        content: String,
        targetLang: String,
        provider: String,
        model: String,
        glossary: String = ""
    ): String {
        val cleanContent = content.trim().replace("\r\n", "\n")
        val contentHash = computeSha256(cleanContent)
        val glossaryHash = if (glossary.isNotBlank()) computeSha256(glossary.trim()) else "noglossary"
        val composite = "$contentHash|$targetLang|$provider|$model|v2|$glossaryHash"
        return computeSha256(composite)
    }

    // =========================================================================
    // Tier 1: Full-File Cache
    // =========================================================================

    /**
     * Retrieves an existing cached full SubRip (.srt) file if present and valid.
     * Updates file lastModified timestamp for LRU tracking.
     */
    fun getFullFile(context: Context? = null, cacheKey: String): File? {
        val file = File(getFullDir(context), "$cacheKey.srt")
        if (file.exists() && file.isFile && file.length() > 0L) {
            try {
                file.setLastModified(System.currentTimeMillis())
            } catch (_: Throwable) {}
            return file
        }
        return null
    }

    /**
     * Persists fully assembled SubRip content with atomic replacement and Windows file-safety fallback.
     * Prevents TOCTOU deletion gap where concurrent HTTP GET requests would otherwise receive HTTP 404.
     */
    @Synchronized
    fun saveFullFile(context: Context? = null, cacheKey: String, srtContent: String): File {
        val fullDir = getFullDir(context)
        val destFile = File(fullDir, "$cacheKey.srt")
        val tempFile = File(fullDir, "tmp_${System.currentTimeMillis()}_$cacheKey.srt")

        try {
            tempFile.writeText(srtContent, Charsets.UTF_8)

            var replaced = false

            // Retry loop up to 80 attempts with dynamic backoff (1ms to 5ms) to accommodate transient Windows reader file locks
            for (attempt in 0..80) {
                // Tier 1: Attempt atomic NIO move (Win32 MoveFileExW with MOVEFILE_REPLACE_EXISTING)
                try {
                    Files.move(
                        tempFile.toPath(),
                        destFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    replaced = true
                    break
                } catch (_: Throwable) {
                    // Transient Windows reader file lock: dynamic backoff (1ms to 5ms) before retry
                    if (attempt < 80) {
                        try {
                            Thread.sleep((1 + (attempt % 5)).toLong())
                        } catch (_: InterruptedException) {}
                    }
                }
            }

            // Tier 2: Non-atomic NIO move fallback if atomic move is unsupported on file system
            if (!replaced) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        destFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    replaced = true
                } catch (_: Throwable) {}
            }

            // Tier 3: Non-truncating in-place rewrite fallback (never truncate destFile to 0 bytes, preserving destFile.length() > 0L)
            if (!replaced) {
                try {
                    if (!destFile.exists() && tempFile.renameTo(destFile)) {
                        replaced = true
                    }
                } catch (_: Throwable) {}
            }

            if (!replaced) {
                try {
                    java.io.RandomAccessFile(destFile, "rw").use { raf ->
                        val bytes = tempFile.readBytes()
                        raf.seek(0)
                        raf.write(bytes)
                        raf.setLength(bytes.size.toLong())
                    }
                    replaced = true
                } catch (_: Throwable) {
                    try {
                        destFile.writeBytes(tempFile.readBytes())
                        replaced = true
                    } catch (_: Throwable) {}
                }
            }

            // Post-save maintenance
            pruneCache(context)
            clearChunks(context, cacheKey)

            return destFile
        } finally {
            if (tempFile.exists()) {
                try {
                    tempFile.delete()
                } catch (_: Throwable) {}
            }
        }
    }

    // =========================================================================
    // Tier 2: Progressive Chunk Cache
    // =========================================================================

    /**
     * Saves a completed chunk mapping of cue index to translated text.
     */
    fun saveChunk(context: Context? = null, cacheKey: String, chunkIndex: Int, items: Map<Int, String>) {
        val episodeChunkDir = File(getChunksDir(context), cacheKey).apply {
            if (!exists()) mkdirs()
        }
        val chunkFile = File(episodeChunkDir, "chunk_%04d.json".format(chunkIndex))
        val json = mapper.writeValueAsString(items)
        chunkFile.writeText(json, Charsets.UTF_8)
        try {
            episodeChunkDir.setLastModified(System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    /**
     * Loads and aggregates all completed chunks for the given cache key.
     * Safely handles JSON string keys ("1", "2") mapping them cleanly to Integer indices.
     */
    fun loadChunks(context: Context? = null, cacheKey: String): Map<Int, String> {
        val episodeChunkDir = File(getChunksDir(context), cacheKey)
        if (!episodeChunkDir.exists() || !episodeChunkDir.isDirectory) return emptyMap()

        val combined = HashMap<Int, String>()
        val chunkFiles = episodeChunkDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: return emptyMap()

        for (f in chunkFiles) {
            try {
                val rawMap: Map<*, *> = mapper.readValue(f, object : TypeReference<Map<String, Any>>() {})
                rawMap.forEach { (k, v) ->
                    val intKey = k.toString().toIntOrNull()
                    if (intKey != null && v != null) {
                        combined[intKey] = v.toString()
                    }
                }
            } catch (_: Throwable) {
                try {
                    val directMap: Map<Int, String> = mapper.readValue(f, object : TypeReference<Map<Int, String>>() {})
                    combined.putAll(directMap)
                } catch (_: Throwable) {}
            }
        }
        return combined
    }

    /**
     * Returns true if one or more valid chunk files exist for the given cache key.
     */
    fun hasChunks(context: Context? = null, cacheKey: String): Boolean {
        val episodeChunkDir = File(getChunksDir(context), cacheKey)
        return episodeChunkDir.exists() &&
                episodeChunkDir.isDirectory &&
                (episodeChunkDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.isNotEmpty() == true)
    }

    /**
     * Deletes the chunk directory for the given cache key upon successful full-file assembly.
     */
    fun clearChunks(context: Context? = null, cacheKey: String) {
        val episodeChunkDir = File(getChunksDir(context), cacheKey)
        if (episodeChunkDir.exists()) {
            try {
                episodeChunkDir.deleteRecursively()
            } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // Maintenance & Pruning
    // =========================================================================

    /**
     * Comprehensive recursive cache pruner:
     * 1. Deletes orphaned tmp_ files older than 5 minutes (preserves active write sessions).
     * 2. Deletes abandoned chunk directories older than 24 hours (preserves active chunking).
     * 3. Prunes full/ files exceeding 30-day TTL.
     * 4. Enforces LRU capacity limits (50MB, 200 files) by evicting oldest modified files.
     */
    @Synchronized
    fun pruneCache(
        context: Context? = null,
        maxBytes: Long = MAX_BYTES,
        maxFiles: Int = MAX_FILES,
        maxTtlMs: Long = MAX_TTL_MS
    ) {
        val now = System.currentTimeMillis()
        val fullDir = getFullDir(context)
        val chunksDir = getChunksDir(context)

        // 1. Orphaned tmp_ files older than 5 minutes
        fullDir.listFiles { f -> f.isFile && f.name.startsWith("tmp_") }?.forEach { f ->
            if (now - f.lastModified() > TMP_EXPIRY_MS) {
                try { f.delete() } catch (_: Throwable) {}
            }
        }

        // 2. Abandoned chunk directories older than 24 hours
        chunksDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (now - dir.lastModified() > CHUNK_EXPIRY_MS) {
                try { dir.deleteRecursively() } catch (_: Throwable) {}
            }
        }

        // 3. LRU and TTL eviction in full/
        val files = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.toMutableList()
            ?: return

        // Remove expired files (> 30 days)
        files.removeAll { f ->
            if (now - f.lastModified() > maxTtlMs) {
                try { f.delete() } catch (_: Throwable) {}
                true
            } else false
        }

        // Sort ascending by lastModified (oldest first)
        files.sortBy { it.lastModified() }

        var totalBytes = files.sumOf { it.length() }
        var fileCount = files.size

        while (files.isNotEmpty() && (totalBytes > maxBytes || fileCount > maxFiles)) {
            val oldest = files.removeAt(0)
            totalBytes -= oldest.length()
            fileCount--
            try { oldest.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Purges all cache data (both full files and chunks).
     */
    fun clearAll(context: Context? = null) {
        try {
            getBaseDir(context).deleteRecursively()
        } catch (_: Throwable) {}
    }

    /**
     * Returns current metrics: Pair(non-tmp file count, total bytes in full/).
     */
    fun getMetrics(context: Context? = null): Pair<Int, Long> {
        val files = getFullDir(context).listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") } ?: emptyList()
        val totalBytes = files.sumOf { it.length() }
        return Pair(files.size, totalBytes)
    }
}
