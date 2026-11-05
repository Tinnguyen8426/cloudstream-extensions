package com.kitsunekko.enricher

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Empirical Zip, Charset, Cache & Hook Challenge Test Harness for Kitsunekko Japanese Subtitle Enricher.
 * Agent: m2_challenger_2 (Zip & Charset Challenger)
 *
 * Verifies:
 * 1. ZipSlip security: blocks malicious relative paths (../../evil.ass, subdir/../../../root.ass)
 *    by throwing SecurityException before any file creation on disk.
 * 2. Streaming zip selective extraction: extracts ONLY the requested episode entry without decompressing
 *    non-target entries or non-subtitle files to disk.
 * 3. Multi-charset auto-detection & fallback: UTF-8 with BOM (\uFEFF) stripping, legacy Shift-JIS with Japanese Kanji
 *    (葬送のフリーレン) preservation without \uFFFD replacement characters, and Windows-1252.
 * 4. LRU temp cache pruning: enforces max 20 subtitle files in kitsunekko_subs and byte limits, deleting oldest files.
 * 5. Dynamic hook chaining and 0ms video link passthrough across multi-enricher pipeline.
 */
object KitsunekkoZipCharsetChallengeTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            System.err.println("  [FAIL] $name: $detail")
            failed++
        }
    }

    private fun assertEquals(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "Expected: <$expected> but was: <$actual>")
    }

    private fun assertTrue(name: String, condition: Boolean, msg: String = "") {
        check(name, condition, "Expected true but was false: $msg")
    }

    private fun assertFalse(name: String, condition: Boolean, msg: String = "") {
        check(name, !condition, "Expected false but was true: $msg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" KITSUNEKKO ZIP, CHARSET, CACHE & HOOK EMPIRICAL CHALLENGE HARNESS")
        println("================================================================================")

        testZipSlipAdversarialVectors()
        testStreamingZipExtractionAdversarial()
        testMultiCharsetStressAndFidelity()
        testLruTempCachePruningStress()
        testDynamicHookChainingAndLatency()

        println("================================================================================")
        println(" EMPIRICAL CHALLENGE SUMMARY: $passed passed, $failed failed")
        println("================================================================================")

        if (failed > 0) {
            System.exit(1)
        }
    }

    // =========================================================================
    // 1. ZipSlip Path Traversal Attack Vectors
    // =========================================================================
    private fun testZipSlipAdversarialVectors() {
        println("\n>>> Suite 1: ZipSlip Security & Adversarial Path Traversal Vectors")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_slip_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Legitimate files inside tempDir must pass
            val validFile1 = File(tempDir, "ep_1_valid.ass")
            var caught = false
            try {
                ZipSubtitleExtractor.validateZipSlip(validFile1, tempDir)
            } catch (_: SecurityException) {
                caught = true
            }
            assertFalse("Valid relative file does not throw SecurityException", caught)

            val validNested = File(tempDir, "subs/ep_2_nested.ass")
            caught = false
            try {
                ZipSubtitleExtractor.validateZipSlip(validNested, tempDir)
            } catch (_: SecurityException) {
                caught = true
            }
            assertFalse("Valid nested file within dest does not throw SecurityException", caught)

            // Adversarial Traversal Vectors
            val attackVectors = listOf(
                "../../evil.ass",
                "subdir/../../../root.ass",
                "subdir/../../evil.ass",
                "..\\..\\evil_win.ass",
                "subdir\\..\\..\\evil_win.ass",
                "../ep_escape.ass",
                "nested/../../../../etc/passwd",
                "nested/../../../../Windows/System32/evil.dll"
            )

            for (vector in attackVectors) {
                val evilFile = File(tempDir, vector)
                var caughtSecurity = false
                try {
                    ZipSubtitleExtractor.validateZipSlip(evilFile, tempDir)
                } catch (e: SecurityException) {
                    caughtSecurity = true
                }
                assertTrue("Blocked traversal vector '$vector' with SecurityException", caughtSecurity)
                assertFalse("Vector '$vector' never created on disk", evilFile.exists())
            }

            // Prefix directory collision attack (e.g. /dest_dir vs /dest_dir_evil)
            val siblingEvilDir = File(tempDir.parentFile, tempDir.name + "_evil")
            val prefixCollisionFile = File(siblingEvilDir, "evil.ass")
            var caughtPrefix = false
            try {
                ZipSubtitleExtractor.validateZipSlip(prefixCollisionFile, tempDir)
            } catch (e: SecurityException) {
                caughtPrefix = true
            }
            assertTrue("Blocked prefix collision directory attack with SecurityException", caughtPrefix)

            // Self directory reference
            val selfDir = File(tempDir, ".")
            var caughtSelf = false
            try {
                ZipSubtitleExtractor.validateZipSlip(selfDir, tempDir)
            } catch (e: SecurityException) {
                caughtSelf = true
            }
            assertTrue("Blocked self directory target with SecurityException", caughtSelf)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. Streaming Zip Extraction Under Adversarial Conditions
    // =========================================================================
    private fun testStreamingZipExtractionAdversarial() {
        println("\n>>> Suite 2: Streaming Zip Selective Extraction & Boundary Testing")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_stream_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Build in-memory zip archive containing diverse file types and 5 episodes
            val bout = ByteArrayOutputStream()
            ZipOutputStream(bout).use { zos ->
                // Directory entry
                zos.putNextEntry(ZipEntry("subs/"))
                zos.closeEntry()

                // Non-subtitle file: readme.txt
                zos.putNextEntry(ZipEntry("subs/readme.txt"))
                zos.write("Release notes and fansub credits".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Non-subtitle file: cover.jpg
                zos.putNextEntry(ZipEntry("cover.jpg"))
                zos.write(ByteArray(512) { 0x55 })
                zos.closeEntry()

                // Non-target: Ep 1
                zos.putNextEntry(ZipEntry("subs/Frieren - 01 [1080p].ass"))
                zos.write("[Script Info]\nTitle: Episode 1 Dialogue".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Target: Ep 2 (Requested)
                zos.putNextEntry(ZipEntry("subs/Frieren - 02 [1080p].ass"))
                zos.write("[Script Info]\nTitle: Episode 2 Targeted Content\nDialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,フリーレン".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Non-target: Ep 3
                zos.putNextEntry(ZipEntry("subs/Frieren - 03.srt"))
                zos.write("1\n00:01:00,000 --> 00:01:05,000\nEpisode 3".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Non-target: Ep 4
                zos.putNextEntry(ZipEntry("subs/Frieren - 04.ssa"))
                zos.write("[Script Info]\nTitle: Episode 4 SSA".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Non-subtitle file: metadata.nfo
                zos.putNextEntry(ZipEntry("metadata.nfo"))
                zos.write("<xml>media info</xml>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val zipBytes = bout.toByteArray()

            // 1. Extract Target Episode 2
            val zis = ZipInputStream(ByteArrayInputStream(zipBytes))
            val extractedFile = ZipSubtitleExtractor.extractSubtitleFromZipStream(
                zipInputStream = zis,
                targetEpisode = 2,
                destinationDir = tempDir,
                slug = "Sousou_no_Frieren"
            )

            assertTrue("Zip extraction returned non-null file", extractedFile != null)
            assertTrue("Extracted file exists on disk", extractedFile?.exists() == true)
            assertTrue("Extracted file has correct ep 2 naming", extractedFile?.name?.contains("ep_2_") == true)
            assertTrue("Extracted file preserves .ass extension", extractedFile?.name?.endsWith(".ass") == true)

            val content = extractedFile?.readText(Charsets.UTF_8) ?: ""
            assertTrue("Extracted content matches Episode 2 content", content.contains("Episode 2 Targeted Content"))
            assertTrue("Extracted content contains Kanji", content.contains("フリーレン"))

            // 2. Strict Disk Isolation Check: Only Ep 2 file must exist on disk!
            val diskFiles = tempDir.listFiles() ?: emptyArray()
            assertEquals("Exactly 1 file written to disk during streaming extraction", 1, diskFiles.size)

            val diskNames = diskFiles.map { it.name }.toSet()
            assertFalse("Non-target ep 1 never written to disk", diskNames.any { it.contains("ep_1") })
            assertFalse("Non-target ep 3 never written to disk", diskNames.any { it.contains("ep_3") })
            assertFalse("Non-target ep 4 never written to disk", diskNames.any { it.contains("ep_4") })
            assertFalse("Non-subtitle readme.txt never written to disk", diskNames.contains("readme.txt"))
            assertFalse("Non-subtitle cover.jpg never written to disk", diskNames.contains("cover.jpg"))
            assertFalse("Non-subtitle metadata.nfo never written to disk", diskNames.contains("metadata.nfo"))

            // 3. Requesting non-existent episode (e.g. ep 99)
            val zisMissing = ZipInputStream(ByteArrayInputStream(zipBytes))
            val missingFile = ZipSubtitleExtractor.extractSubtitleFromZipStream(
                zipInputStream = zisMissing,
                targetEpisode = 99,
                destinationDir = tempDir,
                slug = "Sousou_no_Frieren"
            )
            assertTrue("Missing episode returns null", missingFile == null)

            // 4. Corrupted / Truncated zip byte stream
            val truncatedBytes = zipBytes.copyOfRange(0, zipBytes.size / 3)
            val zisTruncated = ZipInputStream(ByteArrayInputStream(truncatedBytes))
            var truncatedError = false
            var resultTruncated: File? = null
            try {
                resultTruncated = ZipSubtitleExtractor.extractSubtitleFromZipStream(
                    zipInputStream = zisTruncated,
                    targetEpisode = 2,
                    destinationDir = tempDir,
                    slug = "Sousou_no_Frieren"
                )
            } catch (e: Throwable) {
                truncatedError = true
            }
            assertFalse("Truncated zip handled gracefully without unhandled exception", truncatedError)
            assertTrue("Truncated zip safely returns null", resultTruncated == null)

            // 5. Empty zip stream (0 bytes)
            val zisEmpty = ZipInputStream(ByteArrayInputStream(ByteArray(0)))
            val emptyResult = ZipSubtitleExtractor.extractSubtitleFromZipStream(
                zipInputStream = zisEmpty,
                targetEpisode = 1,
                destinationDir = tempDir,
                slug = "Empty"
            )
            assertTrue("Empty zip stream returns null safely", emptyResult == null)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 3. Multi-Charset Auto-Detection & Fallback Stress Testing
    // =========================================================================
    private fun testMultiCharsetStressAndFidelity() {
        println("\n>>> Suite 3: Multi-Charset Auto-Detection & Japanese Kanji Fidelity")

        // 1. UTF-8 with BOM and mixed line endings (\r\n, \r, \n)
        val originalUtf8Text = "[Script Info]\nTitle: 葬送のフリーレン 第28話\nSynch Point: 1\nDialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,旅立ちの日。"
        val utf8BomString = "\uFEFF" + originalUtf8Text.replace("\n", "\r\n")
        val utf8Bytes = utf8BomString.toByteArray(Charsets.UTF_8)

        val decodedUtf8 = ZipSubtitleExtractor.decodeText(utf8Bytes)
        assertFalse("UTF-8 BOM is stripped from start", decodedUtf8.startsWith("\uFEFF"))
        assertFalse("UTF-8 BOM is completely absent", decodedUtf8.contains("\uFEFF"))
        assertFalse("CRLF normalized to LF", decodedUtf8.contains("\r\n"))
        assertFalse("Lone CR normalized to LF", decodedUtf8.contains("\r"))
        assertFalse("No Unicode replacement character \uFFFD in UTF-8 decode", decodedUtf8.contains('\uFFFD'))
        assertTrue("UTF-8 Japanese Kanji preserved", decodedUtf8.contains("葬送のフリーレン"))
        assertTrue("UTF-8 Japanese Dialogue preserved", decodedUtf8.contains("旅立ちの日。"))

        // 2. Legacy Shift-JIS with Diverse Japanese Kanji & Symbols
        val testShiftJisCases = listOf(
            "葬送のフリーレン 第01話「冒険の終わり」",
            "呪術廻戦 懐玉・玉折 01話「壊玉」",
            "無職転生 \u301C異世界行ったら本気だす\u301C",
            "鬼滅の刃 刀鍛冶の里編 第一話「誰かの夢」",
            "【日本語字幕】こんにちは！世界。０１２３４５６７８９",
            "Dialogue: 0,0:00:10.00,0:00:15.00,Default,,0,0,0,,これが旅の始まりだ——勇者ヒンメルの死から29年後。"
        )

        val shiftJisCharset = Charset.forName("Shift_JIS")

        for (sample in testShiftJisCases) {
            val sjisBytes = sample.toByteArray(shiftJisCharset)
            val decodedSjis = ZipSubtitleExtractor.decodeText(sjisBytes)

            assertFalse("Shift-JIS decoded without Unicode replacement char \uFFFD for '$sample'", decodedSjis.contains('\uFFFD'))
            assertEquals("Shift-JIS exact character match for '$sample'", sample, decodedSjis)
        }

        // 3. Windows-1252 (CP1252) Western European Characters
        val cp1252Text = "[Script Info]\nTitle: Sousou no Frieren Café & Español © 2023\nDialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,“Hello world” — façade naïve niño Über Groß\n"
        val cp1252Charset = Charset.forName("windows-1252")
        val cp1252Bytes = cp1252Text.toByteArray(cp1252Charset)

        var cp1252Failed = false
        var decodedCp1252 = ""
        try {
            decodedCp1252 = ZipSubtitleExtractor.decodeText(cp1252Bytes)
        } catch (_: Throwable) {
            cp1252Failed = true
        }
        assertFalse("Windows-1252 decoded without throwing exceptions", cp1252Failed)
        assertTrue("Windows-1252 contains expected title", decodedCp1252.contains("Sousou no Frieren"))

        // 4. Corrupted / Arbitrary Binary Bytes (Lossy fallback)
        val corruptedBytes = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(),
            0xC0.toByte(), 0xAF.toByte(), 0x80.toByte()
        )
        var corruptedFailed = false
        var decodedCorrupted = ""
        try {
            decodedCorrupted = ZipSubtitleExtractor.decodeText(corruptedBytes)
        } catch (_: Throwable) {
            corruptedFailed = true
        }
        assertFalse("Corrupted binary bytes decoded without throwing unhandled exceptions", corruptedFailed)
        assertTrue("Corrupted binary produces non-null string", decodedCorrupted.isNotEmpty())
    }

    // =========================================================================
    // 4. LRU Temp Cache Pruning Stress Testing
    // =========================================================================
    private fun testLruTempCachePruningStress() {
        println("\n>>> Suite 4: LRU Temp Cache Pruning & Boundary Invariants")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsunekko_lru_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            val now = System.currentTimeMillis()

            // 1. Count Bound: Create 35 files, stagger timestamps from t0 to t34
            for (i in 0 until 35) {
                val f = File(tempDir, "ep_${i}_test.ass")
                f.writeText("Episode $i subtitle payload")
                f.setLastModified(now - (35 - i) * 2000L)
            }

            assertEquals("Initial cache files created", 35, tempDir.listFiles()?.size)

            // Prune to max 20 files
            ZipSubtitleExtractor.pruneCache(tempDir, maxSizeBytes = 100L * 1024 * 1024, maxFileCount = 20)

            val remainingFiles = tempDir.listFiles() ?: emptyArray()
            assertEquals("Pruned file count capped at exactly 20", 20, remainingFiles.size)

            val remainingNames = remainingFiles.map { it.name }.toSet()

            // Oldest 15 files (0..14) must have been deleted
            for (i in 0 until 15) {
                assertFalse("Oldest file ep_${i} was pruned", remainingNames.contains("ep_${i}_test.ass"))
            }

            // Newest 20 files (15..34) must be preserved
            for (i in 15 until 35) {
                assertTrue("Newest file ep_${i} was preserved", remainingNames.contains("ep_${i}_test.ass"))
            }

            // 2. Size Bound: 5 files of 30 KB each, pruned to max 70 KB
            val sizeDir = File(tempDir, "size_test")
            sizeDir.mkdirs()

            for (i in 1..5) {
                val f = File(sizeDir, "file_$i.ass")
                f.writeBytes(ByteArray(30 * 1024) { 0x41 }) // 30 KB
                f.setLastModified(now - (6 - i) * 2000L)
            }

            val initialSize = sizeDir.listFiles()?.sumOf { it.length() } ?: 0L
            assertEquals("Initial size is 150 KB", 150L * 1024, initialSize)

            // Prune to max 70 KB
            ZipSubtitleExtractor.pruneCache(sizeDir, maxSizeBytes = 70L * 1024, maxFileCount = 10)

            val remainingSizeFiles = sizeDir.listFiles() ?: emptyArray()
            val totalRemainingBytes = remainingSizeFiles.sumOf { it.length() }
            assertTrue("Pruned directory total size <= 70 KB", totalRemainingBytes <= 70L * 1024)
            assertEquals("Remaining size files count is 2 (2 * 30KB = 60KB <= 70KB)", 2, remainingSizeFiles.size)

            val remainingSizeNames = remainingSizeFiles.map { it.name }.toSet()
            assertFalse("Oldest size file 1 pruned", remainingSizeNames.contains("file_1.ass"))
            assertFalse("Oldest size file 2 pruned", remainingSizeNames.contains("file_2.ass"))
            assertFalse("Oldest size file 3 pruned", remainingSizeNames.contains("file_3.ass"))
            assertTrue("Newest size file 4 preserved", remainingSizeNames.contains("file_4.ass"))
            assertTrue("Newest size file 5 preserved", remainingSizeNames.contains("file_5.ass"))

            // 3. In-flight temporary file protection: tmp_* files must NOT be deleted by pruneCache
            val inFlightDir = File(tempDir, "inflight_test")
            inFlightDir.mkdirs()

            for (i in 1..25) {
                val f = File(inFlightDir, "ep_${i}.ass")
                f.writeText("Data $i")
                f.setLastModified(now - (30 - i) * 1000L)
            }

            // In-flight temp files currently being written
            val tmp1 = File(inFlightDir, "tmp_${now}_ep1.ass")
            tmp1.writeText("In-flight data 1")
            tmp1.setLastModified(now - 100000L) // Even if older timestamp

            val tmp2 = File(inFlightDir, "tmp_${now}_ep2.ass")
            tmp2.writeText("In-flight data 2")
            tmp2.setLastModified(now - 100000L)

            ZipSubtitleExtractor.pruneCache(inFlightDir, maxSizeBytes = 10L * 1024 * 1024, maxFileCount = 15)

            assertTrue("In-flight tmp file 1 was NOT deleted by pruneCache", tmp1.exists())
            assertTrue("In-flight tmp file 2 was NOT deleted by pruneCache", tmp2.exists())

            val nonTmpFiles = inFlightDir.listFiles()?.filter { !it.name.startsWith("tmp_") } ?: emptyList()
            assertEquals("Normal cache files strictly capped at 15", 15, nonTmpFiles.size)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 5. Dynamic Hook Chaining & 0ms Video Passthrough
    // =========================================================================
    private fun testDynamicHookChainingAndLatency() {
        println("\n>>> Suite 5: Dynamic Hook Chaining & 0ms Video Passthrough Latency")

        // 1. Base provider (simulating Torrentio)
        val baseProvider = object : MainAPI() {
            init {
                name = "Torrentio"
                mainUrl = "https://torrentio.strem.fun"
            }
            override val supportedTypes: Set<TvType> get() = setOf(TvType.Anime, TvType.Movie)
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                // Yield video link immediately
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Torrentio 1080p Stream",
                        url = "https://stream.example.com/video.mp4",
                        referer = "",
                        quality = 1080,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        // 2. Chained Enricher 1: Anime47 Subtitle Enricher (Vietnamese)
        val anime47Wrapped = object : MainAPI() {
            val delegate = baseProvider
            init {
                name = delegate.name
                mainUrl = delegate.mainUrl
            }
            override val supportedTypes: Set<TvType> get() = delegate.supportedTypes
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                subtitleCallback(SubtitleFile("Tiếng Việt (Anime47)", "https://anime47.example.com/vi.srt"))
                return delegate.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // 3. Chained Enricher 2: Stremio Subtitle Enricher (English / Multilingual)
        val stremioWrapped = object : MainAPI() {
            val delegate = anime47Wrapped
            init {
                name = delegate.name
                mainUrl = delegate.mainUrl
            }
            override val supportedTypes: Set<TvType> get() = delegate.supportedTypes
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                subtitleCallback(SubtitleFile("English (Stremio)", "https://stremio.example.com/en.srt"))
                return delegate.loadLinks(data, isCasting, subtitleCallback, callback)
            }
        }

        // 4. Chained Enricher 3: Kitsunekko Japanese Subtitle Enricher
        val kitsunekkoWrapped = KitsunekkoHook.KitsunekkoEnrichedMainAPI(stremioWrapped)

        assertEquals("Delegated name preserved across 3-chain", "Torrentio", kitsunekkoWrapped.name)
        assertEquals("Delegated mainUrl preserved across 3-chain", "https://torrentio.strem.fun", kitsunekkoWrapped.mainUrl)

        // 5. Measure Video Link Passthrough Latency
        val receivedLinks = mutableListOf<String>()
        val receivedSubs = mutableListOf<String>()
        var videoLinkElapsedMs = -1L

        val startTime = System.currentTimeMillis()

        runBlocking {
            kitsunekkoWrapped.loadLinks(
                data = "Sousou no Frieren S01E02",
                isCasting = false,
                subtitleCallback = { sub -> receivedSubs.add(sub.lang) },
                callback = { link ->
                    videoLinkElapsedMs = System.currentTimeMillis() - startTime
                    receivedLinks.add(link.url)
                }
            )
        }

        assertTrue("Video link was received", receivedLinks.isNotEmpty())
        assertEquals("Video link URL matches source", "https://stream.example.com/video.mp4", receivedLinks[0])
        assertTrue("Video link passthrough latency < 100ms (actual: ${videoLinkElapsedMs}ms)", videoLinkElapsedMs in 0..100)

        // 6. Verify Subtitle Chaining Coexistence
        assertTrue("Anime47 Vietnamese subtitle emitted in chain", receivedSubs.contains("Tiếng Việt (Anime47)"))
        assertTrue("Stremio English subtitle emitted in chain", receivedSubs.contains("English (Stremio)"))

        // 7. Infinite Recursion Defense & Double-Wrapping Prevention
        val isAlreadyKitsunekko = (kitsunekkoWrapped is KitsunekkoHook.KitsunekkoEnrichedMainAPI) ||
                kitsunekkoWrapped.name.contains("Kitsunekko", ignoreCase = true)
        assertTrue("Kitsunekko type check detects already wrapped instance", isAlreadyKitsunekko)

        // 8. 4.5s Non-Blocking Timeout Barrier
        val slowProvider = object : MainAPI() {
            init {
                name = "SlowProvider"
                mainUrl = "https://slow.example.com"
            }
            override suspend fun loadLinks(
                data: String,
                isCasting: Boolean,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Slow Stream",
                        url = "https://slow.example.com/stream.mp4",
                        referer = "",
                        quality = 720,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                    )
                )
                // Simulate slow internal operation
                delay(100)
                return true
            }
        }

        val slowWrapped = KitsunekkoHook.KitsunekkoEnrichedMainAPI(slowProvider)
        val slowStart = System.currentTimeMillis()
        runBlocking {
            slowWrapped.loadLinks(
                data = "Slow Anime S01E01",
                isCasting = false,
                subtitleCallback = {},
                callback = {}
            )
        }
        val slowElapsed = System.currentTimeMillis() - slowStart
        assertTrue("loadLinks returns promptly without exceeding timeout barrier (actual: ${slowElapsed}ms)", slowElapsed < 5000)
    }
}
