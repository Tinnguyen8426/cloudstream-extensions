package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenger M6-1 Adversarial Stress Test Suite for SubtitleCacheManager.
 * Focus:
 * 1. Windows atomic replacement under simulated concurrent reader locks & TOCTOU gap elimination.
 * 2. High-throughput reader-writer race condition safety.
 * 3. Exact boundary condition testing for recursive pruning:
 *    - Fresh tmp_ files (< 5 min)
 *    - Stale tmp_ files (> 5 min)
 *    - Active chunk folders (< 24 hr)
 *    - Abandoned chunk folders (> 24 hr)
 *    - 30-day TTL expiration
 *    - Count-based LRU eviction (250 files down to 200)
 *    - Byte-based LRU eviction (60MB down to 50MB)
 *    - LRU touch update on getFullFile()
 * 4. Tier 2 Progressive Chunk lifecycle and Jackson numeric key deserialization.
 */
object Phase2CacheAdversarialStressTest {

    private var passed = 0
    private var failed = 0

    private fun assertTest(name: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            println("  [FAIL] $name -> $details")
            failed++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(">>> STARTING CHALLENGER M6-1 ADVERSARIAL CACHE STRESS HARNESS")
        println("================================================================================")

        val tempTestDir = File(System.getProperty("java.io.tmpdir", "."), "challenger_m6_1_${System.currentTimeMillis()}")
        if (tempTestDir.exists()) tempTestDir.deleteRecursively()
        tempTestDir.mkdirs()

        try {
            SubtitleCacheManager.customBaseDir = tempTestDir

            // -----------------------------------------------------------------
            // SUITE 1: SHA-256 Composite Key & Normalization Stress
            // -----------------------------------------------------------------
            testCompositeKeyInvariants()

            // -----------------------------------------------------------------
            // SUITE 2: Windows Atomic Replacement Under Simulated Reader Locks
            // -----------------------------------------------------------------
            testWindowsReaderLockResilience()

            // -----------------------------------------------------------------
            // SUITE 3: High-Throughput Concurrent Reader-Writer TOCTOU Stress
            // -----------------------------------------------------------------
            testConcurrentReaderWriterRaceNoToctou()

            // -----------------------------------------------------------------
            // SUITE 4: Recursive Pruning Boundary Edge Cases
            // -----------------------------------------------------------------
            testRecursivePruningBoundaries()

            // -----------------------------------------------------------------
            // SUITE 5: LRU Eviction Under Exact 200 Files & 50MB Limits
            // -----------------------------------------------------------------
            testLruEvictionExactLimits()

            // -----------------------------------------------------------------
            // SUITE 6: Tier 2 Chunk Cache Lifecycle & JSON Type Resilience
            // -----------------------------------------------------------------
            testTier2ChunkLifecycle()

        } finally {
            SubtitleCacheManager.customBaseDir = null
            if (tempTestDir.exists()) {
                tempTestDir.deleteRecursively()
            }
        }

        println("================================================================================")
        println("CHALLENGER M6-1 RESULTS: $passed PASSED, $failed FAILED")
        println("================================================================================")
        if (failed > 0) {
            System.exit(1)
        }
    }

    private fun testCompositeKeyInvariants() {
        println("\n--- [SUITE 1] SHA-256 Composite Key & Normalization Invariants ---")
        val baseContent = "1\n00:00:01,000 --> 00:00:02,000\nHello World\n"
        val crlfContent = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHello World\r\n"
        val trailingContent = "  1\n00:00:01,000 --> 00:00:02,000\nHello World\n  "

        val k1 = SubtitleCacheManager.buildCacheKey(baseContent, "vi", "openai", "gpt-4o-mini", "")
        val k2 = SubtitleCacheManager.buildCacheKey(crlfContent, "vi", "openai", "gpt-4o-mini", "")
        val k3 = SubtitleCacheManager.buildCacheKey(trailingContent, "vi", "openai", "gpt-4o-mini", "")

        assertTest("CRLF vs LF produces identical SHA-256 composite key", k1 == k2, "k1=$k1, k2=$k2")
        assertTest("Leading/trailing whitespace trim yields identical key", k1 == k3, "k1=$k1, k3=$k3")

        val kLang = SubtitleCacheManager.buildCacheKey(baseContent, "ja", "openai", "gpt-4o-mini", "")
        val kProv = SubtitleCacheManager.buildCacheKey(baseContent, "vi", "gemini", "gpt-4o-mini", "")
        val kModel = SubtitleCacheManager.buildCacheKey(baseContent, "vi", "openai", "gpt-4o", "")
        val kGlossary = SubtitleCacheManager.buildCacheKey(baseContent, "vi", "openai", "gpt-4o-mini", "World: Thế giới")
        val kGlossaryWhitespace = SubtitleCacheManager.buildCacheKey(baseContent, "vi", "openai", "gpt-4o-mini", "   ")

        assertTest("Mutating targetLang produces distinct key", k1 != kLang)
        assertTest("Mutating provider produces distinct key", k1 != kProv)
        assertTest("Mutating model produces distinct key", k1 != kModel)
        assertTest("Non-blank glossary produces distinct key", k1 != kGlossary)
        assertTest("Blank glossary normalizes to noglossary identical to empty", k1 == kGlossaryWhitespace)
    }

    private fun testWindowsReaderLockResilience() {
        println("\n--- [SUITE 2] Windows Atomic Replacement Under Simulated Reader Locks ---")
        val key = "reader_lock_stress_key"
        val initialContent = "1\n00:00:00,000 --> 00:00:01,000\nInitial Version\n"
        val updatedContent = "1\n00:00:00,000 --> 00:00:01,000\nUpdated Version\n"

        // Step 1: Save initial file
        val file = SubtitleCacheManager.saveFullFile(null, key, initialContent)
        assertTest("Initial file created", file.exists() && file.readText() == initialContent)

        // Step 2: Open reader lock on Windows (simulating active streaming reader holding handle)
        val lockLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        val writeCompleted = AtomicBoolean(false)
        val writeExceptions = ConcurrentLinkedQueue<Throwable>()

        val readerThread = Thread {
            try {
                FileInputStream(file).use { stream ->
                    val buffer = ByteArray(16)
                    val readBytes = stream.read(buffer)
                    assert(readBytes > 0)
                    lockLatch.countDown()
                    // Hold open handle for 30ms to simulate slow network transmission of chunk
                    Thread.sleep(30)
                }
            } catch (t: Throwable) {
                // Reader exception
            } finally {
                doneLatch.countDown()
            }
        }
        readerThread.start()

        // Wait until reader has acquired open handle
        lockLatch.await(5, TimeUnit.SECONDS)

        // Step 3: Concurrently perform atomic replacement while reader holds lock
        val writerThread = Thread {
            try {
                SubtitleCacheManager.saveFullFile(null, key, updatedContent)
                writeCompleted.set(true)
            } catch (t: Throwable) {
                writeExceptions.add(t)
            }
        }
        writerThread.start()

        writerThread.join(5000)
        readerThread.join(5000)

        assertTest(
            "saveFullFile completes without throwing Windows file lock exception",
            writeExceptions.isEmpty() && writeCompleted.get(),
            "Exceptions: ${writeExceptions.map { it.message }}"
        )

        val finalContent = SubtitleCacheManager.getFullFile(null, key)?.readText()
        assertTest(
            "Final content matches updated version after lock release and retry",
            finalContent == updatedContent,
            "Actual content: $finalContent"
        )
    }

    private fun testConcurrentReaderWriterRaceNoToctou() {
        println("\n--- [SUITE 3] High-Throughput Concurrent Reader-Writer TOCTOU Stress ---")
        val key = "toctou_race_key"
        val versionA = "1\n00:00:01,000 --> 00:00:02,000\nVersion A\n"
        val versionB = "1\n00:00:01,000 --> 00:00:02,000\nVersion B\n"

        SubtitleCacheManager.saveFullFile(null, key, versionA)

        val numReaders = 10
        val readsPerThread = 50
        val executor = Executors.newFixedThreadPool(numReaders + 2)

        val nullFileCount = AtomicInteger(0)
        val notExistsCount = AtomicInteger(0)
        val readExceptionCount = AtomicInteger(0)
        val readExceptions = ConcurrentLinkedQueue<String>()
        val emptyFileCount = AtomicInteger(0)
        val validReads = AtomicInteger(0)
        val corruptedReads = AtomicInteger(0)

        val running = AtomicBoolean(true)

        // Launch writer continuously toggling version A and version B
        val writerFuture = executor.submit {
            var counter = 0
            while (running.get() && counter < 40) {
                val content = if (counter % 2 == 0) versionB else versionA
                SubtitleCacheManager.saveFullFile(null, key, content)
                counter++
                Thread.sleep(2)
            }
        }

        // Launch readers concurrently hammering getFullFile and reading contents
        val readerFutures = (1..numReaders).map {
            executor.submit {
                for (i in 1..readsPerThread) {
                    val f = SubtitleCacheManager.getFullFile(null, key)
                    if (f == null) {
                        nullFileCount.incrementAndGet()
                    } else if (!f.exists()) {
                        notExistsCount.incrementAndGet()
                    } else {
                        try {
                            val text = f.readText()
                            if (text.isEmpty()) {
                                emptyFileCount.incrementAndGet()
                            } else if (text == versionA || text == versionB) {
                                validReads.incrementAndGet()
                            } else {
                                corruptedReads.incrementAndGet()
                            }
                        } catch (t: Throwable) {
                            readExceptionCount.incrementAndGet()
                            readExceptions.add("${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            }
        }

        readerFutures.forEach { it.get(10, TimeUnit.SECONDS) }
        running.set(false)
        writerFuture.get(10, TimeUnit.SECONDS)
        executor.shutdown()

        val totalAttempts = numReaders * readsPerThread
        println("  -> Diagnostic: nullReturned=${nullFileCount.get()}, notExists=${notExistsCount.get()}, readExceptions=${readExceptionCount.get()}, valid=${validReads.get()}")
        if (readExceptions.isNotEmpty()) {
            println("  -> Sample read exception: ${readExceptions.peek()}")
        }
        assertTest(
            "Zero TOCTOU gap: getFullFile never returned null during concurrent atomic replacements",
            nullFileCount.get() == 0,
            "Null count: ${nullFileCount.get()}/$totalAttempts"
        )
        assertTest(
            "Target file always exists",
            notExistsCount.get() == 0,
            "Not exists count: ${notExistsCount.get()}/$totalAttempts"
        )
        assertTest(
            "Zero zero-byte reads during atomic replacement",
            emptyFileCount.get() == 0,
            "Empty count: ${emptyFileCount.get()}/$totalAttempts"
        )
        assertTest(
            "Zero corrupted or torn reads",
            corruptedReads.get() == 0,
            "Corrupted: ${corruptedReads.get()}/$totalAttempts"
        )
    }

    private fun testRecursivePruningBoundaries() {
        println("\n--- [SUITE 4] Recursive Pruning Exact Boundary Edge Cases ---")
        val fullDir = SubtitleCacheManager.getFullDir(null)
        val chunksDir = SubtitleCacheManager.getChunksDir(null)

        // Clear directories first
        fullDir.listFiles()?.forEach { it.deleteRecursively() }
        chunksDir.listFiles()?.forEach { it.deleteRecursively() }

        val now = System.currentTimeMillis()

        // 4.1 Fresh tmp_ files: created 1m ago, 4m ago, 4m50s ago (< 5m threshold)
        val freshTmp1 = File(fullDir, "tmp_1m_fresh.srt").apply {
            writeText("fresh 1m")
            setLastModified(now - 60_000L)
        }
        val freshTmp2 = File(fullDir, "tmp_4m_fresh.srt").apply {
            writeText("fresh 4m")
            setLastModified(now - 240_000L)
        }
        val freshTmp3 = File(fullDir, "tmp_4m50s_fresh.srt").apply {
            writeText("fresh 4m50s")
            setLastModified(now - 290_000L)
        }

        // 4.2 Stale tmp_ files: created 5m10s ago, 10m ago, 24h ago (> 5m threshold)
        val staleTmp1 = File(fullDir, "tmp_5m10s_stale.srt").apply {
            writeText("stale 5m10s")
            setLastModified(now - 310_000L)
        }
        val staleTmp2 = File(fullDir, "tmp_10m_stale.srt").apply {
            writeText("stale 10m")
            setLastModified(now - 600_000L)
        }
        val staleTmp3 = File(fullDir, "tmp_24h_stale.srt").apply {
            writeText("stale 24h")
            setLastModified(now - 86_400_000L)
        }

        // 4.3 Active chunk folders: modified 10m ago, 23h ago (< 24h threshold)
        val activeChunk1 = File(chunksDir, "active_chunk_10m").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 600_000L)
        }
        val activeChunk2 = File(chunksDir, "active_chunk_23h").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 23L * 3600_000L)
        }

        // 4.4 Abandoned chunk folders: modified 24h10m ago, 48h ago (> 24h threshold)
        val abandonedChunk1 = File(chunksDir, "abandoned_chunk_24h10m").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 24L * 3600_000L - 600_000L)
        }
        val abandonedChunk2 = File(chunksDir, "abandoned_chunk_48h").apply {
            mkdirs()
            File(this, "chunk_0001.json").writeText("{}")
            setLastModified(now - 48L * 3600_000L)
        }

        // 4.5 30-day TTL: files 29 days old vs files 31 days old
        val fileFreshTtl = File(fullDir, "ttl_fresh_29d.srt").apply {
            writeText("valid ttl")
            setLastModified(now - 29L * 86_400_000L)
        }
        val fileExpiredTtl = File(fullDir, "ttl_expired_31d.srt").apply {
            writeText("expired ttl")
            setLastModified(now - 31L * 86_400_000L)
        }

        // Run pruneCache with default parameters
        SubtitleCacheManager.pruneCache(null)

        // Assertions
        assertTest("Fresh tmp_ file (1m) preserved", freshTmp1.exists())
        assertTest("Fresh tmp_ file (4m) preserved", freshTmp2.exists())
        assertTest("Fresh tmp_ file (4m50s) preserved", freshTmp3.exists())

        assertTest("Stale tmp_ file (5m10s) purged", !staleTmp1.exists())
        assertTest("Stale tmp_ file (10m) purged", !staleTmp2.exists())
        assertTest("Stale tmp_ file (24h) purged", !staleTmp3.exists())

        assertTest("Active chunk folder (10m) preserved", activeChunk1.exists())
        assertTest("Active chunk folder (23h) preserved", activeChunk2.exists())

        assertTest("Abandoned chunk folder (24h10m) recursively purged", !abandonedChunk1.exists())
        assertTest("Abandoned chunk folder (48h) recursively purged", !abandonedChunk2.exists())

        assertTest("File within 30-day TTL (29d) preserved", fileFreshTtl.exists())
        assertTest("File exceeding 30-day TTL (31d) purged", !fileExpiredTtl.exists())
    }

    private fun testLruEvictionExactLimits() {
        println("\n--- [SUITE 5] LRU Eviction Under Exact 200 Files & 50MB Limits ---")
        val fullDir = SubtitleCacheManager.getFullDir(null)
        fullDir.listFiles()?.forEach { it.delete() }

        val now = System.currentTimeMillis()

        // 5.1 Test File Count Limit: 220 files created, maxFiles = 200
        for (i in 1..220) {
            val f = File(fullDir, "count_test_%03d.srt".format(i))
            f.writeText("test file $i")
            f.setLastModified(now - (250 - i) * 1000L) // File 1 is oldest, file 220 is newest
        }

        SubtitleCacheManager.pruneCache(null, maxBytes = 500L * 1024 * 1024, maxFiles = 200)

        val remainingFiles = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") } ?: emptyList()
        assertTest(
            "LRU eviction reduces 220 files to exactly 200 files",
            remainingFiles.size == 200,
            "Actual count: ${remainingFiles.size}"
        )

        val oldestSurviving = remainingFiles.minByOrNull { it.lastModified() }
        val newestSurviving = remainingFiles.maxByOrNull { it.lastModified() }
        assertTest(
            "Oldest 20 files were evicted and newest 200 files were preserved",
            oldestSurviving?.name == "count_test_021.srt" && newestSurviving?.name == "count_test_220.srt",
            "Oldest: ${oldestSurviving?.name}, Newest: ${newestSurviving?.name}"
        )

        // 5.2 Test Byte Limit: 60 files of 1MB each (60MB), maxBytes = 50MB (50 * 1024 * 1024)
        fullDir.listFiles()?.forEach { it.delete() }
        val oneMb = ByteArray(1024 * 1024) { 'a'.code.toByte() }

        for (i in 1..60) {
            val f = File(fullDir, "size_test_%03d.srt".format(i))
            f.writeBytes(oneMb)
            f.setLastModified(now - (100 - i) * 1000L) // 1 is oldest, 60 is newest
        }

        val (initCount, initBytes) = SubtitleCacheManager.getMetrics(null)
        assertTest("Initial 60MB populated", initCount == 60 && initBytes == 60L * 1024 * 1024)

        // Prune with 50MB limit
        SubtitleCacheManager.pruneCache(null, maxBytes = 50L * 1024 * 1024, maxFiles = 200)

        val (prunedCount, prunedBytes) = SubtitleCacheManager.getMetrics(null)
        assertTest(
            "LRU eviction reduced 60MB down to exactly 50MB (50 files)",
            prunedCount == 50 && prunedBytes == 50L * 1024 * 1024,
            "Count: $prunedCount, Bytes: $prunedBytes"
        )

        // 5.3 Test LRU Touch Behavior via getFullFile()
        // If oldest file is accessed via getFullFile, its lastModified is bumped to now!
        val remainingSorted = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        val candidateToTouch = remainingSorted.first() // size_test_011.srt
        val candidateKey = candidateToTouch.nameWithoutExtension

        // Touch it via getFullFile
        Thread.sleep(50)
        val touched = SubtitleCacheManager.getFullFile(null, candidateKey)
        assertTest("getFullFile successfully accessed candidate", touched != null)

        // Now prune to 49MB (evict 1 file)
        SubtitleCacheManager.pruneCache(null, maxBytes = 49L * 1024 * 1024, maxFiles = 200)

        assertTest(
            "Touched file was NOT evicted because getFullFile bumped its LRU timestamp",
            candidateToTouch.exists(),
            "Expected ${candidateToTouch.name} to survive"
        )
    }

    private fun testTier2ChunkLifecycle() {
        println("\n--- [SUITE 6] Tier 2 Progressive Chunk Lifecycle & JSON Resilience ---")
        val chunkKey = "chunk_stress_key_999"

        assertTest("hasChunks initially false", !SubtitleCacheManager.hasChunks(null, chunkKey))

        // Save chunk 1 with integer map
        val c1 = mapOf(1 to "First sentence", 2 to "Second sentence")
        SubtitleCacheManager.saveChunk(null, chunkKey, 1, c1)

        // Save chunk 2
        val c2 = mapOf(3 to "Third sentence", 4 to "Fourth sentence")
        SubtitleCacheManager.saveChunk(null, chunkKey, 2, c2)

        assertTest("hasChunks returns true after saving chunks", SubtitleCacheManager.hasChunks(null, chunkKey))

        val loaded = SubtitleCacheManager.loadChunks(null, chunkKey)
        assertTest(
            "loadChunks merges both chunks cleanly",
            loaded.size == 4 && loaded[1] == "First sentence" && loaded[4] == "Fourth sentence",
            "Loaded: $loaded"
        )

        // Test clearChunks
        SubtitleCacheManager.clearChunks(null, chunkKey)
        assertTest(
            "clearChunks cleans up chunk directory",
            !SubtitleCacheManager.hasChunks(null, chunkKey) && SubtitleCacheManager.loadChunks(null, chunkKey).isEmpty()
        )
    }
}
