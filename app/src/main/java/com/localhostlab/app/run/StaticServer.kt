package com.localhostlab.app.run

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP static server running on loopback (127.0.0.1).
 * Uses a dedicated accept thread and a cached thread pool to handle
 * multiple concurrent requests simultaneously (e.g. concurrent WebView asset loading).
 * Stays alive until [stop] is explicitly called.
 */
class StaticServer(
    private val staticDir: File
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var boundPort: Int = -1
        private set

    private var acceptThread: Thread? = null
    private var clientExecutor: ExecutorService? = null
    private val isRunningFlag = AtomicBoolean(false)

    val isRunning: Boolean
        get() = isRunningFlag.get() && serverSocket != null && !(serverSocket?.isClosed ?: true)

    /**
     * Starts listening on 127.0.0.1, scanning starting from [startPort] for the first free port.
     * Uses a dedicated accept thread and cached thread pool for concurrent client handling.
     */
    @Synchronized
    fun start(startPort: Int = 3000): Result<Int> {
        if (isRunning) {
            return Result.success(boundPort)
        }

        var port = startPort
        var bound = false
        val loopback = InetAddress.getByName("127.0.0.1")

        while (port < 65535 && !bound) {
            try {
                // Backlog of 100 pending connections
                serverSocket = ServerSocket(port, 100, loopback)
                boundPort = port
                bound = true
            } catch (e: Exception) {
                port++
            }
        }

        val socket = serverSocket
        if (!bound || socket == null) {
            return Result.failure(Exception("Unable to find an available port to bind HTTP server on 127.0.0.1."))
        }

        isRunningFlag.set(true)

        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                name = "StaticServer-Client-${System.currentTimeMillis()}"
            }
        }
        clientExecutor = executor

        acceptThread = Thread({
            while (isRunningFlag.get() && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    // Set client read timeout so slow or dropped connections don't hang worker threads
                    clientSocket.soTimeout = 15000

                    executor.execute {
                        try {
                            handleClient(clientSocket)
                        } catch (e: Exception) {
                            // Client connection reset or aborted; isolated per connection
                        } finally {
                            try {
                                clientSocket.close()
                            } catch (e: Exception) {
                                // Ignored
                            }
                        }
                    }
                } catch (e: SocketException) {
                    // Socket closed on stop()
                    if (!isRunningFlag.get() || socket.isClosed) break
                } catch (e: Exception) {
                    if (!isRunningFlag.get() || socket.isClosed) break
                }
            }
        }, "StaticServer-Accept-$boundPort").apply {
            isDaemon = true
            start()
        }

        return Result.success(boundPort)
    }

    private fun handleClient(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val out = BufferedOutputStream(client.getOutputStream())

        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return

        val method = parts[0].uppercase()
        var rawUri = parts[1]

        // Drain request headers
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) {
            line = reader.readLine()
        }

        val isHead = method == "HEAD"
        val isGet = method == "GET"

        if (!isGet && !isHead) {
            sendResponse(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed".toByteArray(), isHead = false)
            return
        }

        // Strip query parameters and fragment
        val queryIndex = rawUri.indexOf('?')
        if (queryIndex != -1) {
            rawUri = rawUri.substring(0, queryIndex)
        }
        val hashIndex = rawUri.indexOf('#')
        if (hashIndex != -1) {
            rawUri = rawUri.substring(0, hashIndex)
        }

        val decodedPath = try {
            URLDecoder.decode(rawUri, "UTF-8")
        } catch (e: Exception) {
            rawUri
        }

        val targetPath = if (decodedPath == "/" || decodedPath.isEmpty()) "/index.html" else decodedPath
        var fileToServe = File(staticDir, targetPath.removePrefix("/"))

        // Path traversal check
        val canonicalStaticDir = staticDir.canonicalPath
        if (fileToServe.exists() && !fileToServe.canonicalPath.startsWith(canonicalStaticDir)) {
            sendResponse(out, 403, "Forbidden", "text/plain; charset=utf-8", "Access Denied".toByteArray(), isHead)
            return
        }

        // Subpath / base tolerance: if requested /project/assets/..., resolve to assets/...
        if (!fileToServe.exists() && targetPath.contains("/assets/")) {
            val assetSub = "assets/" + targetPath.substringAfter("/assets/")
            val candidate = File(staticDir, assetSub)
            if (candidate.exists() && candidate.isFile && candidate.canonicalPath.startsWith(canonicalStaticDir)) {
                fileToServe = candidate
            }
        }

        // Hashed vs Unhashed bundle tolerance:
        // If /assets/index-D7h5wQ1a.css requested but disk only has index.css (or vice-versa), serve it!
        if (!fileToServe.exists() && targetPath.contains("/assets/")) {
            val ext = targetPath.substringAfterLast('.', "").lowercase()
            if (ext == "css" || ext == "js") {
                val altFile = File(staticDir, "assets/index.$ext")
                if (altFile.exists() && altFile.isFile && altFile.canonicalPath.startsWith(canonicalStaticDir)) {
                    fileToServe = altFile
                } else {
                    val assetsFolder = File(staticDir, "assets")
                    if (assetsFolder.exists() && assetsFolder.isDirectory) {
                        val match = assetsFolder.listFiles()?.firstOrNull { it.isFile && it.extension.equals(ext, ignoreCase = true) }
                        if (match != null && match.canonicalPath.startsWith(canonicalStaticDir)) {
                            fileToServe = match
                        }
                    }
                }
            }
        }

        // SPA Fallback:
        // Only non-asset paths (HTML navigation / client routes) fallback to index.html.
        // Asset requests must 404 if missing, so browsers do not misinterpret HTML as JS or CSS.
        if (!fileToServe.exists() || fileToServe.isDirectory) {
            val isAssetRequest = targetPath.startsWith("/assets/") || targetPath.contains("/assets/") ||
                    (targetPath.substringAfterLast('/').contains('.') && !targetPath.endsWith(".html"))

            if (!isAssetRequest) {
                val indexFile = File(staticDir, "index.html")
                if (indexFile.exists() && indexFile.isFile) {
                    fileToServe = indexFile
                }
            }
        }

        if (!fileToServe.exists() || !fileToServe.isFile) {
            val notFoundMsg = "<!doctype html><html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1><p>LocalHostLab: File not found in dist/: ${fileToServe.name}</p></body></html>"
            sendResponse(out, 404, "Not Found", "text/html; charset=utf-8", notFoundMsg.toByteArray(), isHead)
            return
        }

        val mimeType = getMimeType(fileToServe.extension)
        val fileBytes = FileInputStream(fileToServe).use { it.readBytes() }
        sendResponse(out, 200, "OK", mimeType, fileBytes, isHead)
    }

    private fun sendResponse(
        out: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        isHead: Boolean
    ) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        out.write(header.toByteArray(Charsets.UTF_8))
        if (!isHead) {
            out.write(body)
        }
        out.flush()
    }

    fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs", "cjs" -> "application/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunningFlag.getAndSet(false)) {
            return
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            serverSocket = null
            boundPort = -1
        }

        try {
            acceptThread?.interrupt()
        } catch (e: Exception) {
            // Ignored
        }
        acceptThread = null

        clientExecutor?.let { executor ->
            try {
                executor.shutdownNow()
                executor.awaitTermination(500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Ignored
            }
        }
        clientExecutor = null
    }
}