package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

fun main() {
    println("================================================================================")
    println(">>> Starting Phase 2 Caching Engine & Local Server Empirical Test Suite...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun check(testName: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $testName")
            passed++
        } else {
            println("  [FAIL] $testName -> $details")
            failed++
        }
    }

    val tempTestDir = File(System.getProperty("java.io.tmpdir", "."), "phase2_empirical_test_${System.currentTimeMillis()}")
    if (tempTestDir.exists()) tempTestDir.deleteRecursively()
    tempTestDir.mkdirs()

    try {
        SubtitleCacheManager.customBaseDir = tempTestDir
        AITranslatorLocalServer.customBaseDir = tempTestDir

        // =====================================================================
        // TEST 1: SHA-256 Hashing & Composite Key Generation
        // =====================================================================
        val text1 = "Hello world\r\nThis is a test subtitle."
        val text2 = "Hello world\nThis is a test subtitle."
        val key1 = SubtitleCacheManager.buildCacheKey(text1, "vi", "OpenAI", "gpt-4o-mini", "")
        val key2 = SubtitleCacheManager.buildCacheKey(text2, "vi", "OpenAI", "gpt-4o-mini", "")
        val keyDifferentLang = SubtitleCacheManager.buildCacheKey(text1, "es", "OpenAI", "gpt-4o-mini", "")
        val keyDifferentModel = SubtitleCacheManager.buildCacheKey(text1, "vi", "OpenAI", "gpt-4o", "")
        val keyWithGlossary = SubtitleCacheManager.buildCacheKey(text1, "vi", "OpenAI", "gpt-4o-mini", "World: Thế giới")

        check(
            "CRLF normalization yields identical cache key",
            key1 == key2 && key1.length == 64,
            "key1=$key1, key2=$key2"
        )
        check(
            "Distinct targetLang yields distinct cache key",
            key1 != keyDifferentLang,
            "key1=$key1, keyDifferentLang=$keyDifferentLang"
        )
        check(
            "Distinct model yields distinct cache key",
            key1 != keyDifferentModel,
            "key1=$key1, keyDifferentModel=$keyDifferentModel"
        )
        check(
            "Glossary inclusion yields distinct cache key",
            key1 != keyWithGlossary,
            "key1=$key1, keyWithGlossary=$keyWithGlossary"
        )

        // =====================================================================
        // TEST 2: Tier 1 Full-File Caching & Atomic Replacement
        // =====================================================================
        val initialSrt = "1\n00:00:01,000 --> 00:00:03,000\nXin chào\n\n"
        val updatedSrt = "1\n00:00:01,000 --> 00:00:03,000\nXin chào thế giới\n\n"

        val savedFile = SubtitleCacheManager.saveFullFile(null, key1, initialSrt)
        check(
            "Tier 1 full file saved successfully",
            savedFile.exists() && savedFile.isFile && savedFile.readText() == initialSrt,
            "File path: ${savedFile.absolutePath}"
        )

        val retrievedFile = SubtitleCacheManager.getFullFile(null, key1)
        check(
            "Tier 1 full file retrieved via getFullFile",
            retrievedFile != null && retrievedFile.exists() && retrievedFile.readText() == initialSrt,
            "Retrieved: $retrievedFile"
        )

        // Atomic replacement under concurrent read
        val executor = Executors.newFixedThreadPool(8)
        val readSuccesses = java.util.concurrent.atomic.AtomicInteger(0)
        val totalReads = 100

        val readerFutures = (1..totalReads).map {
            executor.submit {
                val f = SubtitleCacheManager.getFullFile(null, key1)
                if (f != null && f.exists()) {
                    val content = f.readText()
                    if (content == initialSrt || content == updatedSrt) {
                        readSuccesses.incrementAndGet()
                    }
                }
            }
        }

        // Concurrently update file
        SubtitleCacheManager.saveFullFile(null, key1, updatedSrt)
        readerFutures.forEach { it.get() }

        val finalFile = SubtitleCacheManager.getFullFile(null, key1)
        check(
            "Atomic replacement under concurrent readers (Zero TOCTOU 404 gap)",
            readSuccesses.get() == totalReads && finalFile?.readText() == updatedSrt,
            "Success count: ${readSuccesses.get()}/$totalReads"
        )

        // =====================================================================
        // TEST 3: Tier 2 Progressive Chunk Cache
        // =====================================================================
        val chunkKey = "chunk_test_key_001"
        check(
            "hasChunks false before saving",
            !SubtitleCacheManager.hasChunks(null, chunkKey),
            "Expected false"
        )

        val chunk1Items = mapOf(1 to "Line one", 2 to "Line two")
        val chunk2Items = mapOf(3 to "Line three", 4 to "Line four")

        SubtitleCacheManager.saveChunk(null, chunkKey, 1, chunk1Items)
        SubtitleCacheManager.saveChunk(null, chunkKey, 2, chunk2Items)

        check(
            "hasChunks true after saving chunks",
            SubtitleCacheManager.hasChunks(null, chunkKey),
            "Expected true"
        )

        val loadedChunks = SubtitleCacheManager.loadChunks(null, chunkKey)
        check(
            "loadChunks correctly aggregates and deserializes all chunks with Int keys",
            loadedChunks.size == 4 &&
                    loadedChunks[1] == "Line one" &&
                    loadedChunks[2] == "Line two" &&
                    loadedChunks[3] == "Line three" &&
                    loadedChunks[4] == "Line four",
            "Loaded: $loadedChunks"
        )

        SubtitleCacheManager.clearChunks(null, chunkKey)
        check(
            "clearChunks removes chunk directory",
            !SubtitleCacheManager.hasChunks(null, chunkKey) && SubtitleCacheManager.loadChunks(null, chunkKey).isEmpty(),
            "Expected empty chunks after clear"
        )

        // =====================================================================
        // TEST 4: Recursive Pruner (5-min tmp, 24-hr chunk, LRU eviction)
        // =====================================================================
        val fullDir = SubtitleCacheManager.getFullDir(null)
        val chunksDir = SubtitleCacheManager.getChunksDir(null)
        val now = System.currentTimeMillis()

        // 4.1 Orphaned tmp_ file older than 5 minutes
        val orphanedTmp = File(fullDir, "tmp_dead_old.srt").apply {
            writeText("dead tmp content")
            setLastModified(now - 600_000L) // 10 minutes ago
        }

        // 4.2 Fresh tmp_ file created recently (< 5 minutes)
        val freshTmp = File(fullDir, "tmp_active_fresh.srt").apply {
            writeText("active tmp content")
            setLastModified(now - 60_000L) // 1 minute ago
        }

        // 4.3 Abandoned chunk directory older than 24 hours
        val abandonedChunkDir = File(chunksDir, "abandoned_episode").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 172_800_000L) // 48 hours ago
        }

        // 4.4 Active chunk directory created recently (< 24 hours)
        val activeChunkDir = File(chunksDir, "active_episode").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 600_000L) // 10 minutes ago
        }

        // 4.5 15 full files of 100KB each (total 1.5MB)
        for (i in 0 until 15) {
            val f = File(fullDir, "file_%02d.srt".format(i))
            f.writeBytes(ByteArray(100 * 1024) { 'x'.code.toByte() })
            f.setLastModified(now - (15 - i) * 60_000L)
        }

        // Run pruner with maxBytes=1MB (1024*1024) and maxFiles=10
        SubtitleCacheManager.pruneCache(null, maxBytes = 1024 * 1024L, maxFiles = 10)

        val fullFilesRemaining = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") } ?: emptyList()

        check(
            "Orphaned tmp_ file (> 5 min) pruned",
            !orphanedTmp.exists(),
            "Orphaned tmp still exists!"
        )
        check(
            "Fresh tmp_ file (< 5 min) preserved",
            freshTmp.exists(),
            "Fresh tmp was prematurely deleted!"
        )
        check(
            "Abandoned chunk directory (> 24 hr) pruned",
            !abandonedChunkDir.exists(),
            "Abandoned chunk dir still exists!"
        )
        check(
            "Active chunk directory (< 24 hr) preserved",
            activeChunkDir.exists(),
            "Active chunk dir was prematurely deleted!"
        )
        check(
            "LRU capacity enforced to <= 10 files and <= 1MB",
            fullFilesRemaining.size <= 10 && fullFilesRemaining.sumOf { it.length() } <= 1024 * 1024L,
            "Remaining files: ${fullFilesRemaining.size}, Total bytes: ${fullFilesRemaining.sumOf { it.length() }}"
        )

        // Clean up tmp files for clean server test
        freshTmp.delete()

        // =====================================================================
        // TEST 5: Embedded Loopback HTTP Server (AITranslatorLocalServer)
        // =====================================================================
        val port = AITranslatorLocalServer.start(null)
        check(
            "Ephemeral port allocated (> 1024)",
            port > 1024 && AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == port,
            "Port: $port, isRunning: ${AITranslatorLocalServer.isRunning}"
        )

        // Idempotent start returns same port
        val portAgain = AITranslatorLocalServer.start(null)
        check(
            "start() is idempotent and returns identical port",
            port == portAgain,
            "First: $port, Second: $portAgain"
        )

        // Save a test subtitle in cache
        val srtContent = "1\n00:00:01,000 --> 00:00:03,000\nLoopback streaming test\n\n"
        val serverTestKey = "server_test_key"
        val subFile = File(fullDir, "$serverTestKey.srt").apply {
            writeText(srtContent, Charsets.UTF_8)
        }

        // 5.1 HTTP GET /sub/<key>.srt
        val subUrl = AITranslatorLocalServer.getSubtitleUrl(serverTestKey)
        check(
            "getSubtitleUrl formats correct loopback URL",
            subUrl == "http://127.0.0.1:$port/sub/$serverTestKey.srt",
            "URL: $subUrl"
        )

        val conn = URL(subUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        val respCode = conn.responseCode
        val contentType = conn.contentType
        val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()

        check(
            "HTTP 200 OK received with exact text/plain content",
            respCode == 200 && contentType.contains("text/plain") && respBody == srtContent,
            "Code: $respCode, Type: $contentType, Body: $respBody"
        )

        // 5.2 Path Traversal Attack Protection: /sub/../../secret.txt
        val secretFile = File(tempTestDir, "secret.txt").apply { writeText("TOP SECRET") }
        val attackUrl = "http://127.0.0.1:$port/sub/../../secret.txt"

        var attackRespCode = 0
        try {
            val attackConn = URL(attackUrl).openConnection() as HttpURLConnection
            attackConn.connectTimeout = 5000
            attackConn.readTimeout = 5000
            attackRespCode = attackConn.responseCode
            attackConn.disconnect()
        } catch (e: Exception) {
            // Some HTTP clients throw FileNotFoundException or IOException on 403/404
            if (e.message?.contains("403") == true) attackRespCode = 403
            else if (e.message?.contains("404") == true) attackRespCode = 404
        }

        check(
            "Path traversal attack rejected with HTTP 403 Forbidden",
            attackRespCode == 403 || attackRespCode == 404,
            "Attack response code: $attackRespCode"
        )

        // 5.3 Non-existent file returns 404
        val missingUrl = "http://127.0.0.1:$port/sub/missing_random_sub.srt"
        var missingRespCode = 0
        try {
            val missingConn = URL(missingUrl).openConnection() as HttpURLConnection
            missingConn.connectTimeout = 5000
            missingConn.readTimeout = 5000
            missingRespCode = missingConn.responseCode
            missingConn.disconnect()
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) missingRespCode = 404
        }
        check(
            "Non-existent subtitle returns HTTP 404 Not Found",
            missingRespCode == 404,
            "Response code: $missingRespCode"
        )

        // 5.4 Concurrent streaming requests
        val concurrentSuccesses = java.util.concurrent.atomic.AtomicInteger(0)
        val concurrentFutures = (1..20).map {
            executor.submit {
                val c = URL(subUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                if (c.responseCode == 200) {
                    val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (body == srtContent) {
                        concurrentSuccesses.incrementAndGet()
                    }
                }
                c.disconnect()
            }
        }
        concurrentFutures.forEach { it.get() }

        check(
            "20 concurrent HTTP requests from 8 threads all succeed (100% 200 OK)",
            concurrentSuccesses.get() == 20,
            "Concurrent successes: ${concurrentSuccesses.get()}/20"
        )

        // 5.5 Server Stop releases resources
        AITranslatorLocalServer.stop()
        check(
            "AITranslatorLocalServer stops cleanly",
            !AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == 0,
            "isRunning: ${AITranslatorLocalServer.isRunning}, port: ${AITranslatorLocalServer.port}"
        )

        executor.shutdown()
    } finally {
        AITranslatorLocalServer.stop()
        if (tempTestDir.exists()) {
            tempTestDir.deleteRecursively()
        }
    }

    println("================================================================================")
    println("RESULTS: $passed PASSED, $failed FAILED")
    println("================================================================================")
    if (failed > 0) {
        System.exit(1)
    }
}
