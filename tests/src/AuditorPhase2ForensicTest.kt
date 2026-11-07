package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent Forensic Audit Test Harness for Phase 2 Implementation.
 * Verifies authenticity, absence of hardcoding, resilience under adversarial stress,
 * and Windows concurrency robustness.
 */
fun main() {
    println("================================================================================")
    println(">>> FORENSIC AUDIT: Independent Verification Suite for Phase 2")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun auditCheck(checkId: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [AUDIT-PASS] $checkId")
            passed++
        } else {
            println("  [AUDIT-FAIL] $checkId -> $details")
            failed++
        }
    }

    val tempAuditDir = File(System.getProperty("java.io.tmpdir", "."), "auditor_phase2_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
    if (tempAuditDir.exists()) tempAuditDir.deleteRecursively()
    tempAuditDir.mkdirs()

    try {
        SubtitleCacheManager.customBaseDir = tempAuditDir
        AITranslatorLocalServer.customBaseDir = tempAuditDir

        // ---------------------------------------------------------------------
        // CHECK 1: Dynamic SHA-256 Verification against Independent MessageDigest
        // ---------------------------------------------------------------------
        for (i in 1..5) {
            val randomString = UUID.randomUUID().toString() + " - " + System.nanoTime() + " - Tiếng Việt 語 🚀"
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(randomString.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val actualHash = SubtitleCacheManager.computeSha256(randomString)
            auditCheck(
                "SHA-256 authentic computation iteration $i",
                actualHash == expectedHash && actualHash.length == 64,
                "Expected $expectedHash, got $actualHash"
            )
        }

        // ---------------------------------------------------------------------
        // CHECK 2: Composite Cache Key Invariant Stress
        // ---------------------------------------------------------------------
        val randomContent = "1\n00:00:00,000 --> 00:00:02,000\nRandom cue " + UUID.randomUUID().toString()
        val keyA = SubtitleCacheManager.buildCacheKey(randomContent, "vi", "openai", "gpt-4o-mini", "")
        val keyB = SubtitleCacheManager.buildCacheKey(randomContent.replace("\n", "\r\n"), "vi", "openai", "gpt-4o-mini", "")
        val keyC = SubtitleCacheManager.buildCacheKey(randomContent, "vi-VN", "openai", "gpt-4o-mini", "")
        val keyD = SubtitleCacheManager.buildCacheKey(randomContent, "vi", "gemini", "gpt-4o-mini", "")
        val keyE = SubtitleCacheManager.buildCacheKey(randomContent, "vi", "openai", "gpt-4o", "")
        val keyF = SubtitleCacheManager.buildCacheKey(randomContent, "vi", "openai", "gpt-4o-mini", "Glossary A")

        auditCheck("CRLF normalization produces identical 64-char key", keyA == keyB && keyA.length == 64, "keyA=$keyA, keyB=$keyB")
        auditCheck("TargetLang variance produces distinct key", keyA != keyC, "Collision detected: $keyA")
        auditCheck("Provider variance produces distinct key", keyA != keyD, "Collision detected: $keyA")
        auditCheck("Model variance produces distinct key", keyA != keyE, "Collision detected: $keyA")
        auditCheck("Glossary variance produces distinct key", keyA != keyF, "Collision detected: $keyA")

        // ---------------------------------------------------------------------
        // CHECK 3: Tier 1 Atomic File Writing & Windows Fallback
        // ---------------------------------------------------------------------
        val testSrt = "1\n00:00:01,000 --> 00:00:04,000\nForensic test content\n\n"
        val savedFile = SubtitleCacheManager.saveFullFile(null, keyA, testSrt)
        auditCheck("Tier 1 saveFullFile produces non-empty file on disk", savedFile.exists() && savedFile.length() > 0L)
        auditCheck("Tier 1 content exactly matches written text", savedFile.readText(StandardCharsets.UTF_8) == testSrt)

        val retrievedFile = SubtitleCacheManager.getFullFile(null, keyA)
        auditCheck("Tier 1 getFullFile retrieves saved file", retrievedFile != null && retrievedFile.absolutePath == savedFile.absolutePath)

        // ---------------------------------------------------------------------
        // CHECK 4: Tier 2 Progressive Chunk Storage & Jackson Int Deserialization
        // ---------------------------------------------------------------------
        val chunkKey = "forensic_chunk_" + UUID.randomUUID().toString()
        auditCheck("hasChunks initially returns false", !SubtitleCacheManager.hasChunks(null, chunkKey))

        val chunk1 = mapOf(10 to "Cue Ten", 20 to "Cue Twenty")
        val chunk2 = mapOf(30 to "Cue Thirty", 40 to "Cue Forty")
        SubtitleCacheManager.saveChunk(null, chunkKey, 1, chunk1)
        SubtitleCacheManager.saveChunk(null, chunkKey, 2, chunk2)

        auditCheck("hasChunks returns true after saving chunks", SubtitleCacheManager.hasChunks(null, chunkKey))

        val loadedChunks = SubtitleCacheManager.loadChunks(null, chunkKey)
        val chunkKeysMatch = loadedChunks.keys.toSet() == setOf(10, 20, 30, 40)
        val chunkValuesMatch = loadedChunks[10] == "Cue Ten" && loadedChunks[40] == "Cue Forty"
        auditCheck("loadChunks reconstructs all Int indices accurately", chunkKeysMatch && chunkValuesMatch, "Loaded: $loadedChunks")

        SubtitleCacheManager.clearChunks(null, chunkKey)
        auditCheck("clearChunks recursively cleans directory", !SubtitleCacheManager.hasChunks(null, chunkKey) && SubtitleCacheManager.loadChunks(null, chunkKey).isEmpty())

        // ---------------------------------------------------------------------
        // CHECK 5: Recursive Pruning Verification
        // ---------------------------------------------------------------------
        val fullDir = SubtitleCacheManager.getFullDir(null)
        val chunksDir = SubtitleCacheManager.getChunksDir(null)
        val now = System.currentTimeMillis()

        // 5.1 Abandoned tmp_ (> 5 min) vs fresh tmp_ (1 min)
        val abandonedTmp = File(fullDir, "tmp_abandoned_test.srt").apply {
            writeText("abandoned tmp")
            setLastModified(now - 400_000L) // > 6.6 min
        }
        val freshTmp = File(fullDir, "tmp_fresh_test.srt").apply {
            writeText("fresh tmp")
            setLastModified(now - 60_000L) // 1 min
        }

        // 5.2 Abandoned chunk dir (> 24 hr) vs active chunk dir (1 hr)
        val abandonedChunks = File(chunksDir, "abandoned_dir").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 90_000_000L) // 25 hrs
        }
        val activeChunks = File(chunksDir, "active_dir").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 3_600_000L) // 1 hr
        }

        // 5.3 12 full files of 100KB each
        for (i in 1..12) {
            val f = File(fullDir, "cap_file_%02d.srt".format(i))
            f.writeBytes(ByteArray(100 * 1024) { 'z'.code.toByte() })
            f.setLastModified(now - (12 - i) * 60_000L)
        }

        SubtitleCacheManager.pruneCache(null, maxBytes = 800 * 1024L, maxFiles = 8)

        auditCheck("Pruner removes abandoned tmp_ (> 5 min)", !abandonedTmp.exists())
        auditCheck("Pruner preserves fresh tmp_ (< 5 min)", freshTmp.exists())
        auditCheck("Pruner removes abandoned chunk dir (> 24 hr)", !abandonedChunks.exists())
        auditCheck("Pruner preserves active chunk dir (< 24 hr)", activeChunks.exists())

        val remainingFiles = fullDir.listFiles { f -> f.isFile && !f.name.startsWith("tmp_") } ?: emptyArray()
        val totalBytes = remainingFiles.sumOf { it.length() }
        auditCheck("Pruner enforces maxFiles <= 8 and maxBytes <= 800KB", remainingFiles.size <= 8 && totalBytes <= 800 * 1024L, "Count: ${remainingFiles.size}, Bytes: $totalBytes")

        freshTmp.delete()

        // ---------------------------------------------------------------------
        // CHECK 6: Loopback HTTP Server Authenticity & Security
        // ---------------------------------------------------------------------
        val port = AITranslatorLocalServer.start(null)
        auditCheck("Server binds to ephemeral port > 1024", port > 1024 && AITranslatorLocalServer.isRunning)

        // Raw socket inspection to verify real TCP loopback binding
        val rawSocket = Socket("127.0.0.1", port)
        auditCheck("Raw TCP connection to 127.0.0.1:$port succeeds", rawSocket.isConnected)
        rawSocket.close()

        // Create a large 200KB subtitle file to verify streaming
        val largeSrtBuilder = StringBuilder()
        for (idx in 1..1000) {
            largeSrtBuilder.append("$idx\n00:00:01,000 --> 00:00:03,000\nLine $idx in large subtitle stream\n\n")
        }
        val largeSrtContent = largeSrtBuilder.toString()
        val largeKey = "large_sub_key"
        File(fullDir, "$largeKey.srt").writeText(largeSrtContent, StandardCharsets.UTF_8)

        // Stream via HTTP GET
        val streamUrl = AITranslatorLocalServer.getSubtitleUrl(largeKey)
        val conn = URL(streamUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        val cType = conn.contentType
        val streamedBody = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        conn.disconnect()

        auditCheck("Large 200KB subtitle streams with HTTP 200 OK", code == 200)
        auditCheck("Content-Type is text/plain; charset=utf-8", cType != null && cType.contains("text/plain"))
        auditCheck("Streamed body matches exact large content (zero corruption)", streamedBody == largeSrtContent)

        // Security check: Path traversal attacks
        val traversalAttacks = listOf(
            "/sub/../../secret.txt",
            "/sub/..\\..\\secret.txt",
            "/sub/%2e%2e%2fsecret.txt",
            "/sub/sub/../../secret.txt"
        )
        for ((idx, attack) in traversalAttacks.withIndex()) {
            var attackCode = 0
            try {
                val aConn = URL("http://127.0.0.1:$port$attack").openConnection() as HttpURLConnection
                aConn.connectTimeout = 3000
                aConn.readTimeout = 3000
                attackCode = aConn.responseCode
                aConn.disconnect()
            } catch (e: Exception) {
                if (e.message?.contains("403") == true) attackCode = 403
                else if (e.message?.contains("404") == true) attackCode = 404
            }
            auditCheck("Traversal attack $idx ($attack) rejected with 403/404", attackCode == 403 || attackCode == 404, "Code: $attackCode")
        }

        // Unsupported HTTP method POST
        var postCode = 0
        try {
            val postConn = URL(streamUrl).openConnection() as HttpURLConnection
            postConn.requestMethod = "POST"
            postConn.doOutput = true
            postConn.outputStream.write("test".toByteArray())
            postCode = postConn.responseCode
            postConn.disconnect()
        } catch (e: Exception) {
            if (e.message?.contains("405") == true) postCode = 405
        }
        auditCheck("HTTP POST rejected with 405 Method Not Allowed", postCode == 405, "Code: $postCode")

        // Concurrency test: 30 concurrent HTTP clients
        val httpExecutor = Executors.newFixedThreadPool(10)
        val httpSuccesses = AtomicInteger(0)
        val httpFutures = (1..30).map {
            httpExecutor.submit {
                val c = URL(streamUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                if (c.responseCode == 200) {
                    val b = c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    if (b.length == largeSrtContent.length) {
                        httpSuccesses.incrementAndGet()
                    }
                }
                c.disconnect()
            }
        }
        httpFutures.forEach { it.get() }
        httpExecutor.shutdown()

        auditCheck("30 concurrent HTTP client streams all succeed 100%", httpSuccesses.get() == 30, "Successes: ${httpSuccesses.get()}/30")

        AITranslatorLocalServer.stop()
        auditCheck("Server stops cleanly and port resets to 0", !AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == 0)

    } finally {
        AITranslatorLocalServer.stop()
        if (tempAuditDir.exists()) tempAuditDir.deleteRecursively()
    }

    println("================================================================================")
    println("AUDIT VERIFICATION SUMMARY: $passed PASSED, $failed FAILED")
    println("================================================================================")
    if (failed > 0) {
        System.exit(1)
    }
}
