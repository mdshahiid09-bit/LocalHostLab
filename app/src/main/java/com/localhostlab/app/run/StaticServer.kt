package com.localhostlab.app.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class StaticServer(
    private val staticDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    var boundPort: Int = -1
        private set

    val isRunning: Boolean
        get() = serverSocket != null && !(serverSocket?.isClosed ?: true)

    /**
     * Starts listening on 127.0.0.1, scanning starting from [startPort] for the first free port.
     */
    suspend fun start(startPort: Int = 3000): Result<Int> = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext Result.success(boundPort)
        }

        var port = startPort
        var bound = false
        val loopback = InetAddress.getByName("127.0.0.1")

        while (port < 65535 && !bound) {
            try {
                serverSocket = ServerSocket(port, 50, loopback)
                boundPort = port
                bound = true
            } catch (e: Exception) {
                port++
            }
        }

        if (!bound || serverSocket == null) {
            return@withContext Result.failure(Exception("Unable to find an available port to bind HTTP server."))
        }

        val socket = serverSocket!!
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (socket.isClosed) break
                }
            }
        }

        Result.success(boundPort)
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                var rawUri = parts[1]

                // Drain remaining request headers
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

                // Strip query parameters
                val queryIndex = rawUri.indexOf('?')
                if (queryIndex != -1) {
                    rawUri = rawUri.substring(0, queryIndex)
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

                // SPA Fallback:
                // If the exact file doesn't exist, check whether it's an unknown client-side route
                // or if it's an asset. If not an existing file, fallback to dist/index.html.
                if (!fileToServe.exists() || fileToServe.isDirectory) {
                    val isAssetRequest = targetPath.startsWith("/assets/") ||
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
        } catch (e: Exception) {
            // Client connection closed or aborted
        }
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

    private fun getMimeType(extension: String): String {
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

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            serverJob?.cancel()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            serverSocket = null
            serverJob = null
            boundPort = -1
        }
    }
}
