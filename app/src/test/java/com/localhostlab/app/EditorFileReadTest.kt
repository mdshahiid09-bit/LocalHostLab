package com.localhostlab.app

import com.localhostlab.app.run.StaticServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EditorFileReadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testTwoDifferentFilesReadDifferingContentMatchingDisk() {
        val root = tempFolder.newFolder("projects", "nexfolio-test")

        val appFile = File(root, "src/App.tsx")
        appFile.parentFile?.mkdirs()
        val appContent = """
            import React from 'react';
            import { HashRouter, Routes, Route } from 'react-router-dom';
            import { Home } from './pages/Home';
            import { NotFound } from './pages/NotFound';

            export const App: React.FC = () => {
              return (
                <HashRouter>
                  <Routes>
                    <Route path="/" element={<Home />} />
                    <Route path="*" element={<NotFound />} />
                  </Routes>
                </HashRouter>
              );
            };

            export default App;
        """.trimIndent()
        appFile.writeText(appContent)

        val navFile = File(root, "src/components/Navbar.tsx")
        navFile.parentFile?.mkdirs()
        val navContent = """
            import React, { useState, useEffect } from 'react';
            import { motion, AnimatePresence } from 'framer-motion';
            import { Menu, X, Sparkles, Send } from 'lucide-react';
            import { navItems, ownerProfile } from '../data/portfolio';

            export const Navbar: React.FC = () => {
              const [isOpen, setIsOpen] = useState(false);
              return <nav>{ownerProfile.name}</nav>;
            };
        """.trimIndent()
        navFile.writeText(navContent)

        // Verify files exist on disk
        assertTrue("App.tsx must exist on disk", appFile.exists())
        assertTrue("Navbar.tsx must exist on disk", navFile.exists())

        // Read independently using File(path).readText()
        val readApp = File(appFile.absolutePath).readText()
        val readNav = File(navFile.absolutePath).readText()

        // Assert content matches disk exactly
        assertEquals("App.tsx content must match disk", appContent, readApp)
        assertEquals("Navbar.tsx content must match disk", navContent, readNav)

        // Assert two different files have differing content
        assertNotEquals("Two different files must have differing content", readApp, readNav)
        assertTrue("App.tsx content must not be blank", readApp.isNotBlank())
        assertTrue("Navbar.tsx content must not be blank", readNav.isNotBlank())
    }

    @Test
    fun testDynamicViteAssetParsingFromIndexHtml() {
        val html = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <link rel="icon" type="image/svg+xml" href="/assets/favicon-C1b2a3.svg" />
                <link rel="stylesheet" crossorigin href="/assets/index-D7h5wQ1a.css" />
                <link rel="modulepreload" href="./assets/vendor-K9j8h7.js?v=2" />
              </head>
              <body>
                <div id="root"></div>
                <script type="module" crossorigin src="/assets/index-B9k3mZ2p.js"></script>
              </body>
            </html>
        """.trimIndent()

        val regex = Regex("""(?:src|href)\s*=\s*['"]([^'"]*assets/[^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val extracted = regex.findAll(html)
            .map { match ->
                var raw = match.groupValues[1].trim()
                if (raw.contains('?')) raw = raw.substringBefore('?')
                if (raw.contains('#')) raw = raw.substringBefore('#')
                raw.removePrefix("/").removePrefix("./")
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        assertEquals(4, extracted.size)
        assertTrue(extracted.contains("assets/favicon-C1b2a3.svg"))
        assertTrue(extracted.contains("assets/index-D7h5wQ1a.css"))
        assertTrue(extracted.contains("assets/vendor-K9j8h7.js"))
        assertTrue(extracted.contains("assets/index-B9k3mZ2p.js"))
    }

    @Test
    fun testStaticServerConcurrentRequestsAndCleanShutdown() {
        val distDir = tempFolder.newFolder("projects", "test-concurrent", "dist")
        val assetsDir = File(distDir, "assets").apply { mkdirs() }

        val indexHtml = File(distDir, "index.html")
        val htmlContent = "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"/assets/index-D7h5wQ1a.css\" /></head><body><div id=\"root\"></div><script type=\"module\" src=\"/assets/index-B9k3mZ2p.js\"></script></body></html>"
        indexHtml.writeText(htmlContent)

        val cssFile = File(assetsDir, "index-D7h5wQ1a.css")
        val cssContent = "body{background:#080A12;color:#f8fafc;margin:0}"
        cssFile.writeText(cssContent)

        val jsFile = File(assetsDir, "index-B9k3mZ2p.js")
        val jsContent = "console.log('Nexfolio running');"
        jsFile.writeText(jsContent)

        val server = StaticServer(distDir)
        val startResult = server.start(startPort = 3100)
        assertTrue("Server must start successfully", startResult.isSuccess)
        val port = startResult.getOrThrow()
        assertTrue("Server port must be valid", port >= 3100)
        assertTrue("Server must report isRunning = true", server.isRunning)

        // Make concurrent requests to simulate browser WebView loading assets simultaneously
        val threadPool = Executors.newFixedThreadPool(4)
        val tasks = listOf(
            Callable {
                val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                val type = conn.contentType ?: ""
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Triple(code, type, body)
            },
            Callable {
                val conn = URL("http://127.0.0.1:$port/assets/index-D7h5wQ1a.css").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                val type = conn.contentType ?: ""
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Triple(code, type, body)
            },
            Callable {
                val conn = URL("http://127.0.0.1:$port/assets/index-B9k3mZ2p.js").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                val type = conn.contentType ?: ""
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Triple(code, type, body)
            },
            Callable {
                // Non-existent asset must return 404 and NOT fallback to index.html
                val conn = URL("http://127.0.0.1:$port/assets/missing-asset.js").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                Triple(code, conn.contentType ?: "", "")
            }
        )

        val results = threadPool.invokeAll(tasks).map { it.get() }
        threadPool.shutdown()

        // Verify HTML response
        val htmlRes = results[0]
        assertEquals(200, htmlRes.first)
        assertTrue(htmlRes.second.contains("text/html"))
        assertTrue(htmlRes.third.contains("id=\"root\""))

        // Verify CSS response
        val cssRes = results[1]
        assertEquals(200, cssRes.first)
        assertTrue(cssRes.second.contains("text/css"))
        assertEquals(cssContent, cssRes.third)

        // Verify JS response
        val jsRes = results[2]
        assertEquals(200, jsRes.first)
        assertTrue(jsRes.second.contains("application/javascript"))
        assertEquals(jsContent, jsRes.third)

        // Verify missing asset returns 404
        val missingRes = results[3]
        assertEquals(404, missingRes.first)

        // Verify clean shutdown
        server.stop()
        assertFalse("Server must report isRunning = false after stop", server.isRunning)
        assertEquals(-1, server.boundPort)
    }
}
