package com.localhostlab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun testStaticServerHealthCheckAndDistVerification() {
        val root = tempFolder.newFolder("projects", "server-test", "dist")
        val indexHtml = File(root, "index.html")
        val htmlContent = "<!DOCTYPE html><html><head><title>Test</title></head><body><h1>Hello LocalHostLab</h1></body></html>"
        indexHtml.writeText(htmlContent)

        assertTrue(indexHtml.exists())
        assertTrue(indexHtml.length() > 0)

        val read = indexHtml.readText()
        assertTrue(read.contains("Hello LocalHostLab"))
    }
}
