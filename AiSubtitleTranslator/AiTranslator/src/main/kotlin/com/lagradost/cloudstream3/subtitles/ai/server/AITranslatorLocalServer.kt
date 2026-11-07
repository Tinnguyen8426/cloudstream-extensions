package com.lagradost.cloudstream3.subtitles.ai.server

import android.content.Context
import com.lagradost.cloudstream3.subtitles.ai.cache.SubtitleCacheManager
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
 * Embedded local loopback HTTP server for streaming translated SubRip (.srt) subtitles
 * directly into CloudStream's ExoPlayer pipeline with zero network overhead.
 *
 * CloudStream's PlayerSubtitleHelper hardcodes SubtitleOrigin.URL for SubtitleFile instances
 * and delegates downloading to ExoPlayer's OkHttpDataSource.Factory. OkHttp strictly forbids
 * "file://" URI schemes (throwing IllegalArgumentException: Expected URL scheme 'http' or 'https' but was 'file').
 *
 * This server binds exclusively to the loopback interface 127.0.0.1 on an ephemeral port (ServerSocket(0)),
 * eliminating port collision across devices and processes. It provides canonical path traversal shielding
 * (HTTP 403 Forbidden on relative traversal attacks) and serves UTF-8 text/plain subtitle streams.
 */
object AITranslatorLocalServer {

    private const val TAG = "AITranslatorLocalServer"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var listeningPort: Int = 0

    @Volatile
    private var isServerActive: Boolean = false

    @Volatile
    private var storedContext: Context? = null

    private var acceptThread: Thread? = null
    private var clientExecutor: ExecutorService? = null

    private val registeredFiles = ConcurrentHashMap<String, File>()

    /**
     * Optional custom base directory override for standalone JVM testing or custom storage locations.
     */
    @Volatile
    var customBaseDir: File? = null

    val port: Int
        get() = listeningPort

    val isRunning: Boolean
        get() = isServerActive && serverSocket?.isClosed == false && serverSocket?.isBound == true

    /**
     * Starts the embedded loopback HTTP server on an ephemeral port (0).
     * Thread-safe and idempotent.
     */
    @Synchronized
    fun start(context: Context? = null): Int {
        if (isRunning && listeningPort > 0) {
            if (context != null) storedContext = context
            return listeningPort
        }

        try {
            stop() // Clean up any lingering sockets or threads

            storedContext = context
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            listeningPort = socket.localPort
            isServerActive = true

            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "AITranslatorLocalServer-Worker").apply {
                    isDaemon = true
                }
            }
            clientExecutor = executor

            acceptThread = thread(name = "AITranslatorLocalServer-Accept", isDaemon = true) {
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
                            logE(TAG, "Error accepting client connection: ${e.message}", e)
                        }
                    }
                }
            }

            logI(TAG, "AITranslatorLocalServer listening on http://127.0.0.1:$listeningPort")
            return listeningPort
        } catch (e: Throwable) {
            logE(TAG, "Failed to start local subtitle server: ${e.message}", e)
            return 0
        }
    }

    /**
     * Stops the embedded HTTP server, closes open sockets, and terminates worker threads.
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
        logI(TAG, "AITranslatorLocalServer stopped")
    }

    /**
     * Resolves the loopback streaming URL for a given cacheKey:
     * "http://127.0.0.1:<port>/sub/<cacheKey>.srt"
     */
    fun getSubtitleUrl(cacheKey: String): String {
        ensureRunning()
        val fileName = if (cacheKey.endsWith(".srt")) cacheKey else "$cacheKey.srt"
        return "http://127.0.0.1:$listeningPort/sub/$fileName"
    }

    /**
     * Registers a local subtitle file to be served over loopback HTTP.
     * Returns: "http://127.0.0.1:<port>/sub/<fileName>"
     */
    fun registerSubtitle(file: File): String {
        ensureRunning()
        registeredFiles[file.name] = file
        return "http://127.0.0.1:$listeningPort/sub/${file.name}"
    }

    /**
     * Alias for registerSubtitle.
     */
    fun getUrlForFile(file: File): String {
        return registerSubtitle(file)
    }

    private fun ensureRunning() {
        if (!isRunning) {
            start(storedContext)
        }
    }

    /**
     * Dispatches an incoming HTTP connection from ExoPlayer or a verification harness.
     */
    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000 // 5 seconds read timeout
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            // 1. Parse request line: e.g. "GET /sub/abc123hash.srt HTTP/1.1"
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.trim().split(" ")
            if (requestParts.size < 2) {
                sendErrorResponse(outputStream, 400, "Bad Request")
                return
            }

            val method = requestParts[0].uppercase()
            val rawPath = requestParts[1]

            // 2. Consume request headers until empty line
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
            }

            if (method != "GET" && method != "HEAD") {
                sendErrorResponse(outputStream, 405, "Method Not Allowed")
                return
            }

            // 3. Validate path routing
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

            // 4. Resolve candidate full directory & canonical validation
            val baseDir = customBaseDir ?: SubtitleCacheManager.getBaseDir(storedContext)
            val fullDir = if (baseDir.name == "full") baseDir else File(baseDir, "full").apply { if (!exists()) mkdirs() }

            // Path Traversal Protection: reject files outside authorized fullDir (e.g. /sub/../../secret.txt or drive letter injection)
            var targetFile: File? = null
            val isAuthorized = try {
                val canonDir = fullDir.canonicalFile
                val candidate = File(fullDir, fileName).canonicalFile
                targetFile = candidate
                candidate != canonDir && candidate.toPath().normalize().startsWith(canonDir.toPath().normalize())
            } catch (_: Throwable) {
                false
            }

            if (!isAuthorized || targetFile == null) {
                sendErrorResponse(outputStream, 403, "Forbidden")
                return
            }

            // 5. Locate file to serve: registered map first, then targetFile in fullDir
            val fileToServe = registeredFiles[fileName]
                ?: registeredFiles[rawFileName]
                ?: targetFile

            if (!fileToServe.exists() || !fileToServe.isFile) {
                sendErrorResponse(outputStream, 404, "File Not Found")
                return
            }

            // 6. Emit HTTP 200 OK with streaming headers
            val fileBytes = fileToServe.readBytes()
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
            // Connection closed by client (e.g. video seek or player teardown)
        } catch (e: Throwable) {
            logE(TAG, "Error handling client request: ${e.message}")
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

    private fun logI(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (_: Throwable) {
            // JVM standalone fallback
        }
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.e(tag, msg, tr)
            } else {
                android.util.Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            // JVM standalone fallback
        }
    }
}
