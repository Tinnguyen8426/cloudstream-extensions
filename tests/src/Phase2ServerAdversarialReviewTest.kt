package tests.adversarial

import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Review and Stress-Test Harness for AITranslatorLocalServer.
 * Validates Security, RFC/ExoPlayer Conformance, Concurrency, Socket Timeouts, and Teardown.
 */
fun main() {
    println("================================================================================")
    println(">>> Starting AITranslatorLocalServer Adversarial Review Suite...")
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

    val tempTestDir = File(System.getProperty("java.io.tmpdir", "."), "server_review_adv_${System.currentTimeMillis()}")
    if (tempTestDir.exists()) tempTestDir.deleteRecursively()
    tempTestDir.mkdirs()

    val fullDir = File(tempTestDir, "full").apply { mkdirs() }
    val secretFile = File(tempTestDir, "secret_outside_cache.txt").apply { writeText("CLASSIFIED_TOKEN_98765") }
    val legitFile = File(fullDir, "valid_sub.srt").apply {
        writeText("1\n00:00:01,000 --> 00:00:04,000\nValid Subtitle Content\n\n", Charsets.UTF_8)
    }
    val spacedFile = File(fullDir, "My Spaced Subtitle.srt").apply {
        writeText("1\n00:00:01,000 --> 00:00:02,000\nSpaced Subtitle\n\n", Charsets.UTF_8)
    }

    // Helper to send raw HTTP bytes over TCP socket
    fun sendRawHttp(host: String, port: Int, rawPayload: String): Pair<Int, String> {
        val socket = Socket(host, port)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        out.write(rawPayload.toByteArray(Charsets.UTF_8))
        out.flush()

        val statusLine = reader.readLine() ?: return Pair(0, "")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        val bodyBuilder = StringBuilder()
        var line: String? = reader.readLine()
        var isBody = false
        while (line != null) {
            if (!isBody) {
                if (line.isEmpty()) {
                    isBody = true
                }
            } else {
                bodyBuilder.append(line).append("\n")
            }
            line = reader.readLine()
        }
        socket.close()
        return Pair(code, bodyBuilder.toString())
    }

    try {
        AITranslatorLocalServer.customBaseDir = tempTestDir
        SubtitleCacheManager.customBaseDir = tempTestDir

        // ---------------------------------------------------------------------
        // 1. Ephemeral Port & Loopback Binding
        // ---------------------------------------------------------------------
        val port = AITranslatorLocalServer.start(null)
        assertTest(
            "Ephemeral port allocated (> 1024)",
            port > 1024 && AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == port,
            "port=$port"
        )

        // Verify bound strictly to 127.0.0.1
        val socket = Socket("127.0.0.1", port)
        val localBoundAddr = socket.inetAddress.hostAddress
        socket.close()
        assertTest(
            "Bound strictly to loopback interface 127.0.0.1",
            localBoundAddr == "127.0.0.1",
            "hostAddress=$localBoundAddr"
        )

        // Idempotency: multiple start() calls return same port
        val portAgain = AITranslatorLocalServer.start(null)
        assertTest(
            "Multiple start() calls are idempotent",
            port == portAgain,
            "port=$port, portAgain=$portAgain"
        )

        // URL formatting
        val subUrlNoExt = AITranslatorLocalServer.getSubtitleUrl("sub_key_123")
        val subUrlWithExt = AITranslatorLocalServer.getSubtitleUrl("sub_key_123.srt")
        assertTest(
            "getSubtitleUrl formats clean loopback URL without double .srt",
            subUrlNoExt == "http://127.0.0.1:$port/sub/sub_key_123.srt" &&
                    subUrlWithExt == "http://127.0.0.1:$port/sub/sub_key_123.srt",
            "subUrlNoExt=$subUrlNoExt, subUrlWithExt=$subUrlWithExt"
        )

        // ---------------------------------------------------------------------
        // 2. ExoPlayer Streaming & RFC Conformance
        // ---------------------------------------------------------------------
        // 2.1 Valid GET request
        val (get200Code, get200Body) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "GET valid subtitle emits HTTP 200 OK with correct body",
            get200Code == 200 && get200Body.contains("Valid Subtitle Content"),
            "code=$get200Code, body=$get200Body"
        )

        // Inspect raw headers
        val s = Socket("127.0.0.1", port)
        s.getOutputStream().write("GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.UTF_8))
        val rawHeaderReader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val headers = mutableListOf<String>()
        var hLine = rawHeaderReader.readLine()
        while (hLine != null && hLine.isNotEmpty()) {
            headers.add(hLine)
            hLine = rawHeaderReader.readLine()
        }
        s.close()

        val hasUtf8Text = headers.any { it.contains("Content-Type: text/plain; charset=utf-8", ignoreCase = true) }
        val hasLength = headers.any { it.startsWith("Content-Length: ", ignoreCase = true) }
        val hasRanges = headers.any { it.contains("Accept-Ranges: bytes", ignoreCase = true) }
        val hasCors = headers.any { it.contains("Access-Control-Allow-Origin: *", ignoreCase = true) }
        val hasConnClose = headers.any { it.contains("Connection: close", ignoreCase = true) }

        assertTest(
            "HTTP 200 headers include Content-Type, Content-Length, Accept-Ranges, CORS, Connection: close",
            hasUtf8Text && hasLength && hasRanges && hasCors && hasConnClose,
            "headers=$headers"
        )

        // 2.2 HEAD request
        val (headCode, headBody) = sendRawHttp(
            "127.0.0.1", port,
            "HEAD /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "HEAD request returns HTTP 200 with 0 body bytes",
            headCode == 200 && headBody.isEmpty(),
            "code=$headCode, body=$headBody"
        )

        // 2.3 Query string stripping
        val (queryCode, queryBody) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/valid_sub.srt?lang=vi&token=xyz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "Query parameters are stripped cleanly and file is served",
            queryCode == 200 && queryBody.contains("Valid Subtitle Content"),
            "code=$queryCode, body=$queryBody"
        )

        // 2.4 URL-encoded filename
        val (encodedCode, encodedBody) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/My%20Spaced%20Subtitle.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "URL-encoded filename is decoded and served",
            encodedCode == 200 && encodedBody.contains("Spaced Subtitle"),
            "code=$encodedCode, body=$encodedBody"
        )

        // 2.5 Method Not Allowed (POST, PUT, DELETE)
        val (postCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "POST /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n"
        )
        val (putCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "PUT /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n"
        )
        assertTest(
            "Unsupported HTTP methods (POST, PUT) return HTTP 405 Method Not Allowed",
            postCode == 405 && putCode == 405,
            "postCode=$postCode, putCode=$putCode"
        )

        // 2.6 Malformed request line
        val (badCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "GARBAGE_REQUEST\r\n\r\n"
        )
        assertTest(
            "Malformed request line returns HTTP 400 Bad Request",
            badCode == 400,
            "badCode=$badCode"
        )

        // 2.7 Missing file returns 404
        val (notFoundCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/non_existent_file.srt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        assertTest(
            "Non-existent file returns HTTP 404 Not Found",
            notFoundCode == 404,
            "notFoundCode=$notFoundCode"
        )

        // 2.8 Path outside /sub/ returns 404
        val (rootCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "GET /secret_outside_cache.txt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        )
        assertTest(
            "Request without /sub/ prefix returns HTTP 404",
            rootCode == 404,
            "rootCode=$rootCode"
        )

        // ---------------------------------------------------------------------
        // 3. Security & Adversarial Path Traversal Shielding
        // ---------------------------------------------------------------------
        val traversalAttacks = listOf(
            "GET /sub/../../secret_outside_cache.txt HTTP/1.1",
            "GET /sub/..%2f..%2fsecret_outside_cache.txt HTTP/1.1",
            "GET /sub/%2e%2e/%2e%2e/secret_outside_cache.txt HTTP/1.1",
            "GET /sub/..\\..\\secret_outside_cache.txt HTTP/1.1",
            "GET /sub/..%5c..%5csecret_outside_cache.txt HTTP/1.1",
            "GET /sub/%2e%2e%5c%2e%2e%5csecret_outside_cache.txt HTTP/1.1",
            "GET /sub/nested/../../secret_outside_cache.txt HTTP/1.1",
            "GET /sub/. HTTP/1.1",
            "GET /sub/.. HTTP/1.1",
            "GET /sub/%2e%2e HTTP/1.1",
            "GET /sub/ HTTP/1.1"
        )

        var allTraversalBlocked = true
        var leakDetected = false
        val attackDetails = mutableListOf<String>()

        for (attack in traversalAttacks) {
            val (code, body) = sendRawHttp("127.0.0.1", port, "$attack\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            if (body.contains("CLASSIFIED_TOKEN_98765")) {
                leakDetected = true
                allTraversalBlocked = false
                attackDetails.add("LEAK! Attack: $attack -> Body contained secret!")
            } else if (code != 403 && code != 404) {
                allTraversalBlocked = false
                attackDetails.add("UNEXPECTED_STATUS ($code)! Attack: $attack")
            }
        }

        assertTest(
            "All path traversal vectors strictly rejected without secret leak (HTTP 403/404)",
            allTraversalBlocked && !leakDetected,
            "Failures: $attackDetails"
        )

        // Check specifically that /sub/..%2f..%2fsecret_outside_cache.txt returns 403 Forbidden
        val (encodedTraversalCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/..%2f..%2fsecret_outside_cache.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "Encoded traversal /sub/..%2f..%2f returns HTTP 403 Forbidden",
            encodedTraversalCode == 403,
            "code=$encodedTraversalCode"
        )

        // ---------------------------------------------------------------------
        // 4. Concurrency & Network Resilience
        // ---------------------------------------------------------------------
        // 4.1 50 concurrent requests across 10 threads
        val executor = Executors.newFixedThreadPool(10)
        val concurrentSuccesses = AtomicInteger(0)
        val concurrentTotal = 50
        val futures = (1..concurrentTotal).map {
            executor.submit {
                val (c, b) = sendRawHttp(
                    "127.0.0.1", port,
                    "GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                )
                if (c == 200 && b.contains("Valid Subtitle Content")) {
                    concurrentSuccesses.incrementAndGet()
                }
            }
        }
        futures.forEach { it.get() }
        assertTest(
            "50 concurrent HTTP requests from 10 threads all return HTTP 200",
            concurrentSuccesses.get() == concurrentTotal,
            "successes=${concurrentSuccesses.get()}/$concurrentTotal"
        )

        // 4.2 Client abrupt disconnect mid-stream
        val disconnectSocket = Socket("127.0.0.1", port)
        disconnectSocket.getOutputStream().write("GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.UTF_8))
        disconnectSocket.getOutputStream().flush()
        // Read only 1 byte then violently close
        disconnectSocket.getInputStream().read()
        disconnectSocket.close()
        Thread.sleep(100)
        // Server should still be healthy
        val (afterDisconnectCode, _) = sendRawHttp(
            "127.0.0.1", port,
            "GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "Server remains healthy after abrupt client disconnect",
            afterDisconnectCode == 200,
            "afterDisconnectCode=$afterDisconnectCode"
        )

        // 4.3 Socket Read Timeout (5000ms)
        // Open connection and send only partial request line, then verify server closes connection after timeout
        val slowSocket = Socket("127.0.0.1", port)
        slowSocket.soTimeout = 8000 // wait up to 8s
        val slowOut = slowSocket.getOutputStream()
        slowOut.write("GET ".toByteArray(Charsets.UTF_8)) // partial line
        slowOut.flush()

        val startWait = System.currentTimeMillis()
        var timedOutOrClosed = false
        try {
            val slowReader = BufferedReader(InputStreamReader(slowSocket.getInputStream(), Charsets.UTF_8))
            val line = slowReader.readLine()
            if (line == null) {
                timedOutOrClosed = true // EOF reached, server closed socket
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Client timed out
        } catch (_: java.io.IOException) {
            timedOutOrClosed = true
        } finally {
            slowSocket.close()
        }
        val elapsed = System.currentTimeMillis() - startWait
        assertTest(
            "Socket read timeout terminates stalled slowloris client connection (~5s)",
            timedOutOrClosed && elapsed >= 4500L,
            "timedOutOrClosed=$timedOutOrClosed, elapsed=${elapsed}ms"
        )

        // ---------------------------------------------------------------------
        // 5. Teardown & Lifecycle Restart
        // ---------------------------------------------------------------------
        AITranslatorLocalServer.stop()
        assertTest(
            "Server stops cleanly: isRunning=false, port=0",
            !AITranslatorLocalServer.isRunning && AITranslatorLocalServer.port == 0,
            "isRunning=${AITranslatorLocalServer.isRunning}, port=${AITranslatorLocalServer.port}"
        )

        // Verify socket is closed
        var connRefused = false
        try {
            val testClosedSock = Socket("127.0.0.1", port)
            testClosedSock.close()
        } catch (_: java.net.ConnectException) {
            connRefused = true
        } catch (_: java.io.IOException) {
            connRefused = true
        }
        assertTest(
            "Closed port refuses new connections immediately",
            connRefused,
            "connRefused=$connRefused"
        )

        // Restart server
        val newPort = AITranslatorLocalServer.start(null)
        assertTest(
            "Server successfully restarts on new ephemeral port",
            newPort > 1024 && AITranslatorLocalServer.isRunning,
            "newPort=$newPort"
        )

        val (restartCode, restartBody) = sendRawHttp(
            "127.0.0.1", newPort,
            "GET /sub/valid_sub.srt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTest(
            "Restarted server correctly handles requests",
            restartCode == 200 && restartBody.contains("Valid Subtitle Content"),
            "restartCode=$restartCode"
        )

        AITranslatorLocalServer.stop()
        executor.shutdown()
    } finally {
        AITranslatorLocalServer.stop()
        if (tempTestDir.exists()) tempTestDir.deleteRecursively()
    }

    println("================================================================================")
    println("ADVERSARIAL RESULTS: $passed PASSED, $failed FAILED")
    println("================================================================================")
    if (failed > 0) {
        System.exit(1)
    }
}
