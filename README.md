# LocalHostLab (v1)

**LocalHostLab** is an Android application built with **Kotlin + Jetpack Compose** (Material 3) that allows developers to import, extract, run, and preview **Vite React projects** locally on Android.

---

## 📱 Features

1. **Animated Splash Screen:** Smooth spring animation welcoming the user before loading projects.
2. **Project List & Storage:** Manage imported projects stored in app-private storage (`projects/<projectId>/`).
3. **Dual Import (ZIP Archive + Project Folder):**
   - **ZIP Archive:** Pick `.zip` files via `ActivityResultContracts.OpenDocument` with canonical Zip-slip defense.
   - **Project Folder:** Directly select uncompressed project folders via `ActivityResultContracts.OpenDocumentTree` (Storage Access Framework).
   - High-throughput streaming with 64KB buffers and smart `.git` and `node_modules` filtering to prevent SAF IPC bottlenecks and ANRs.
   - Real-time progress indicator displaying processed file count and current file name.
4. **Vite Project Verification:**
   - Inspects `package.json` for validity.
   - Ensures `vite` is present in `dependencies` or `devDependencies`.
   - Rejects invalid archives with a clear error dialog ("This is not a Vite React project.").
5. **Local HTTP Server & Runner:**
   - Step 1: `npm install` inside the project folder.
   - Step 2: `npm run build`.
   - Step 3: Lightweight in-process static HTTP server listening on loopback `127.0.0.1` starting at port 3000 (first free port).
   - **SPA Fallback:** All non-file routes automatically fallback to `index.html`.
6. **Live Console Log View:**
   - Auto-scrolling, monospace build and server console output.
   - "Copy log" button for sharing and debugging.
   - Failure dialog capturing the failed step and last 20 log lines with "Retry" and "Copy log".
7. **Full-Screen WebView Preview:**
   - Direct preview of `http://127.0.0.1:<port>/`.
   - JavaScript and DOM storage enabled.
   - Top bar with back, refresh, URL pill, and "Open in Chrome" external browser launcher.

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
│       │   ├── drawable/ (ic_launcher_background.xml, ic_launcher_foreground.xml)
│       │   └── mipmap-anydpi-v26/ (ic_launcher.xml, ic_launcher_round.xml)
│       └── java/com/localhostlab/app/
│           ├── MainActivity.kt
│           ├── navigation/
│           │   ├── Screen.kt
│           │   └── NavGraph.kt
│           ├── ui/
│           │   ├── theme/ (Color.kt, Theme.kt, Type.kt)
│           │   └── screens/
│           │       ├── SplashScreen.kt
│           │       ├── HomeScreen.kt
│           │       ├── HomeViewModel.kt
│           │       ├── ProjectScreen.kt
│           │       ├── ProjectViewModel.kt
│           │       └── PreviewScreen.kt
│           ├── data/
│           │   ├── Project.kt
│           │   └── ProjectRepository.kt
│           ├── upload/
│           │   ├── ZipImporter.kt
│           │   ├── FolderImporter.kt
│           │   └── ProjectChecker.kt
│           └── run/
│               ├── StaticServer.kt
│               ├── NodeEngine.kt
│               └── ProjectRunner.kt
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
# Assemble Debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI/CD
Every push to `main` triggers `.github/workflows/build.yml`, which:
1. Compiles the debug APK on Ubuntu with JDK 17 and Gradle 8.7.
2. Uploads `LocalHostLab-debug` as a workflow artifact.
3. Automatically publishes a GitHub Release tagged `v1.0.<run_number>` with `app-debug.apk` attached.

---

## 🧪 Testing with a Sample Vite React ZIP

To test the upload and preview flow:

1. Create a minimal Vite React project or use an existing one:
   ```bash
   npm create vite@latest sample-app -- --template react
   cd sample-app
   npm install
   npm run build
   ```
2. Archive the project directory into a ZIP file:
   ```bash
   zip -r sample-app.zip .
   ```
3. Transfer `sample-app.zip` to your Android device.
4. Open **LocalHostLab**, tap **"Upload ZIP"**, and select `sample-app.zip`.
5. The app verifies `package.json`, displays the project card on the Home screen, and lets you tap **"Run"** and **"Preview"**!

---

## 📌 Node.js Mobile Integration Notes

- For on-device `npm install` and `npm run build` execution without pre-built assets, Node.js is integrated via **nodejs-mobile** (`libnode.so` and JNI bindings `startNodeWithArguments`).
- When pre-built `dist/` is present in the ZIP, the built-in pure Kotlin `StaticServer` immediately serves the project at `http://127.0.0.1:3000/` with SPA routing and zero external dependencies.
- See `app/src/main/java/com/localhostlab/app/run/NodeEngine.kt` for native linking configuration.
