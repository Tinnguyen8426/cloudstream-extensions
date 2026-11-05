package com.kitsunekko.enricher

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.app
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Streaming Zip decompression engine with ZipSlip canonical path protection,
 * multi-charset fallback (UTF-8 -> Shift-JIS -> Windows-1252), BOM stripping,
 * and LRU temp cache file management for Kitsunekko Japanese subtitles.
 */
object ZipSubtitleExtractor {

    private const val TAG = "ZipSubtitleExtractor"
    const val CACHE_DIR_ZIPS = "kitsunekko_cache"
    const val CACHE_DIR_SUBS = "kitsunekko_subs"

    const val MAX_ZIP_FILES = 4
    const val MAX_SUB_FILES = 20
    const val MAX_CACHE_BYTES = 100L * 1024 * 1024 // 100 MB

    fun getZipCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_ZIPS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSubsCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_SUBS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Validates that destinationFile stays within destinationDir (ZipSlip defense).
     */
    fun validateZipSlip(destinationFile: File, destinationDir: File) {
        val canonicalDest = destinationFile.canonicalPath
        val canonicalDir = destinationDir.canonicalPath
        if (!canonicalDest.startsWith(canonicalDir + File.separator)) {
            throw SecurityException("ZipSlip security violation: entry attempts directory traversal! Path: ${destinationFile.path}")
        }
    }

    /**
     * Normalizes line endings to LF and strips UTF-8 BOM if present.
     */
    fun normalizeSubText(text: String): String {
        return text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimStart('\uFEFF')
    }

    private val CHARSET_SHIFT_JIS by lazy {
        try { Charset.forName("Shift_JIS") } catch (_: Throwable) { Charsets.UTF_8 }
    }
    private val CHARSET_CP1252 by lazy {
        try { Charset.forName("windows-1252") } catch (_: Throwable) { Charsets.ISO_8859_1 }
    }

    /**
     * Multi-charset auto-detection and fallback decoding:
     * UTF-8 -> Shift-JIS -> Windows-1252 -> Lossy UTF-8
     */
    fun decodeText(bytes: ByteArray): String {
        // 1. Try UTF-8
        try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (!utf8.contains('\uFFFD')) {
                return normalizeSubText(utf8)
            }
        } catch (_: Throwable) {}

        // 2. Try Shift-JIS (standard for Japanese legacy subtitles)
        try {
            val decoded = String(bytes, CHARSET_SHIFT_JIS)
            if (!decoded.contains('\uFFFD')) {
                return normalizeSubText(decoded)
            }
        } catch (_: Throwable) {}

        // 3. Fallback to Windows-1252
        return try {
            normalizeSubText(String(bytes, CHARSET_CP1252))
        } catch (_: Throwable) {
            // Last resort: lossy UTF-8, never throw unhandled exception
            normalizeSubText(String(bytes, Charsets.UTF_8))
        }
    }

    /**
     * Streams through a Zip archive and extracts ONLY the matching episode entry.
     * Does NOT decompress the entire archive to disk.
     */
    fun extractSubtitleFromZipStream(
        zipInputStream: ZipInputStream,
        targetEpisode: Int,
        destinationDir: File,
        slug: String
    ): File? {
        val allowedExts = setOf("ass", "srt", "ssa")
        val sanitizedSlug = slug.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(40)

        try {
            while (true) {
                val entry = zipInputStream.nextEntry ?: break
                if (entry.isDirectory) {
                    zipInputStream.closeEntry()
                    continue
                }

                if (entry.size > 15 * 1024 * 1024L) {
                    Log.w(TAG, "Skipping oversize entry: ${entry.name} (${entry.size} bytes)")
                    zipInputStream.closeEntry()
                    continue
                }

                val entryName = entry.name
                val fileName = entryName.substringAfterLast('/')
                val ext = fileName.substringAfterLast('.', "").lowercase()

                if (ext in allowedExts) {
                    val epNum = KitsunekkoMatcher.extractEpisodeNumber(fileName)
                    if (epNum == targetEpisode) {
                        val outFile = File(destinationDir, "ep_${targetEpisode}_${sanitizedSlug}.$ext")
                        validateZipSlip(outFile, destinationDir)

                        val rawBytes = zipInputStream.readBytes()
                        val decodedText = decodeText(rawBytes)

                        val tempFile = File(destinationDir, "tmp_${System.currentTimeMillis()}_ep$targetEpisode.$ext")
                        validateZipSlip(tempFile, destinationDir)
                        try {
                            tempFile.writeText(decodedText, Charsets.UTF_8)

                            if (outFile.exists()) outFile.delete()
                            if (!tempFile.renameTo(outFile)) {
                                tempFile.copyTo(outFile, overwrite = true)
                                tempFile.delete()
                            }

                            zipInputStream.closeEntry()
                            pruneCache(destinationDir, MAX_CACHE_BYTES, MAX_SUB_FILES)
                            Log.i(TAG, "Extracted ep $targetEpisode from zip: ${outFile.name} (${outFile.length()} bytes)")
                            return outFile
                        } finally {
                            if (tempFile.exists()) tempFile.delete()
                        }
                    }
                }
                zipInputStream.closeEntry()
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error while streaming zip: ${e.message}", e)
            return null
        }
        return null
    }

    /**
     * Extracts matching episode from a locally saved zip archive file.
     */
    fun extractFromZipFile(
        zipFile: File,
        targetEpisode: Int,
        destinationDir: File,
        slug: String
    ): File? {
        if (!zipFile.exists() || zipFile.length() == 0L) {
            if (zipFile.exists()) zipFile.delete()
            return null
        }
        return try {
            try {
                java.util.zip.ZipFile(zipFile).use { }
            } catch (ze: Throwable) {
                Log.e(TAG, "Corrupted zip archive detected ${zipFile.name}: ${ze.message}, deleting", ze)
                if (zipFile.exists()) zipFile.delete()
                return null
            }

            FileInputStream(zipFile).buffered().use { fis ->
                ZipInputStream(fis).use { zis ->
                    extractSubtitleFromZipStream(zis, targetEpisode, destinationDir, slug)
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "extractFromZipFile failed on ${zipFile.name}: ${e.message}, deleting cached zip", e)
            if (zipFile.exists()) zipFile.delete()
            null
        }
    }

    /**
     * High-level entry point: fetches/caches .zip if necessary, then extracts the target episode.
     */
    suspend fun extractSubtitleFromZip(
        context: Context,
        zipUrl: String,
        zipName: String,
        targetEpisode: Int?
    ): File? {
        val targetEp = targetEpisode ?: 1
        val subsDir = getSubsCacheDir(context)
        val zipDir = getZipCacheDir(context)
        val sanitizedZipName = zipName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(50)

        // 1. Check if matching extracted file already exists
        val existingExtracted = subsDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name.startsWith("ep_${targetEp}_${sanitizedZipName}")
        }
        if (existingExtracted != null && existingExtracted.length() > 0L) {
            existingExtracted.setLastModified(System.currentTimeMillis())
            return existingExtracted
        }

        // 2. Check if local cached zip exists or download it
        val cachedZipFile = File(zipDir, "$sanitizedZipName.zip")
        validateZipSlip(cachedZipFile, zipDir)

        if (!cachedZipFile.exists() || cachedZipFile.length() == 0L) {
            val tmpZip = File(zipDir, "tmp_${System.currentTimeMillis()}_$sanitizedZipName.zip")
            validateZipSlip(tmpZip, zipDir)
            try {
                Log.i(TAG, "Downloading zip archive: $zipUrl -> ${cachedZipFile.name}")
                val downloadOk = app.get(zipUrl, timeout = 8).use { response ->
                    if (response.isSuccessful) {
                        response.body.byteStream().use { input ->
                            FileOutputStream(tmpZip).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                                output.flush()
                            }
                        }
                        if (cachedZipFile.exists()) cachedZipFile.delete()
                        val renamed = tmpZip.renameTo(cachedZipFile)
                        if (!renamed) {
                            tmpZip.copyTo(cachedZipFile, overwrite = true)
                            tmpZip.delete()
                        }
                        pruneCache(zipDir, MAX_CACHE_BYTES, MAX_ZIP_FILES)
                        true
                    } else {
                        Log.w(TAG, "Failed downloading zip from $zipUrl: HTTP ${response.code}")
                        false
                    }
                }
                if (!downloadOk) {
                    if (cachedZipFile.exists()) cachedZipFile.delete()
                    return null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception downloading zip: ${e.message}")
                if (cachedZipFile.exists()) cachedZipFile.delete()
                return null
            } finally {
                if (tmpZip.exists()) tmpZip.delete()
            }
        } else {
            cachedZipFile.setLastModified(System.currentTimeMillis())
        }

        // 3. Extract the requested episode entry
        val result = extractFromZipFile(cachedZipFile, targetEp, subsDir, sanitizedZipName)
        return result
    }

    /**
     * LRU Cache eviction: deletes oldest modified files when file count or total size exceeds limits.
     */
    @Synchronized
    fun pruneCache(directory: File, maxSizeBytes: Long, maxFileCount: Int) {
        if (!directory.exists() || !directory.isDirectory) return
        val files = directory.listFiles() ?: return
        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        // Delete stale temporary files older than 10 minutes
        for (f in files) {
            if (f.isFile && f.name.startsWith("tmp_")) {
                if (now - f.lastModified() > 10 * 60 * 1000L) {
                    f.delete()
                }
            }
        }

        val remainingFiles = directory.listFiles() ?: return
        val sortedFiles = remainingFiles.filter { it.isFile && !it.name.startsWith("tmp_") }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var currentTotalSize = sortedFiles.sumOf { it.length() }
        var currentCount = sortedFiles.size

        while (sortedFiles.isNotEmpty() && (currentTotalSize > maxSizeBytes || currentCount > maxFileCount)) {
            val oldest = sortedFiles.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) {
                currentTotalSize -= size
                currentCount--
                Log.d(TAG, "Pruned old cache file: ${oldest.name}")
            }
        }
    }

    /**
     * Deletes all files in kitsunekko_cache and kitsunekko_subs.
     */
    @Synchronized
    fun clearAllCache(context: Context) {
        try {
            val zipDir = getZipCacheDir(context)
            zipDir.listFiles()?.forEach { it.delete() }

            val subsDir = getSubsCacheDir(context)
            subsDir.listFiles()?.forEach { it.delete() }
            Log.i(TAG, "Cleared all Kitsunekko caches")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear cache: ${e.message}", e)
        }
    }

    /** Alias for clearAllCache */
    fun clearCache(context: Context) = clearAllCache(context)

    /**
     * Computes cache metrics for Settings UI display.
     */
    fun getCacheSummary(
        context: Context,
        dirItemCount: Int = 0,
        dirLastUpdated: Long = 0L
    ): KitsunekkoCacheSummary {
        val zipDir = getZipCacheDir(context)
        val subsDir = getSubsCacheDir(context)

        val zipBytes = zipDir.listFiles()?.sumOf { it.length() } ?: 0L
        val subsBytes = subsDir.listFiles()?.sumOf { it.length() } ?: 0L
        val subCount = subsDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.size ?: 0

        val now = System.currentTimeMillis()
        val rootAgeHours = if (dirLastUpdated > 0L) {
            (now - dirLastUpdated).toDouble() / (1000.0 * 3600.0)
        } else {
            -1.0
        }

        return KitsunekkoCacheSummary(
            rootCacheAgeHours = rootAgeHours,
            cachedSubsCount = subCount,
            cacheSizeBytes = zipBytes + subsBytes,
            directoryItemCount = dirItemCount,
            directoryLastUpdated = dirLastUpdated,
            zipCacheBytes = zipBytes,
            subsCacheBytes = subsBytes
        )
    }
}
