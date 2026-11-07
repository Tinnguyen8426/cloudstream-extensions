package com.kitsunekko.enricher

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Embedded local loopback HTTP server for streaming cached Japanese subtitles to CloudStream.
 *
 * CloudStream's PlayerSubtitleHelper hardcodes SubtitleOrigin.URL for all SubtitleFile instances
 * and delegates downloading to ExoPlayer's OkHttpDataSource.Factory. OkHttp rejects "file://" URLs
 * with IllegalArgumentException ("Expected URL scheme 'http' or 'https' but was 'file'").
 *
 * This server binds exclusively to loopback 127.0.0.1 on an ephemeral port and serves normalized
 * UTF-8 SubRip (.srt) files at "http://127.0.0.1:<port>/sub/<id>.srt" with 0ms network latency.
 */
object KitsunekkoLocalServer {

    private const val TAG = "KitsunekkoLocalServer"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var listeningPort: Int = 0

    @Volatile
    private var isServerActive: Boolean = false

    private var acceptThread: Thread? = null
    private var clientExecutor: ExecutorService? = null

    private val registeredFiles = ConcurrentHashMap<String, File>()

    val port: Int
        get() = listeningPort

    val isRunning: Boolean
        get() = isServerActive && serverSocket?.isClosed == false && serverSocket?.isBound == true

    /**
     * Starts the embedded loopback HTTP server on an ephemeral port.
     * Safe to call multiple times (idempotent).
     */
    @Synchronized
    fun start(context: Context? = null): Int {
        if (isRunning && listeningPort > 0) {
            return listeningPort
        }

        try {
            stop() // Clean up any stale state

            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            listeningPort = socket.localPort
            isServerActive = true

            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "KitsunekkoLocalServer-Worker").apply {
                    isDaemon = true
                }
            }
            clientExecutor = executor

            acceptThread = thread(name = "KitsunekkoLocalServer-Accept", isDaemon = true) {
                while (isServerActive && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        executor.execute {
                            handleClient(clientSocket)
                        }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Throwable) {
                        if (isServerActive) {
                            Log.e(TAG, "Error accepting client connection: ${e.message}")
                        }
                    }
                }
            }

            Log.i(TAG, "Embedded subtitle server listening on http://127.0.0.1:$listeningPort")
            return listeningPort
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start local subtitle server: ${e.message}", e)
            return 0
        }
    }

    /**
     * Stops the embedded HTTP server and releases bound sockets and threads.
     */
    @Synchronized
    fun stop() {
        isServerActive = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        listeningPort = 0

        try {
            acceptThread?.interrupt()
        } catch (_: Throwable) {}
        acceptThread = null

        try {
            clientExecutor?.shutdownNow()
        } catch (_: Throwable) {}
        clientExecutor = null

        registeredFiles.clear()
        Log.i(TAG, "Embedded subtitle server stopped")
    }

    /**
     * Registers a local subtitle file to be served over loopback HTTP.
     * Returns the full URL: "http://127.0.0.1:<port>/sub/<fileName>".
     */
    fun registerSubtitle(file: File): String {
        ensureRunning()
        registeredFiles[file.name] = file
        return "http://127.0.0.1:$listeningPort/sub/${file.name}"
    }

    /**
     * Resolves or generates the loopback HTTP URL for a given subtitle file.
     */
    fun getUrlForFile(file: File): String {
        return registerSubtitle(file)
    }

    private fun ensureRunning() {
        if (!isRunning) {
            start()
        }
    }

    /**
     * Handles an incoming HTTP request on loopback.
     */
    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000 // 5 seconds read timeout
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            // 1. Read request line: e.g. "GET /sub/ep_1_frieren.srt HTTP/1.1"
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.trim().split(" ")
            if (requestParts.size < 2) {
                sendErrorResponse(outputStream, 400, "Bad Request")
                return
            }

            val method = requestParts[0].uppercase()
            val rawPath = requestParts[1]

            // 2. Consume remaining request headers until empty line
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
            }

            if (method != "GET" && method != "HEAD") {
                sendErrorResponse(outputStream, 405, "Method Not Allowed")
                return
            }

            // 3. Resolve target file
            val path = rawPath.substringBefore("?")
            if (!path.startsWith("/sub/")) {
                sendErrorResponse(outputStream, 404, "Not Found")
                return
            }

            val rawFileName = path.removePrefix("/sub/")
            val fileName = try {
                URLDecoder.decode(rawFileName, "UTF-8")
            } catch (_: Throwable) {
                rawFileName
            }

            var targetFile: File? = registeredFiles[fileName] ?: registeredFiles[rawFileName]

            // Fallback lookup in cache directory if not explicitly in registry map
            if (targetFile == null || !targetFile.exists()) {
                val ctx = KitsunekkoHook.appContext
                if (ctx != null) {
                    val subsDir = ZipSubtitleExtractor.getSubsCacheDir(ctx)
                    val candidate = File(subsDir, fileName)
                    try {
                        ZipSubtitleExtractor.validateZipSlip(candidate, subsDir)
                        if (candidate.exists() && candidate.isFile) {
                            targetFile = candidate
                        }
                    } catch (_: Throwable) {}
                }
            }

            if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }

            // 4. Send HTTP 200 OK with UTF-8 text/plain content
            val fileBytes = targetFile.readBytes()
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${fileBytes.size}\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"

            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            if (method == "GET") {
                outputStream.write(fileBytes)
            }
            outputStream.flush()
        } catch (_: SocketException) {
            // Connection closed by client (e.g. player cancelled request)
        } catch (e: Throwable) {
            Log.w(TAG, "Error handling client request: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {}
        }
    }

    private fun sendErrorResponse(output: OutputStream, statusCode: Int, statusText: String) {
        try {
            val response = "HTTP/1.1 $statusCode $statusText\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Throwable) {}
    }
}
