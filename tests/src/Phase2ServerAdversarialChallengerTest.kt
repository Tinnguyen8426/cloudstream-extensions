package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger M6-2 Adversarial Empirical Stress Test Suite
 * Strictly verifies AITranslatorLocalServer:
 * 1. Port binding exclusively to 127.0.0.1 (not 0.0.0.0), port > 1024, clean shutdown.
 * 2. Raw TCP path traversal defenses (bypassing HttpURLConnection client-side normalization):
 *    - /sub/../../secret.txt
 *    - /sub/..%2F..%2Fsecret.txt
 *    - /sub/%2e%2e/%2e%2e/secret.txt
 *    - /sub/%2e%2e%2f%2e%2e%2fsecret.txt
 *    - /sub/..\..\secret.txt
 *    - /sub/..%5c..%5csecret.txt
 *    - /sub/..%5C..%2Fsecret.txt
 *    - /sub/C:/Windows/win.ini
 *    - /sub/, /sub/., /sub/..
 *    - Non-existent files -> 404
 *    - Valid files -> 200 with text/plain; charset=utf-8
 * 3. Concurrency load: 50+ concurrent raw socket connections over 16 worker threads.
 * 4. HTTP protocol compliance: HEAD requests, method rejection (POST/PUT/DELETE -> 405), malformed -> 400.
 * 5. Dynamic file registration and lifecycle shutdown.
 */
fun main() {
    println("================================================================================")
    println(">>> Starting Challenger M6-2: Loopback Server & Security Stress Harness...")
    println("================================================================================")

    var passed = 0
    var failed = 0

    fun assertTest(name: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $name")
            passed++
        } else {
            println("  [FAIL] $name -> $details")
            failed++
        }
    }

    val tempTestDir = File(System.getProperty("java.io.tmpdir", "."), "challenger_m6_2_test_${System.currentTimeMillis()}")
    if (tempTestDir.exists()) tempTestDir.deleteRecursively()
    tempTestDir.mkdirs()

    val secretFile = File(tempTestDir, "secret.txt").apply {
        writeText("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD", Charsets.UTF_8)
    }

    val fullDir = File(tempTestDir, "full").apply { mkdirs() }
    val validSrtContent = "1\n00:00:01,000 --> 00:00:04,000\nChallenger Empirical Verification\n\n"
    val validSrtFile = File(fullDir, "valid_test.srt").apply {
        writeText(validSrtContent, Charsets.UTF_8)
    }

    // Helper: Send raw HTTP request line over raw TCP socket without client-side normalization
    fun sendRawRequest(port: Int, requestLine: String, headers: List<String> = emptyList()): Pair<Int, String> {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 3000
            val out = socket.getOutputStream()
            val headerBlock = headers.joinToString("\r\n").let { if (it.isNotEmpty()) "$it\r\n" else "" }
            val raw = "$requestLine\r\nHost: 127.0.0.1\r\nConnection: close\r\n$headerBlock\r\n"
            out.write(raw.toByteArray(Charsets.UTF_8))
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: return Pair(0, "")
            val parts = statusLine.split(" ")
            val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0

            val sb = java.lang.StringBuilder()
            // Read rest of response headers and body
            var line: String?
            var isBody = false
            while (reader.readLine().also { line = it } != null) {
                if (!isBody) {
                    if (line!!.isEmpty()) {
                        isBody = true
                    }
                } else {
                    sb.append(line).append("\n")
                }
            }
            return Pair(statusCode, sb.toString())
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    try {
        SubtitleCacheManager.customBaseDir = tempTestDir
        AITranslatorLocalServer.customBaseDir = tempTestDir

        // =====================================================================
        // SECTION 1: Port Binding & Localhost 127.0.0.1 Exclusivity
        // =====================================================================
        val port = AITranslatorLocalServer.start(null)
        assertTest(
            "Ephemeral port allocated (> 1024)",
            port > 1024 && AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == port,
            "Port: $port, isRunning: ${AITranslatorLocalServer.isRunning}"
        )

        // Inspect internal ServerSocket binding via reflection
        val serverSocketField = AITranslatorLocalServer::class.java.getDeclaredField("serverSocket").apply {
            isAccessible = true
        }
        val internalSocket = serverSocketField.get(AITranslatorLocalServer) as? ServerSocket
        val boundAddress = internalSocket?.inetAddress?.hostAddress ?: ""
        val isLoopback = internalSocket?.inetAddress?.isLoopbackAddress ?: false

        assertTest(
            "ServerSocket binds exclusively to 127.0.0.1 (NOT 0.0.0.0)",
            boundAddress == "127.0.0.1" && isLoopback,
            "Bound address: '$boundAddress', isLoopback: $isLoopback"
        )

        // Non-loopback interface isolation check:
        // Query active IPv4 addresses on host network interfaces and attempt connection
        var nonLoopbackBlocked = true
        var nonLoopbackTested = 0
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback) continue
                val addresses = netIf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is InetAddress && !addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        nonLoopbackTested++
                        try {
                            val probeSocket = Socket()
                            probeSocket.connect(InetSocketAddress(addr, port), 500)
                            probeSocket.close()
                            // If connection succeeded on non-loopback IP, 0.0.0.0 leak!
                            nonLoopbackBlocked = false
                        } catch (_: ConnectException) {
                            // Expected: Connection refused
                        } catch (_: Throwable) {
                            // Timeout or unreachable is also acceptable
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        assertTest(
            "Non-loopback LAN addresses rejected connection (Tested $nonLoopbackTested interfaces)",
            nonLoopbackBlocked,
            "Non-loopback connection unexpectedly succeeded on external IP!"
        )

        // =====================================================================
        // SECTION 2: Aggressive Raw TCP Path Traversal Defenses
        // =====================================================================
        // Payload 2.1: Literal /sub/../../secret.txt
        val (code21, body21) = sendRawRequest(port, "GET /sub/../../secret.txt HTTP/1.1")
        assertTest(
            "Raw path traversal /sub/../../secret.txt rejected with 403 Forbidden",
            code21 == 403 && !body21.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code21, Body: $body21"
        )

        // Payload 2.2: Standard URL-encoded /sub/..%2F..%2Fsecret.txt
        val (code22, body22) = sendRawRequest(port, "GET /sub/..%2F..%2Fsecret.txt HTTP/1.1")
        assertTest(
            "Encoded slash traversal /sub/..%2F..%2Fsecret.txt rejected with 403 Forbidden",
            code22 == 403 && !body22.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code22, Body: $body22"
        )

        // Payload 2.3: Fully hex-encoded dot-dot /sub/%2e%2e/%2e%2e/secret.txt
        val (code23, body23) = sendRawRequest(port, "GET /sub/%2e%2e/%2e%2e/secret.txt HTTP/1.1")
        assertTest(
            "Fully hex-encoded dot-dot /sub/%2e%2e/%2e%2e/secret.txt rejected with 403 Forbidden",
            code23 == 403 && !body23.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code23, Body: $body23"
        )

        // Payload 2.4: Lowercase fully hex-encoded /sub/%2e%2e%2f%2e%2e%2fsecret.txt
        val (code24, body24) = sendRawRequest(port, "GET /sub/%2e%2e%2f%2e%2e%2fsecret.txt HTTP/1.1")
        assertTest(
            "Hex encoded /sub/%2e%2e%2f%2e%2e%2fsecret.txt rejected with 403 Forbidden",
            code24 == 403 && !body24.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code24, Body: $body24"
        )

        // Payload 2.5: Windows backslash traversal /sub/..\..\secret.txt
        val (code25, body25) = sendRawRequest(port, "GET /sub/..\\..\\secret.txt HTTP/1.1")
        assertTest(
            "Windows backslash traversal /sub/..\\..\\secret.txt rejected with 403 Forbidden",
            code25 == 403 && !body25.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code25, Body: $body25"
        )

        // Payload 2.6: URL-encoded Windows backslash /sub/..%5c..%5csecret.txt
        val (code26, body26) = sendRawRequest(port, "GET /sub/..%5c..%5csecret.txt HTTP/1.1")
        assertTest(
            "Encoded backslash /sub/..%5c..%5csecret.txt rejected with 403 Forbidden",
            code26 == 403 && !body26.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code26, Body: $body26"
        )

        // Payload 2.7: Mixed slash/backslash /sub/..%5C..%2Fsecret.txt
        val (code27, body27) = sendRawRequest(port, "GET /sub/..%5C..%2Fsecret.txt HTTP/1.1")
        assertTest(
            "Mixed slash /sub/..%5C..%2Fsecret.txt rejected with 403 Forbidden",
            code27 == 403 && !body27.contains("CONFIDENTIAL_CHALLENGER_SECRET_PAYLOAD"),
            "Status: $code27, Body: $body27"
        )

        // Payload 2.8: Absolute Windows drive escape attempt /sub/C:/Windows/win.ini
        val (code28, body28) = sendRawRequest(port, "GET /sub/C:/Windows/win.ini HTTP/1.1")
        assertTest(
            "Absolute Windows path /sub/C:/Windows/win.ini rejected with 403 Forbidden",
            code28 == 403 && body28.isEmpty(),
            "Status: $code28, Body: $body28"
        )

        // Payload 2.9: Root dir escape /sub/
        val (code29, _) = sendRawRequest(port, "GET /sub/ HTTP/1.1")
        assertTest(
            "Directory root /sub/ rejected with 403 Forbidden",
            code29 == 403,
            "Status: $code29"
        )

        // Payload 2.10: Single dot /sub/.
        val (code210, _) = sendRawRequest(port, "GET /sub/. HTTP/1.1")
        assertTest(
            "Single dot /sub/. rejected with 403 Forbidden",
            code210 == 403,
            "Status: $code210"
        )

        // Payload 2.11: Double dot /sub/..
        val (code211, _) = sendRawRequest(port, "GET /sub/.. HTTP/1.1")
        assertTest(
            "Double dot /sub/.. rejected with 403 Forbidden",
            code211 == 403,
            "Status: $code211"
        )

        // Payload 2.12: Inside directory probe (subdirectory inside full/)
        val subDirInside = File(fullDir, "nested_folder").apply { mkdirs() }
        val (code212, _) = sendRawRequest(port, "GET /sub/nested_folder HTTP/1.1")
        assertTest(
            "Subdirectory probe /sub/nested_folder returns 404 (not a file)",
            code212 == 404,
            "Status: $code212"
        )

        // Payload 2.13: Non-existent file
        val (code213, _) = sendRawRequest(port, "GET /sub/nonexistent_sub_404.srt HTTP/1.1")
        assertTest(
            "Non-existent file /sub/nonexistent_sub_404.srt returns 404 File Not Found",
            code213 == 404,
            "Status: $code213"
        )

        // Payload 2.14: Out-of-scope route /secret.txt
        val (code214, _) = sendRawRequest(port, "GET /secret.txt HTTP/1.1")
        assertTest(
            "Out-of-scope route /secret.txt returns 404 Not Found",
            code214 == 404,
            "Status: $code214"
        )

        // Payload 2.15: Query parameter stripping with valid file
        val (code215, body215) = sendRawRequest(port, "GET /sub/valid_test.srt?foo=bar&baz=1 HTTP/1.1")
        assertTest(
            "Valid file with query parameters /sub/valid_test.srt?foo=bar returns 200 OK",
            code215 == 200 && body215.trim() == validSrtContent.trim(),
            "Status: $code215"
        )

        // Payload 2.16: Query parameter containing traversal attempt
        val (code216, body216) = sendRawRequest(port, "GET /sub/valid_test.srt?../../secret.txt HTTP/1.1")
        assertTest(
            "Query parameter with traversal string safely stripped, serves file (200 OK)",
            code216 == 200 && body216.trim() == validSrtContent.trim(),
            "Status: $code216"
        )

        // =====================================================================
        // SECTION 3: HTTP Protocol Compliance & Header Verification
        // =====================================================================
        val (code31, body31) = sendRawRequest(port, "GET /sub/valid_test.srt HTTP/1.1")
        assertTest(
            "Valid file returns HTTP 200 OK with correct body",
            code31 == 200 && body31.trim() == validSrtContent.trim(),
            "Status: $code31, Body: $body31"
        )

        // Detailed header verification
        val fullHttpConn = Socket("127.0.0.1", port)
        var hasContentType = false
        var hasContentLength = false
        var hasAcceptRanges = false
        var hasCors = false
        try {
            fullHttpConn.soTimeout = 3000
            val out = fullHttpConn.getOutputStream()
            out.write("GET /sub/valid_test.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(fullHttpConn.getInputStream(), Charsets.UTF_8))
            val status = reader.readLine()
            var hLine: String?
            while (reader.readLine().also { hLine = it } != null) {
                if (hLine!!.isEmpty()) break
                val lower = hLine!!.lowercase()
                if (lower.startsWith("content-type:") && lower.contains("text/plain") && lower.contains("utf-8")) {
                    hasContentType = true
                }
                if (lower.startsWith("content-length:") && lower.contains("${validSrtFile.length()}")) {
                    hasContentLength = true
                }
                if (lower.startsWith("accept-ranges:") && lower.contains("bytes")) {
                    hasAcceptRanges = true
                }
                if (lower.startsWith("access-control-allow-origin:") && lower.contains("*")) {
                    hasCors = true
                }
            }
        } finally {
            fullHttpConn.close()
        }

        assertTest(
            "Streaming response headers: Content-Type (text/plain; charset=utf-8), Content-Length, Accept-Ranges, CORS",
            hasContentType && hasContentLength && hasAcceptRanges && hasCors,
            "Type=$hasContentType, Len=$hasContentLength, Ranges=$hasAcceptRanges, Cors=$hasCors"
        )

        // HEAD method compliance
        val (codeHead, bodyHead) = sendRawRequest(port, "HEAD /sub/valid_test.srt HTTP/1.1")
        assertTest(
            "HEAD request returns HTTP 200 OK with empty body",
            codeHead == 200 && bodyHead.trim().isEmpty(),
            "Status: $codeHead, Body len: ${bodyHead.length}"
        )

        // Method Not Allowed rejection: POST, PUT, DELETE
        val (codePost, _) = sendRawRequest(port, "POST /sub/valid_test.srt HTTP/1.1")
        val (codePut, _) = sendRawRequest(port, "PUT /sub/valid_test.srt HTTP/1.1")
        val (codeDelete, _) = sendRawRequest(port, "DELETE /sub/valid_test.srt HTTP/1.1")
        assertTest(
            "Non-GET/HEAD methods rejected with HTTP 405 Method Not Allowed",
            codePost == 405 && codePut == 405 && codeDelete == 405,
            "POST: $codePost, PUT: $codePut, DELETE: $codeDelete"
        )

        // Malformed request
        val (codeBad, _) = sendRawRequest(port, "MALFORMED_GARBAGE_REQUEST")
        assertTest(
            "Malformed request line rejected with HTTP 400 Bad Request",
            codeBad == 400,
            "Status: $codeBad"
        )

        // Dynamic registration: registerSubtitle()
        val customDir = File(tempTestDir, "custom_registered_dir").apply { mkdirs() }
        val customSubFile = File(customDir, "dynamically_registered.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:02,000\nDynamic Registration Test\n\n", Charsets.UTF_8)
        }
        val regUrl = AITranslatorLocalServer.registerSubtitle(customSubFile)
        val expectedRegUrl = "http://127.0.0.1:$port/sub/dynamically_registered.srt"
        assertTest(
            "registerSubtitle() returns expected URL and registers in memory map",
            regUrl == expectedRegUrl,
            "URL: $regUrl"
        )

        val (codeReg, bodyReg) = sendRawRequest(port, "GET /sub/dynamically_registered.srt HTTP/1.1")
        assertTest(
            "Dynamically registered subtitle served successfully over HTTP 200 OK",
            codeReg == 200 && bodyReg.contains("Dynamic Registration Test"),
            "Status: $codeReg, Body: $bodyReg"
        )

        // =====================================================================
        // SECTION 4: High Concurrent Load Stress (60 raw socket clients across 16 threads)
        // =====================================================================
        val concurrentClients = 60
        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(concurrentClients)
        val successCounter = AtomicInteger(0)
        val errorCounter = AtomicInteger(0)

        for (i in 0 until concurrentClients) {
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    // Mix requests: majority GET valid, some HEAD, some 404, some 403 attack
                    when (i % 4) {
                        0 -> {
                            val (c, b) = sendRawRequest(port, "GET /sub/valid_test.srt HTTP/1.1")
                            if (c == 200 && b.trim() == validSrtContent.trim()) successCounter.incrementAndGet()
                            else errorCounter.incrementAndGet()
                        }
                        1 -> {
                            val (c, b) = sendRawRequest(port, "HEAD /sub/valid_test.srt HTTP/1.1")
                            if (c == 200 && b.trim().isEmpty()) successCounter.incrementAndGet()
                            else errorCounter.incrementAndGet()
                        }
                        2 -> {
                            val (c, _) = sendRawRequest(port, "GET /sub/nonexistent_concurrent_$i.srt HTTP/1.1")
                            if (c == 404) successCounter.incrementAndGet()
                            else errorCounter.incrementAndGet()
                        }
                        3 -> {
                            val (c, _) = sendRawRequest(port, "GET /sub/../../secret.txt HTTP/1.1")
                            if (c == 403) successCounter.incrementAndGet()
                            else errorCounter.incrementAndGet()
                        }
                    }
                } catch (e: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Trigger simultaneous storm
        startLatch.countDown()
        val completedInTime = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTest(
            "60 concurrent raw socket connections completed within 10 seconds",
            completedInTime && doneLatch.count == 0L,
            "CompletedInTime: $completedInTime, Remaining: ${doneLatch.count}"
        )

        assertTest(
            "100% concurrent request integrity: 60/60 correct status codes, 0 errors",
            successCounter.get() == concurrentClients && errorCounter.get() == 0,
            "Success: ${successCounter.get()}/$concurrentClients, Errors: ${errorCounter.get()}"
        )

        // =====================================================================
        // SECTION 5: Clean Teardown & Lifecycle Verification
        // =====================================================================
        AITranslatorLocalServer.stop()

        assertTest(
            "Server marked inactive and port reset to 0 after stop()",
            !AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == 0,
            "isRunning: ${AITranslatorLocalServer.isRunning}, port: ${AITranslatorLocalServer.port}"
        )

        // Verify socket is actually closed: connection to old port must fail immediately
        var connectionRefused = false
        try {
            val deadSocket = Socket()
            deadSocket.connect(InetSocketAddress("127.0.0.1", port), 500)
            deadSocket.close()
        } catch (_: ConnectException) {
            connectionRefused = true
        } catch (_: Throwable) {
            connectionRefused = true
        }

        assertTest(
            "Socket closed and previous port immediately refuses new connections",
            connectionRefused,
            "Socket unexpectedly accepted connection after stop()!"
        )

        // Verify clean restart on new ephemeral port
        val newPort = AITranslatorLocalServer.start(null)
        assertTest(
            "Server cleanly restarts on fresh ephemeral port (> 1024)",
            newPort > 1024 && AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == newPort,
            "New port: $newPort, isRunning: ${AITranslatorLocalServer.isRunning}"
        )
        AITranslatorLocalServer.stop()

    } finally {
        AITranslatorLocalServer.stop()
        if (tempTestDir.exists()) {
            tempTestDir.deleteRecursively()
        }
    }

    println("================================================================================")
    println("CHALLENGER STRESS RESULTS: $passed PASSED, $failed FAILED")
    println("================================================================================")
    if (failed > 0) {
        System.exit(1)
    }
}
