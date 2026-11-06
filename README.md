# LocalHostLab (v1)

**LocalHostLab** is an Android application built with **Kotlin + Jetpack Compose** (Material 3) that allows developers to import, create, edit, run, and preview **React (Vite) projects** locally on Android devices without external dependencies.

Owner / Author: **MD Shahid**

---

## 📱 Features

1. **Animated Splash Screen:**
   - 2-second splash screen with animated glowing logo and typography transitioning smoothly to the Home screen.
2. **Project List & Storage Management:**
   - Saved projects indexed in app-private storage (`projects.json` and `projects/<id>/`).
   - Project cards show: project name, running status, source type (`Folder`, `ZIP`, `Manual`), file count, and last run timestamp.
   - Spring bounce animations and smooth navigation transitions.
3. **Import Whole Folder (Main Feature):**
   - Single-action folder picker via Storage Access Framework (`ActivityResultContracts.OpenDocumentTree`).
   - Copies the entire React project folder structure recursively into private app storage (`projects/<id>/`).
   - Only copies the selected folder's contents and never its parent directory.
   - Smart exclusion: automatically skips `node_modules`, `dist`, `.git`, `.next`, `.cache`, and all hidden system files (`.*`).
   - High-speed 64KB stream buffers with directory traversal defense.
   - Real-time progress bar and live file counter ("Copying 42 of 58 files").
   - **Re-sync Capability:** Picking the same folder again updates/replaces files while preserving the project ID and configuration.
4. **ZIP Archive Import:**
   - Storage Access Framework file picker (`ActivityResultContracts.OpenDocument`).
   - Strict canonical Zip-slip vulnerability protection.
   - Accurate file counting and live extraction progress.
5. **Checking Stage & Validation:**
   - Validates that `package.json` exists and contains valid JSON.
   - Validates that `vite` is present in `dependencies` or `devDependencies`.
   - Validates that `index.html` exists at the project root.
   - On mismatch, displays a clear alert dialog: **"Ye React (Vite) project nahi lagta"** with the exact failure reason.
6. **Built-in Code Editor & Manual Projects ("New Project"):**
   - Starter template selector:
     - **React + Vite + TypeScript**
     - **React + Vite + Tailwind CSS**
     - **Empty React Project**
   - Built-in full-featured code editor:
     - Monospace font with real-time line numbers gutter.
     - Fast syntax highlighting for JS, JSX, TS, TSX, CSS, JSON, and Markdown.
     - Multi-tab file management with close buttons.
     - Project File Tree drawer with Add File, Add Folder, Rename, and Delete (with confirmation dialog).
     - Auto-indentation preserving leading whitespace.
     - Undo and Redo history stack.
     - Find and Replace bar (Search, Replace Once, Replace All).
     - Unsaved-changes confirmation dialog on exit.
7. **Local HTTP Server & Run Pipeline:**
   - Step 1: `npm install` inside the project directory.
   - Step 2: `npm run build`.
   - Step 3: Lightweight static HTTP server listening on loopback `127.0.0.1` starting at port 3000 (auto-increments to first free port) serving `dist/` with single-page-app (SPA) fallback to `index.html`.
   - Smart failure dialog showing failing step, last 20 log lines, Retry, Copy Log, and an intelligent **"Fix suggestion"** line.
   - Celebration: floating confetti particle animation upon successful launch.
   - Only one project runs at a time; starting another stops the previous one.
8. **Foreground Service:**
   - `ServerForegroundService` keeps the HTTP server running with a persistent Android notification and a "Stop" action while the app is in the background.
9. **Full-Screen WebView Preview:**
   - Direct in-app preview of `http://127.0.0.1:<port>/` with JavaScript and DOM storage enabled.
   - Editable URL bar with Go action to test custom routes.
   - Back, Refresh, and "Open in Chrome" external browser buttons.
10. **Settings & Maintenance:**
    - Appearance / Theme toggle: System Default, Light Theme, Dark Theme.
    - Default port configuration.
    - Clear cache and export files.
    - Clean `node_modules` across idle projects to reclaim device storage.

---

## 🛠️ Architecture

```
LocalHostLab/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── values/ (strings.xml, colors.xml, themes.xml)
│       │   ├── xml/ (file_paths.xml)
│       │   ├── drawable/
│       │   └── mipmap-anydpi-v26/
│       └── java/com/localhostlab/app/
│           ├── MainActivity.kt
│           ├── navigation/
│           │   ├── Screen.kt
│           │   └── NavGraph.kt
│           ├── ui/
│           │   ├── theme/ (Color.kt, Theme.kt, Type.kt)
│           │   ├── components/
│           │   │   └── ConfettiView.kt
│           │   └── screens/
│           │       ├── SplashScreen.kt
│           │       ├── HomeScreen.kt
│           │       ├── HomeViewModel.kt
│           │       ├── ProjectScreen.kt
│           │       ├── ProjectViewModel.kt
│           │       ├── PreviewScreen.kt
│           │       ├── EditorScreen.kt
│           │       ├── EditorViewModel.kt
│           │       ├── SettingsScreen.kt
│           │       └── SettingsViewModel.kt
│           ├── data/
│           │   ├── Project.kt
│           │   └── ProjectRepository.kt
│           ├── upload/
│           │   ├── ZipImporter.kt
│           │   ├── FolderImporter.kt
│           │   ├── ProjectChecker.kt
│           │   ├── ProjectExporter.kt
│           │   └── ProjectTemplates.kt
│           └── run/
│               ├── StaticServer.kt
│               ├── NodeEngine.kt
│               └── ProjectRunner.kt
│               └── ServerForegroundService.kt
├── .github/workflows/build.yml
└── README.md
```

---

## 🚀 Building & Running

### Prerequisites
- JDK 17
- Android SDK (API 34)
- Gradle 8.7

### Build with Gradle CLI
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI/CD
Every push to `main` triggers `.github/workflows/build.yml`, which:
1. Compiles the debug APK on Ubuntu with JDK 17 and Gradle 8.7.
2. Uploads `LocalHostLab-debug` as a workflow artifact.
3. Automatically publishes a GitHub Release tagged `v1.0.<run_number>` with `app-debug.apk` attached.
