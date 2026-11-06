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

                val method = parts[0]
                var rawUri = parts[1]

                // Drain remaining request headers
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                if (method != "GET" && method != "HEAD") {
                    sendResponse(out, 405, "Method Not Allowed", "text/plain", "Method Not Allowed".toByteArray())
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

                // SPA Fallback: If file does not exist and doesn't contain a file extension, serve index.html
                if (!fileToServe.exists() || fileToServe.isDirectory) {
                    val hasExtension = targetPath.substringAfterLast('/').contains('.')
                    if (!hasExtension || !fileToServe.exists()) {
                        val indexFile = File(staticDir, "index.html")
                        if (indexFile.exists()) {
                            fileToServe = indexFile
                        }
                    }
                }

                if (!fileToServe.exists() || !fileToServe.isFile) {
                    val notFoundMsg = "<html><body><h1>404 Not Found</h1><p>LocalHostLab: File not found in dist/</p></body></html>"
                    sendResponse(out, 404, "Not Found", "text/html; charset=utf-8", notFoundMsg.toByteArray())
                    return
                }

                val mimeType = getMimeType(fileToServe.extension)
                val fileBytes = FileInputStream(fileToServe).use { it.readBytes() }
                sendResponse(out, 200, "OK", mimeType, fileBytes)
            }
        } catch (e: Exception) {
            // Client disconnected or connection aborted
        }
    }

    private fun sendResponse(
        out: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain; charset=utf-8"
            "map" -> "application/json"
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
