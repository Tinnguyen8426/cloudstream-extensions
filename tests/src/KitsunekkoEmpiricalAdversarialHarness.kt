package com.cloudstream.test.e2e

import com.kitsunekko.enricher.KitsunekkoMatcher
import com.kitsunekko.enricher.ZipSubtitleExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Empirical Adversarial Harness for Kitsunekko Subtitle Enricher (R1 Verification).
 * Agent: m4_challenger_2
 *
 * Verifies:
 * 1. Memory bounds during zip streaming (8KB buffer, no full-zip buffering)
 * 2. Corrupt archive resilience (zero-byte, truncated, corrupted headers auto-deleted from disk)
 * 3. Fallback charset decoding (Shift-JIS, UTF-8 +/- BOM, Windows-1252, noisy/corrupt binary)
 * 4. ZipSlip security (canonical traversal blocking, no directory escape)
 * 5. Unicode & ReDoS stress (massive strings, astral glyphs, RTL, zero-width)
 */
object KitsunekkoEmpiricalAdversarialHarness {

    private var passed = 0
    private var failed = 0
    private val failureMessages = mutableListOf<String>()

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            val msg = "  [FAIL] $name: $detail"
            System.err.println(msg)
            failureMessages.add(msg)
            failed++
        }
    }

    private fun assertEquals(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "Expected: <$expected> but was: <$actual>")
    }

    private fun assertTrue(name: String, condition: Boolean, msg: String = "") {
        check(name, condition, if (msg.isNotEmpty()) msg else "Expected true but was false")
    }

    private fun assertFalse(name: String, condition: Boolean, msg: String = "") {
        check(name, !condition, if (msg.isNotEmpty()) msg else "Expected false but was true")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println(" KITSUNEKKO EMPIRICAL ADVERSARIAL STRESS & VERIFICATION HARNESS")
        println(" Agent: m4_challenger_2 (Empirical Challenger)")
        println("================================================================================")

        testMemoryBoundsAndStreamingExtraction()
        testCorruptArchiveResilienceAndAutoDeletion()
        testFallbackCharsetDecodingStress()
        testZipSlipPathTraversalSecurity()
        testUnicodeAndReDoSStress()

        println("================================================================================")
        println(" EMPIRICAL VERIFICATION SUMMARY: $passed PASSED, $failed FAILED")
        println("================================================================================")

        if (failed > 0) {
            System.err.println("\nFailed assertions:")
            failureMessages.forEach { System.err.println(it) }
            System.exit(1)
        } else {
            println("\nALL EMPIRICAL CHALLENGER ASSERTIONS CONFIRMED SUCCESSFULLY!")
            System.exit(0)
        }
    }

    // =========================================================================
    // 1. Memory Bounds During Zip Streaming
    // =========================================================================
    private fun testMemoryBoundsAndStreamingExtraction() {
        println("\n>>> Focus 1: Memory Bounds During Zip Streaming (8KB Buffer & Selective Streaming)")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_mem_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Build a zip containing:
            // - 1 target subtitle: "Frieren - 02.ass" (10 KB)
            // - 5 non-target subtitles: "Frieren - 01.ass", "Frieren - 03.ass", etc.
            // - 1 high-entropy incompressible payload: "video_stream.bin" (5 MB of pseudo-random bytes)
            val zipFile = File(tempDir, "large_archive.zip")
            val rand = Random(999)
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // Non-target 1
                zos.putNextEntry(ZipEntry("subs/Frieren - 01.ass"))
                zos.write("Ep 1 Subtitle".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Target entry
                zos.putNextEntry(ZipEntry("subs/Frieren - 02.ass"))
                zos.write("[Script Info]\nTitle: Ep 2\nDialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,フリーレン".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Non-target 3
                zos.putNextEntry(ZipEntry("subs/Frieren - 03.ass"))
                zos.write("Ep 3 Subtitle".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // High entropy payload (5 MB) that does not deflate down to zeroes
                zos.putNextEntry(ZipEntry("media/large_video_stream.bin"))
                val dummyChunk = ByteArray(64 * 1024)
                for (i in 0 until 80) { // 80 * 64KB = 5.12 MB
                    rand.nextBytes(dummyChunk)
                    zos.write(dummyChunk)
                }
                zos.closeEntry()
            }

            assertTrue("Large zip file created on disk (> 5MB, actual: ${zipFile.length() / 1024 / 1024}MB)",
                zipFile.length() > 5 * 1024 * 1024)

            // Force GC to get baseline
            System.gc()
            Thread.sleep(100)
            val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val extracted = ZipSubtitleExtractor.extractFromZipFile(
                zipFile = zipFile,
                targetEpisode = 2,
                destinationDir = tempDir,
                slug = "Frieren"
            )

            System.gc()
            Thread.sleep(100)
            val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val heapGrowth = Math.max(0L, memAfter - memBefore)

            assertTrue("Target episode 2 extracted successfully", extracted != null && extracted.exists())
            assertTrue("Extracted subtitle contains targeted Kanji", extracted?.readText()?.contains("フリーレン") == true)

            // Verify that non-subtitle 5MB file was NEVER decompressed or written to disk
            val extractedFiles = tempDir.listFiles()?.filter { it != zipFile } ?: emptyList()
            assertEquals("Only target episode subtitle written to disk", 1, extractedFiles.size)
            assertEquals("Extracted file is ep_2", "ep_2_Frieren.ass", extractedFiles[0].name)

            // Verify heap delta did not explode by 5MB+ (streaming selective unpack)
            println("  [INFO] Heap memory delta during extraction: ${heapGrowth / 1024} KB")
            assertTrue("Heap growth < 3MB during 5MB+ zip extraction (no full-archive RAM buffer)", heapGrowth < 3 * 1024 * 1024)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. Corrupt Archive Resilience & Cache Auto-Deletion
    // =========================================================================
    private fun testCorruptArchiveResilienceAndAutoDeletion() {
        println("\n>>> Focus 2: Corrupt Archive Resilience & Auto-Deletion From Disk")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_corrupt_test_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            // Test 2a: Zero-byte archive
            val zeroFile = File(tempDir, "zero_archive.zip")
            zeroFile.writeBytes(ByteArray(0))
            assertTrue("Zero-byte file created", zeroFile.exists() && zeroFile.length() == 0L)

            val zeroResult = ZipSubtitleExtractor.extractFromZipFile(zeroFile, 1, tempDir, "zero")
            assertEquals("Zero-byte zip returns null", null, zeroResult)
            assertFalse("Zero-byte zip was automatically deleted from disk", zeroFile.exists())

            // Test 2b: Truncated zip (only 8 bytes header)
            val truncatedFile = File(tempDir, "truncated_archive.zip")
            truncatedFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00))
            assertTrue("Truncated file created", truncatedFile.exists())

            val truncResult = ZipSubtitleExtractor.extractFromZipFile(truncatedFile, 1, tempDir, "trunc")
            assertEquals("Truncated zip returns null safely", null, truncResult)
            assertFalse("Truncated zip was automatically deleted from disk", truncatedFile.exists())

            // Test 2c: Garbage / Corrupt random bytes
            val corruptFile = File(tempDir, "corrupt_garbage.zip")
            val randBytes = ByteArray(4096)
            Random(42).nextBytes(randBytes)
            corruptFile.writeBytes(randBytes)
            assertTrue("Corrupt garbage file created", corruptFile.exists())

            val corruptResult = ZipSubtitleExtractor.extractFromZipFile(corruptFile, 1, tempDir, "garbage")
            assertEquals("Corrupt garbage zip returns null safely", null, corruptResult)
            assertFalse("Corrupt garbage zip was automatically deleted from disk", corruptFile.exists())

            // Test 2d: Missing / non-existent file
            val nonExistentFile = File(tempDir, "does_not_exist.zip")
            val missingResult = ZipSubtitleExtractor.extractFromZipFile(nonExistentFile, 1, tempDir, "missing")
            assertEquals("Non-existent zip returns null safely", null, missingResult)

            // Test 2e: Cache remains unpoisoned - a subsequent valid zip extracts cleanly
            val validFile = File(tempDir, "clean_valid.zip")
            ZipOutputStream(FileOutputStream(validFile)).use { zos ->
                zos.putNextEntry(ZipEntry("ep_1.ass"))
                zos.write("Dialogue: valid subtitle content".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val validResult = ZipSubtitleExtractor.extractFromZipFile(validFile, 1, tempDir, "clean")
            assertTrue("Subsequent valid zip extracted after corruption tests", validResult != null && validResult.exists())
            assertTrue("Clean zip preserved on disk", validFile.exists())

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 3. Fallback Charset Decoding Stress Testing
    // =========================================================================
    private fun testFallbackCharsetDecodingStress() {
        println("\n>>> Focus 3: Multi-Charset Decoding & Corrupt Input Resilience")

        // 3a. Shift-JIS with diverse Japanese Kanji & Katakana
        val sjisStrings = listOf(
            "葬送のフリーレン 第28話「また会ったときに恥ずかしいからね」",
            "呪術廻戦 懐玉・玉折 01話「壊玉」",
            "【日本語字幕】こんにちは！世界。０１２３４５６７８９",
            "Dialogue: 0,0:01:00.00,0:01:05.00,Default,,0,0,0,,勇者ヒンメルの死から29年後。"
        )
        val sjisCharset = Charset.forName("Shift_JIS")
        for (s in sjisStrings) {
            val bytes = s.toByteArray(sjisCharset)
            val decoded = ZipSubtitleExtractor.decodeText(bytes)
            assertFalse("Shift-JIS decoded without Unicode replacement char \uFFFD for '$s'", decoded.contains('\uFFFD'))
            assertEquals("Shift-JIS decoded exactly matching original", s, decoded)
        }

        // 3b. UTF-8 with BOM (\uFEFF)
        val utf8Text = "Title: 葬送のフリーレン\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,こんにちは"
        val utf8WithBom = "\uFEFF" + utf8Text.replace("\n", "\r\n")
        val decodedBom = ZipSubtitleExtractor.decodeText(utf8WithBom.toByteArray(Charsets.UTF_8))
        assertFalse("UTF-8 BOM is stripped from start", decodedBom.startsWith("\uFEFF"))
        assertFalse("UTF-8 BOM completely stripped", decodedBom.contains("\uFEFF"))
        assertFalse("CRLF normalized to LF", decodedBom.contains("\r\n"))
        assertTrue("Japanese Kanji preserved in UTF-8 BOM", decodedBom.contains("葬送のフリーレン"))

        // 3c. Windows-1252 (Western European)
        val cp1252Text = "Title: Sousou no Frieren Café & Español © 2024\nDialogue: naïve niño Über Groß\n"
        val cp1252Charset = Charset.forName("windows-1252")
        val decodedCp1252 = ZipSubtitleExtractor.decodeText(cp1252Text.toByteArray(cp1252Charset))
        assertTrue("Windows-1252 contains expected title", decodedCp1252.contains("Sousou no Frieren Café & Español © 2024"))

        // 3d. Corrupt binary byte noise (50,000 bytes of arbitrary high-entropy bytes)
        val noiseBytes = ByteArray(50000)
        Random(1337).nextBytes(noiseBytes)
        // Inject non-decodable byte sequences
        noiseBytes[0] = 0xFF.toByte()
        noiseBytes[1] = 0xFE.toByte()
        noiseBytes[2] = 0xC0.toByte()
        noiseBytes[3] = 0xAF.toByte()

        var noiseThrew = false
        var noiseDecoded: String? = null
        try {
            noiseDecoded = ZipSubtitleExtractor.decodeText(noiseBytes)
        } catch (e: Throwable) {
            noiseThrew = true
        }
        assertFalse("Arbitrary 50,000-byte noise decoded without throwing unhandled exception", noiseThrew)
        assertTrue("Noise decoding produced non-null string", noiseDecoded != null && noiseDecoded.isNotEmpty())
    }

    // =========================================================================
    // 4. ZipSlip Path Traversal Security
    // =========================================================================
    private fun testZipSlipPathTraversalSecurity() {
        println("\n>>> Focus 4: ZipSlip Path Traversal Security Defense")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kitsu_slip_verify_" + System.currentTimeMillis())
        tempDir.mkdirs()

        try {
            val maliciousVectors = listOf(
                "../../evil.ass",
                "../../../etc/passwd",
                "..\\..\\evil_win.ass",
                "nested/../../../../Windows/System32/evil.dll",
                "subdir/../../../evil.ass",
                "../ep_escape.ass"
            )

            for (vector in maliciousVectors) {
                val targetFile = File(tempDir, vector)
                var caughtSecurity = false
                try {
                    ZipSubtitleExtractor.validateZipSlip(targetFile, tempDir)
                } catch (e: SecurityException) {
                    caughtSecurity = true
                }
                assertTrue("Blocked malicious traversal path '$vector' with SecurityException", caughtSecurity)
            }

            // Prefix collision directory attack (e.g., /tmp/cache vs /tmp/cache_evil)
            val siblingEvilDir = File(tempDir.parentFile, tempDir.name + "_evil")
            val collisionFile = File(siblingEvilDir, "evil.ass")
            var caughtPrefix = false
            try {
                ZipSubtitleExtractor.validateZipSlip(collisionFile, tempDir)
            } catch (e: SecurityException) {
                caughtPrefix = true
            }
            assertTrue("Blocked prefix-collision directory attack with SecurityException", caughtPrefix)

            // Streaming zip containing a malicious ZipSlip entry: verify no directory escape occurs
            val bout = ByteArrayOutputStream()
            ZipOutputStream(bout).use { zos ->
                zos.putNextEntry(ZipEntry("../../escape_ep1.ass"))
                zos.write("Malicious payload".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val maliciousZipBytes = bout.toByteArray()

            val extractedResult = java.util.zip.ZipInputStream(ByteArrayInputStream(maliciousZipBytes)).use { zis ->
                ZipSubtitleExtractor.extractSubtitleFromZipStream(
                    zipInputStream = zis,
                    targetEpisode = 1,
                    destinationDir = tempDir,
                    slug = "Malicious"
                )
            }

            // Verify that no file was created outside tempDir
            val escapedFile1 = File(tempDir.parentFile, "escape_ep1.ass")
            val escapedFile2 = File(tempDir.parentFile.parentFile, "escape_ep1.ass")
            assertFalse("Malicious entry never escaped to parent directory", escapedFile1.exists())
            assertFalse("Malicious entry never escaped to grandparent directory", escapedFile2.exists())
            assertTrue("Extracted file remained strictly inside destinationDir sandbox",
                extractedResult == null || extractedResult.canonicalPath.startsWith(tempDir.canonicalPath + File.separator))

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 5. Unicode & ReDoS Stress Testing
    // =========================================================================
    private fun testUnicodeAndReDoSStress() {
        println("\n>>> Focus 5: Unicode & ReDoS Stress Testing (50,000 Chars & Complex Glyphs)")

        // 5a. ReDoS Stress: 50,000 characters with repeated tokens
        val massiveInput = "Sousou.no.Frieren.S01E01." + "1080p.WEB-DL.x264.".repeat(2500) + "mkv"
        assertTrue("Massive input length > 40,000 chars (actual: ${massiveInput.length})", massiveInput.length > 40000)

        val startRedos = System.currentTimeMillis()
        val parsedMedia = KitsunekkoMatcher.parseMediaInfo(massiveInput)
        val redosElapsed = System.currentTimeMillis() - startRedos

        assertTrue("parseMediaInfo on massive string completed in < 150ms (actual: ${redosElapsed}ms)", redosElapsed < 150)
        assertEquals("Clean title parsed correctly without catastrophic backtracking", "Sousou no Frieren", parsedMedia.cleanTitle)
        assertEquals("Episode number resolved correctly", 1, parsedMedia.episodeNumber)
        assertEquals("Season number resolved correctly", 1, parsedMedia.seasonNumber)

        // 5b. Massive Japanese text ReDoS
        val massiveJapanese = "葬送のフリーレン 第01話 冒険の終わり " + "2023 1080p ".repeat(2000)
        val startJp = System.currentTimeMillis()
        val parsedJp = KitsunekkoMatcher.parseMediaInfo(massiveJapanese)
        val jpElapsed = System.currentTimeMillis() - startJp
        assertTrue("Japanese massive parsing completed in < 150ms (actual: ${jpElapsed}ms)", jpElapsed < 150)
        assertTrue("Japanese clean title is non-blank", parsedJp.cleanTitle.isNotBlank())
        assertEquals("Japanese episode number resolved", 1, parsedJp.episodeNumber)

        // 5c. Arbitrary Unicode glyphs, astral emojis, RTL, zero-width
        val unicodeInputs = listOf(
            "🔥 Frieren: Beyond Journey's End ⚔️ S01E05 [1080p] 🚀 ãéíóú",
            "مرحبا بالعالم - الحلقة 05",
            "שלום עולם עונה 1 פרク 2",
            "Z̸̡a̴̧l̵̢g̸̢o̸̧ 01",
            "Sousou\u200Bno\u200CFrieren\u200D 01",
            "Frieren\u0000Season 1\u0007"
        )

        for (u in unicodeInputs) {
            var threw = false
            try {
                val p = KitsunekkoMatcher.parseMediaInfo(u)
                if (p.cleanTitle.isEmpty()) {
                    // empty string check
                }
            } catch (e: Throwable) {
                threw = true
            }
            assertFalse("Unicode input '$u' parsed without crash", threw)
        }
    }
}
