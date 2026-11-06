package com.localhostlab.app.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ProjectTemplates {

    enum class TemplateType(val title: String, val description: String) {
        REACT_TS(
            title = "React + Vite (TypeScript)",
            description = "Standard React 18, Vite, TypeScript, and modern CSS"
        ),
        REACT_TAILWIND(
            title = "React + Vite + Tailwind CSS",
            description = "Tailwind CSS, PostCSS, Lucide icons, and modern styles"
        ),
        EMPTY_REACT(
            title = "Empty React Project",
            description = "Minimal React setup with empty App component"
        )
    }

    suspend fun createProjectFromTemplate(
        targetDir: File,
        projectName: String,
        templateType: TemplateType
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val sanitizedName = projectName.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
            var fileCount = 0

            fun writeFile(relativePath: String, content: String) {
                val file = File(targetDir, relativePath)
                file.parentFile?.mkdirs()
                file.writeText(content.trimIndent())
                fileCount++
            }

            when (templateType) {
                TemplateType.REACT_TS -> {
                    writeFile(
                        "package.json",
                        """
                        {
                          "name": "$sanitizedName",
                          "private": true,
                          "version": "0.0.1",
                          "type": "module",
                          "scripts": {
                            "dev": "vite",
                            "build": "tsc && vite build",
                            "preview": "vite preview"
                          },
                          "dependencies": {
                            "react": "^18.3.1",
                            "react-dom": "^18.3.1"
                          },
                          "devDependencies": {
                            "@types/react": "^18.3.3",
                            "@types/react-dom": "^18.3.0",
                            "@vitejs/plugin-react": "^4.3.1",
                            "typescript": "^5.5.3",
                            "vite": "^5.3.4"
                          }
                        }
                        """
                    )

                    writeFile(
                        "index.html",
                        """
                        <!doctype html>
                        <html lang="en">
                          <head>
                            <meta charset="UTF-8" />
                            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                            <title>$projectName</title>
                          </head>
                          <body>
                            <div id="root"></div>
                            <script type="module" src="/src/main.tsx"></script>
                          </body>
                        </html>
                        """
                    )

                    writeFile(
                        "vite.config.ts",
                        """
                        import { defineConfig } from 'vite'
                        import react from '@vitejs/plugin-react'

                        export default defineConfig({
                          plugins: [react()],
                        })
                        """
                    )

                    writeFile(
                        "tsconfig.json",
                        """
                        {
                          "compilerOptions": {
                            "target": "ES2020",
                            "useDefineForClassFields": true,
                            "lib": ["ES2020", "DOM", "DOM.Iterable"],
                            "module": "ESNext",
                            "skipLibCheck": true,
                            "moduleResolution": "bundler",
                            "allowImportingTsExtensions": true,
                            "resolveJsonModule": true,
                            "isolatedModules": true,
                            "noEmit": true,
                            "jsx": "react-jsx",
                            "strict": true,
                            "noUnusedLocals": true,
                            "noUnusedParameters": true,
                            "noFallthroughCasesInSwitch": true
                          },
                          "include": ["src"]
                        }
                        """
                    )

                    writeFile(
                        "src/index.css",
                        """
                        :root {
                          font-family: system-ui, Avenir, Helvetica, Arial, sans-serif;
                          line-height: 1.5;
                          font-weight: 400;
                          color-scheme: light dark;
                          color: rgba(255, 255, 255, 0.87);
                          background-color: #0f172a;
                        }
                        body {
                          margin: 0;
                          display: flex;
                          place-items: center;
                          min-width: 320px;
                          min-height: 100vh;
                          justify-content: center;
                        }
                        .card {
                          padding: 2em;
                          border-radius: 16px;
                          background: #1e293b;
                          box-shadow: 0 4px 20px rgba(0,0,0,0.5);
                          text-align: center;
                        }
                        button {
                          border-radius: 8px;
                          border: 1px solid transparent;
                          padding: 0.6em 1.2em;
                          font-size: 1em;
                          font-weight: 500;
                          background-color: #38bdf8;
                          color: #0f172a;
                          cursor: pointer;
                        }
                        """
                    )

                    writeFile(
                        "src/App.tsx",
                        """
                        import React, { useState } from 'react'
                        import './index.css'

                        export default function App() {
                          const [count, setCount] = useState(0)

                          return (
                            <div className="card">
                              <h1>$projectName</h1>
                              <p>Running on LocalHostLab inside Android</p>
                              <button onClick={() => setCount(c => c + 1)}>
                                Count is {count}
                              </button>
                            </div>
                          )
                        }
                        """
                    )

                    writeFile(
                        "src/main.tsx",
                        """
                        import React from 'react'
                        import ReactDOM from 'react-dom/client'
                        import App from './App.tsx'

                        ReactDOM.createRoot(document.getElementById('root')!).render(
                          <React.StrictMode>
                            <App />
                          </React.StrictMode>,
                        )
                        """
                    )
                }

                TemplateType.REACT_TAILWIND -> {
                    writeFile(
                        "package.json",
                        """
                        {
                          "name": "$sanitizedName",
                          "private": true,
                          "version": "0.0.1",
                          "type": "module",
                          "scripts": {
                            "dev": "vite",
                            "build": "vite build",
                            "preview": "vite preview"
                          },
                          "dependencies": {
                            "react": "^18.3.1",
                            "react-dom": "^18.3.1"
                          },
                          "devDependencies": {
                            "@vitejs/plugin-react": "^4.3.1",
                            "autoprefixer": "^10.4.19",
                            "postcss": "^8.4.39",
                            "tailwindcss": "^3.4.4",
                            "vite": "^5.3.4"
                          }
                        }
                        """
                    )

                    writeFile(
                        "index.html",
                        """
                        <!doctype html>
                        <html lang="en">
                          <head>
                            <meta charset="UTF-8" />
                            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                            <title>$projectName</title>
                          </head>
                          <body class="bg-slate-900 text-slate-100 min-h-screen flex items-center justify-center p-4">
                            <div id="root"></div>
                            <script type="module" src="/src/main.jsx"></script>
                          </body>
                        </html>
                        """
                    )

                    writeFile(
                        "tailwind.config.js",
                        """
                        /** @type {import('tailwindcss').Config} */
                        export default {
                          content: [
                            "./index.html",
                            "./src/**/*.{js,ts,jsx,tsx}",
                          ],
                          theme: {
                            extend: {},
                          },
                          plugins: [],
                        }
                        """
                    )

                    writeFile(
                        "postcss.config.js",
                        """
                        export default {
                          plugins: {
                            tailwindcss: {},
                            autoprefixer: {},
                          },
                        }
                        """
                    )

                    writeFile(
                        "vite.config.js",
                        """
                        import { defineConfig } from 'vite'
                        import react from '@vitejs/plugin-react'

                        export default defineConfig({
                          plugins: [react()],
                        })
                        """
                    )

                    writeFile(
                        "src/index.css",
                        """
                        @tailwind base;
                        @tailwind components;
                        @tailwind utilities;
                        """
                    )

                    writeFile(
                        "src/App.jsx",
                        """
                        import React, { useState } from 'react'
                        import './index.css'

                        export default function App() {
                          const [likes, setLikes] = useState(0)

                          return (
                            <div className="max-w-md w-full bg-slate-800 rounded-2xl p-6 shadow-xl border border-slate-700 text-center">
                              <h1 className="text-2xl font-bold text-sky-400 mb-2">$projectName</h1>
                              <p className="text-slate-400 text-sm mb-6">Built with Vite, React & Tailwind CSS</p>
                              <button
                                onClick={() => setLikes(l => l + 1)}
                                className="px-5 py-2.5 bg-sky-500 hover:bg-sky-400 text-slate-900 font-semibold rounded-xl shadow transition"
                              >
                                👍 Likes: {likes}
                              </button>
                            </div>
                          )
                        }
                        """
                    )

                    writeFile(
                        "src/main.jsx",
                        """
                        import React from 'react'
                        import ReactDOM from 'react-dom/client'
                        import App from './App.jsx'

                        ReactDOM.createRoot(document.getElementById('root')).render(
                          <React.StrictMode>
                            <App />
                          </React.StrictMode>,
                        )
                        """
                    )
                }

                TemplateType.EMPTY_REACT -> {
                    writeFile(
                        "package.json",
                        """
                        {
                          "name": "$sanitizedName",
                          "private": true,
                          "version": "0.0.1",
                          "type": "module",
                          "scripts": {
                            "dev": "vite",
                            "build": "vite build",
                            "preview": "vite preview"
                          },
                          "dependencies": {
                            "react": "^18.3.1",
                            "react-dom": "^18.3.1"
                          },
                          "devDependencies": {
                            "@vitejs/plugin-react": "^4.3.1",
                            "vite": "^5.3.4"
                          }
                        }
                        """
                    )

                    writeFile(
                        "index.html",
                        """
                        <!doctype html>
                        <html lang="en">
                          <head>
                            <meta charset="UTF-8" />
                            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                            <title>$projectName</title>
                          </head>
                          <body>
                            <div id="root"></div>
                            <script type="module" src="/src/main.jsx"></script>
                          </body>
                        </html>
                        """
                    )

                    writeFile(
                        "vite.config.js",
                        """
                        import { defineConfig } from 'vite'
                        import react from '@vitejs/plugin-react'

                        export default defineConfig({
                          plugins: [react()],
                        })
                        """
                    )

                    writeFile(
                        "src/App.jsx",
                        """
                        import React from 'react'

                        export default function App() {
                          return (
                            <div style={{ padding: '2rem', fontFamily: 'sans-serif' }}>
                              <h1>$projectName</h1>
                              <p>Start writing your code in the LocalHostLab editor!</p>
                            </div>
                          )
                        }
                        """
                    )

                    writeFile(
                        "src/main.jsx",
                        """
                        import React from 'react'
                        import ReactDOM from 'react-dom/client'
                        import App from './App.jsx'

                        ReactDOM.createRoot(document.getElementById('root')).render(
                          <React.StrictMode>
                            <App />
                          </React.StrictMode>,
                        )
                        """
                    )
                }
            }

            Result.success(fileCount)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
