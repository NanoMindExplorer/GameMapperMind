# FASE 4 — Error Handling & Security

Sesuai kontrak: 1 fase per respons, file lengkap tanpa placeholder, defense in depth.

## File yang dihasilkan (8 file)

| # | File | Path tujuan di repo |
|---|------|---------------------|
| 1 | `react/ErrorBoundary.tsx` | `src/components/ErrorBoundary.tsx` |
| 2 | `react/useCrashReporter.ts` | `src/hooks/useCrashReporter.ts` |
| 3 | `kotlin/NativeCrashGuard.kt` | `android/app/src/main/java/com/gamemappermind/app/security/NativeCrashGuard.kt` |
| 4 | `kotlin/PendingErrorBus.kt` | `android/app/src/main/java/com/gamemappermind/app/security/PendingErrorBus.kt` |
| 5 | `kotlin/GameMapperPluginReference.kt` | `android/app/src/main/java/com/gamemappermind/app/plugin/GameMapperPlugin.kt` *(reference — adaptasi ke plugin Anda)* |
| 6 | `kotlin/SecureWebViewConfig.kt` | `android/app/src/main/java/com/gamemappermind/app/security/SecureWebViewConfig.kt` |
| 7 | `webview/index.html` | `android/app/src/main/assets/public/index.html` *(merge ke file yang ada)* |
| 8 | `webview/csp.ts` | `src/security/csp.ts` |
| 9 | `kotlin/InputSanitizer.kt` | `android/app/src/main/java/com/gamemappermind/app/security/InputSanitizer.kt` |
| 10 | `kotlin/InputSanitizerTest.kt` | `android/app/src/test/java/com/gamemappermind/app/security/InputSanitizerTest.kt` |

---

## 4.1 — React ErrorBoundary + Global Error Reporter

### `ErrorBoundary.tsx`

Class component yang **menggantung di paling atas tree React**. Menangkap tiga sumber error:

1. **Render errors** — via `getDerivedStateFromError()` + `componentDidCatch()` (React API)
2. **Window errors** — `window.addEventListener('error', ...)` (script load failures, dll.)
3. **Unhandled promise rejections** — `window.addEventListener('unhandledrejection', ...)`

**Recovery UI (default fallback)** menampilkan:
- Icon ⚠️ + judul "GameMapperMind crashed"
- Pesan error + source + timestamp
- 4 tombol: Reload App, Reset Overlay, Copy Error, Show Details
- Pre-formatted stack trace yang bisa di-collapse

**Tombol "Reset Overlay"** melakukan:
- `delete window.injectConfig` (set oleh OverlayApp di FASE 1)
- Hapus semua `localStorage` key dengan prefix `gmm:` KECUALI:
  - `gmm:shizuku-permission` (preserve grant)
  - `gmm:shizuku-bound` (preserve binder state)

**Persistence:**
- 5 crash report terakhir disimpan di `localStorage['gmm:crash-reports']`
- API: `useRecentCrashes()` hook + `clearAllCrashes()`

**Safety:**
- Default fallback dibungkus try/catch — jika fallback juga crash, fallback ke plain HTML
- Reload cooldown 3 detik (anti double-tap)
- `safeResetOverlayState()` swallow semua error (localStorage bisa penuh/disabled)

### `useCrashReporter.ts`

Hook yang **subscribe ke native `app:error` events** (dipancarkan oleh `NativeCrashGuard` via `PendingErrorBus` → `GameMapperPlugin.notifyListeners()`).

**Payload sanitization:** Setiap event dari native divalidasi:
- `source` harus di `['native', 'plugin', 'pipeline', 'shizuku']`
- `message` di-cap 2KB
- `stack` di-cap 8KB
- `id` di-cap 128 chars (fallback ke generated UUID jika invalid)

**API:**
```typescript
const { latest, all, clear, reportJsError } = useCrashReporter();
// latest: NativeCrashReport | null
// all: NativeCrashReport[]  (max 20)
// clear(): void
// reportJsError(err, { source, method }): void  — push JS error ke pipeline yang sama
```

---

## 4.2 — Native Crash-Proof Wrapper

### `NativeCrashGuard.kt`

Singleton object dengan dua entry point:
- `guard(pluginName, methodName, call, block)` — untuk @PluginMethod
- `guardReturn(pluginName, methodName, defaultValue, block)` — untuk listener/callback

**Flow:**
1. Generate `correlationId` (UUID)
2. Eksekusi `block()` dalam try/catch
3. Jika throw → `classify(t)` → tentukan `(errorCode, recoverable)`
4. `sanitizeMessage(t)` — strip paths, JWT, hex strings
5. `sanitizeStack(t)` — cap 8KB, drop framework noise frames
6. `call.reject(message, code, errorJson)` — kirim structured error ke JS
7. `PendingErrorBus.publish(payload)` — emit event ke JS-side `useCrashReporter`

**Stable error codes** (NEVER reuse, hanya tambah baru):
- `INVALID_ARGUMENT` — caller passed bad input
- `PERMISSION_DENIED` — Shizuku not granted/bound
- `SERVICE_UNAVAILABLE` — Shizuku not running
- `INTERNAL_ERROR` — unexpected exception (NPE, dll.)
- `TIMEOUT` — operation exceeded deadline
- `NOT_FOUND` — resource missing
- `CONFLICT` — state conflict
- `RATE_LIMITED` — quota hit
- `NATIVE_CRASH` — uncaught Throwable fallback

**Throwable → ErrorCode mapping:**
- `IllegalArgumentException` / `IllegalStateException` → `INVALID_ARGUMENT` (recoverable)
- `SecurityException` → `PERMISSION_DENIED` (not recoverable)
- `NullPointerException` → `INTERNAL_ERROR` (not recoverable)
- `OutOfMemoryError` → `NATIVE_CRASH` (not recoverable)
- `TimeoutException` → `TIMEOUT` (recoverable)
- `FileNotFoundException` → `NOT_FOUND`
- `IOException` → `SERVICE_UNAVAILABLE`
- Heuristic: jika message mengandung "shizuku"+"permission" → `PERMISSION_DENIED`

### `PendingErrorBus.kt`

**Thread-safe queue** (ConcurrentLinkedQueue, cap 64) yang mendekopel NativeCrashGuard (yang bisa jalan di thread manapun — Shizuku binder thread, pipeline worker thread) dari GameMapperPlugin (yang harus panggil `notifyListeners()` di main thread).

**API:**
- `publish(payload: JSONObject)` — thread-safe, non-blocking, FIFO eviction saat full
- `drain(): List<JSONObject>` — ambil semua pending (dipanggil oleh plugin di main thread)
- `size(): Int` — advisory count
- `clear()` — untuk app restart

### `GameMapperPluginReference.kt`

Reference implementation yang menunjukkan pola penerapan di plugin nyata Anda. Setiap @PluginMethod dibungkus:

```kotlin
@PluginMethod
fun setProfile(call: PluginCall) {
    NativeCrashGuard.guard("GameMapper", "setProfile", call) {
        val jsonStr = InputSanitizer.requireJsonString(call, "profile")
        when (val result = ProfileValidator.parseAndValidate(jsonStr)) {
            is ProfileValidator.ValidationResult.Ok -> {
                currentProfile = result.profile
                pushProfileToPipeline(result.profile)
                call.resolve()
            }
            is ProfileValidator.ValidationResult.Err -> {
                call.reject("Invalid profile", NativeCrashGuard.ErrorCode.INVALID_ARGUMENT, ...)
            }
        }
    }
}
```

**Periodic error-bus poller:**
- `Handler(Looper.getMainLooper())` postDelayed setiap 1 detik
- Drain `PendingErrorBus.drain()` → `notifyListeners("app:error", payload)` per entry
- Dihentikan di `handleOnDestroy()`

**Plugin methods yang di-hardening:**
- `setProfile`, `clearProfile`
- `tap`, `swipe`, `releaseAllPointers`
- `updateSwipeTrigger`
- `getRecentErrors`, `clearErrorHistory`

---

## 4.3 — WebView Security Hardening

### `SecureWebViewConfig.kt`

Object yang apply semua hardening flags ke WebView:

| # | Setting | Value | Why |
|---|---------|-------|-----|
| 1 | `javaScriptEnabled` | true | Required by Capacitor |
| 2 | `domStorageEnabled` | true | Required by localStorage |
| 3 | `allowContentAccess` | **false** | Block `content://` URIs |
| 4 | `allowFileAccess` | **false** | Block `file://` URIs |
| 5 | `allowFileAccessFromFileURLs` | **false** | Block file:// XSS |
| 6 | `allowUniversalAccessFromFileURLs` | **false** | Block universal XSS |
| 7 | `mediaPlaybackRequiresUserGesture` | true | No autoplay |
| 8 | `setGeolocationEnabled` | **false** | No geolocation |
| 9 | `mixedContentMode` | `MIXED_CONTENT_NEVER_ALLOW` | No HTTPS→HTTP mix |
| 10 | `setSafeBrowsingEnabled` | true (API 26+) | Google Safe Browsing |
| 11 | `cacheMode` | `LOAD_NO_CACHE` | No stale cache |
| 12 | `setAppCacheEnabled` | false | Disable legacy appcache |
| 13 | `setWebContentsDebuggingEnabled` | isDebuggable only | No `chrome://inspect` di release |

### `SecureWebViewClient` (inner class)

Custom `WebViewClient` dengan **URL allowlist**:

```kotlin
ALLOWED_SCHEMES = setOf("https", "appasset")
ALLOWED_HOSTS = setOf("appassets.androidplatform.net", "localhost")
BLOCKED_FILE_EXTENSIONS = setOf(".apk", ".dex", ".so", ".jar", ".zip", ".rar", ".7z")
```

**`shouldOverrideUrlLoading()`:**
- Block scheme di luar allowlist
- Block host di luar allowlist (untuk https)
- Block file extension berbahaya

**`shouldInterceptRequest()`:**
- Delegate ke `WebViewAssetLoader` jika ada (untuk `app://` scheme)
- Block scheme di luar allowlist
- Block file extension berbahaya
- Return 403 + empty body untuk blocked requests

**`onReceivedSslError()`:**
- **SELALU `handler.cancel()`** — never `handler.proceed()` (SSL bypass = security hole)

### `index.html` (template)

Meta tags yang HARUS ada di `<head>` index.html Anda, **urutan penting**:

```html
<!-- CSP — must come BEFORE any external resource -->
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'self'; object-src 'none'; worker-src 'self'; manifest-src 'self'; font-src 'self' data:" />

<!-- IE legacy CSP -->
<meta http-equiv="X-Content-Security-Policy" content="..." />

<!-- Clickjacking -->
<meta http-equiv="X-Frame-Options" content="DENY" />

<!-- No referrer leak -->
<meta name="referrer" content="no-referrer" />

<!-- Disable unused browser features -->
<meta http-equiv="Permissions-Policy" content="geolocation=(), microphone=(), camera=(), speaker=(), vibrate=(), fullscreen=(), payment=(), usb=(), bluetooth=(), nfc=()" />

<!-- No indexing -->
<meta name="robots" content="noindex, nofollow, noarchive" />
```

**CSP policy detail:**
- `default-src 'self'` — same-origin only
- `script-src 'self'` — NO inline scripts, NO eval, NO external CDNs
- `style-src 'self' 'unsafe-inline'` — Capacitor injects inline styles
- `img-src 'self' data: blob:` — allow data URIs for icons
- `connect-src 'self'` — block XHR ke external APIs
- `frame-ancestors 'none'` — never iframe-embeddable
- `form-action 'none'` — no form submissions
- `base-uri 'self'` — no `<base>` tag hijack
- `object-src 'none'` — no Flash/Java
- `worker-src 'self'` — only same-origin workers

### `csp.ts`

Frontend helper yang **subscribe ke `securitypolicyviolation` event** browser. Setiap CSP violation:
- Log ke console (untuk `adb logcat` / `chrome://inspect`)
- Forward ke native side via Capacitor bridge (best-effort)

**API:**
- `installCspViolationReporter()` — install listener (idempotent, call once at app startup)
- `uninstallCspViolationReporter()` — cleanup
- `getCurrentCsp()` — return CSP string dari meta tag (untuk testing)

---

## 4.4 — Input Sanitization (JS Bridge Trust Boundary)

### `InputSanitizer.kt`

Centralized validator untuk **setiap nilai yang dibaca dari `PluginCall`**. Setiap method throw `IllegalArgumentException` pada input buruk → ditangkap `NativeCrashGuard` → mapped ke `INVALID_ARGUMENT`.

**Validasi yang ditegakkan:**

| Type | Rule |
|------|------|
| String | Max 65,536 chars; strip control chars (`\p{Cc}`, `\p{Cf}` kecuali `\t\n\r`); strip Unicode noncharacters (`\uFFFE`, `\uFFFF`); reject path traversal (`../`, `..%2f`, `file://`, `content://`) |
| Int | Range check `min..max` |
| Double | Range + reject NaN/Infinity |
| Boolean | Must be present (require) or default (optional) |
| JSON string | Max 256 KB; max nesting depth 32 (anti StackOverflow); reject if path traversal |
| JSONObject | Parse + validate as JSON |
| JSONArray (Int) | Max 256 items; per-element validator lambda |
| URL | Scheme allowlist (`https`, `appasset`); reject path traversal in URL path |
| Package name | Regex `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` |
| Pointer slot | 0..99 (matches `TouchInjector.POOL_SIZE`) |
| Button code | 0..1023 (Linux evdev `KEY_MAX`) |
| Duration ms | 16..5000 (cap to prevent pointer lock) |
| Swipe direction | 0..3 (up/down/left/right) |
| Fraction | 0.0..1.0 |

**Precompiled regex patterns** untuk hot-path performance:
- `CONTROL_CHARS_RE` — single Pattern.compile, reuse via matcher()
- `PATH_TRAVERSAL_RE` — detects `../`, `..%2f`, `file://`, `content://`
- `PACKAGE_NAME_RE` — Android package name pattern

### `InputSanitizerTest.kt`

JUnit test suite, **28 test cases** meng-cover:
- String: 7 kasus (happy, null, too long, control chars, path traversal, file://, content://)
- Int: 4 kasus (in-range, below, above, missing)
- Double: 3 kasus (NaN, Infinity, valid)
- Pointer slot: 2 kasus (0 & 99 accepted, 100 rejected)
- Package name: 3 kasus (valid, uppercase rejected, single-segment rejected)
- JSON: 5 kasus (valid, too deep, oversized, valid object, malformed)
- URL: 3 kasus (https, file://, javascript:)
- Int array: 3 kasus (valid, invalid element, oversized)

**Mockito** dipakai untuk mock `PluginCall` karena constructornya package-private.

---

## Deployment checklist

### Step 1 — Install dependencies

**Frontend (package.json):**
```bash
npm install @capacitor/app
# (Capacitor core sudah ada)
```

**Android (android/app/build.gradle):**
```gradle
dependencies {
    // Existing deps...
    implementation "androidx.webkit:webkit:1.12.1"
    testImplementation "junit:junit:4.13.2"
    testImplementation "org.mockito:mockito-core:5.14.2"
}
```

Aktifkan test sourceSet di `android/app/build.gradle`:
```gradle
android {
    // ...
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}
```

### Step 2 — Copy files

```bash
# Frontend
mkdir -p src/components src/hooks src/security
cp fase4/react/ErrorBoundary.tsx       src/components/
cp fase4/react/useCrashReporter.ts     src/hooks/
cp fase4/webview/csp.ts                src/security/

# Kotlin
mkdir -p android/app/src/main/java/com/gamemappermind/app/security
mkdir -p android/app/src/test/java/com/gamemappermind/app/security
cp fase4/kotlin/NativeCrashGuard.kt          android/app/src/main/java/com/gamemappermind/app/security/
cp fase4/kotlin/PendingErrorBus.kt           android/app/src/main/java/com/gamemappermind/app/security/
cp fase4/kotlin/SecureWebViewConfig.kt       android/app/src/main/java/com/gamemappermind/app/security/
cp fase4/kotlin/InputSanitizer.kt            android/app/src/main/java/com/gamemappermind/app/security/
cp fase4/kotlin/InputSanitizerTest.kt        android/app/src/test/java/com/gamemappermind/app/security/

# GameMapperPlugin reference (REVIEW sebelum overwrite!)
# JANGAN langsung overwrite plugin yang ada — gunakan sebagai referensi pola
cp fase4/kotlin/GameMapperPluginReference.kt android/app/src/main/java/com/gamemappermind/app/plugin/GameMapperPluginReference.kt
```

### Step 3 — Integrate ErrorBoundary ke app root

`src/main.tsx` (atau entry point Anda):
```tsx
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { installCspViolationReporter } from './security/csp';

// Install CSP violation listener ASAP
installCspViolationReporter();

const container = document.getElementById('root')!;
createRoot(container).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>
);
```

### Step 4 — Apply SecureWebViewConfig ke Capacitor WebView

Di `MainActivity.kt` (atau tempat Capacitor bridge diinisialisasi):

```kotlin
import com.gamemappermind.app.security.SecureWebViewConfig
import androidx.webkit.WebViewAssetLoader

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set up asset loader for app:// scheme
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        // Apply hardening after bridge is initialized
        bridge.webView.let { webView ->
            SecureWebViewConfig.applyTo(webView, assetLoader)
        }
    }
}
```

### Step 5 — Update index.html dengan CSP meta tags

Merge meta tags dari `fase4/webview/index.html` ke `android/app/src/main/assets/public/index.html` Anda yang sudah ada. **Pastikan CSP meta tag ada SEBELUM** `<link>` atau `<script>` manapun.

### Step 6 — Refactor plugin methods yang ada

Untuk setiap `@PluginMethod` di `GameMapperPlugin.kt` yang sudah ada, bungkus dengan pola:

```kotlin
@PluginMethod
fun yourMethod(call: PluginCall) {
    NativeCrashGuard.guard("GameMapper", "yourMethod", call) {
        // ... your logic, throw freely ...
        call.resolve()
    }
}
```

Untuk setiap `call.getString/getInt/getDouble` yang ada, ganti dengan `InputSanitizer.requireXxx()`:

```kotlin
// SEBELUM
val slot = call.getInt("slot", 0)
val x = call.getInt("x", 0)

// SESUDAH
val slot = InputSanitizer.requirePointerSlot(call, "slot")
val x = InputSanitizer.requirePixelCoord(call, "x", touchInjector.screenWidthPx)
```

### Step 7 — Build & test

```bash
# Frontend
npm run build

# Android unit tests
cd android
./gradlew :app:testDebugUnitTest --tests "*.InputSanitizerTest"

# Android build
./gradlew assembleDebug
```

### Step 8 — Verify hardening aktif

```bash
# Install APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Buka app, lalu periksa WebView settings:
adb shell dumpsys webviewupdate
adb logcat -s SecureWebViewConfig:I

# Cek CSP aktif dengan inspect:
adb forward tcp:9222 localabstract:webview_devtools_remote_*
# (di Chrome: chrome://inspect → tap "inspect" di bawah WebView)

# Di console, coba:
# fetch('https://evil.com')  → harus di-block oleh CSP connect-src
# fetch('file:///etc/hosts') → harus di-block oleh allowFileAccess=false
```

---

## Security checklist summary

| Threat | Mitigation |
|--------|------------|
| XSS in WebView | CSP `script-src 'self'` (no inline, no eval) |
| Filesystem leak | `allowFileAccess=false`, `allowContentAccess=false`, path traversal regex |
| SSL bypass | `onReceivedSslError` always `handler.cancel()` |
| CSP bypass attempt | `securitypolicyviolation` listener + native forward |
| Plugin crash takes down app | `NativeCrashGuard.guard()` wraps every @PluginMethod |
| Unhandled promise rejection | ErrorBoundary's `unhandledrejection` listener |
| Path traversal in JSON | `PATH_TRAVERSAL_RE` strips `../`, `file://`, `content://` |
| Control char injection | `CONTROL_CHARS_RE` strips `\p{Cc}\p{Cf}` |
| Unicode noncharacter | Strip `\uFFFE`, `\uFFFF`, surrogate pairs |
| Stack overflow via deep JSON | `MAX_JSON_NESTING_DEPTH = 32` |
| Memory bomb via huge string | `MAX_STRING_LEN = 65536`, `MAX_JSON_STRING_LEN = 256KB` |
| Plugin method never resolves | `guard()` logs warning if elapsed > 5s |
| Crash report leak PII | `sanitizeMessage` strips `/data/data/<pkg>` paths, JWTs, hex strings |
| Double-tap reload | 3-second cooldown on `reloadApp()` |
| Lost error context across threads | `PendingErrorBus` queue with 64-entry cap |
| Geolocation leak | `setGeolocationEnabled(false)` + Permissions-Policy `geolocation=()` |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW` |
| Webview debug in release | `setWebContentsDebuggingEnabled(isDebuggable)` |
| Iframe embedding | `frame-ancestors 'none'` + `X-Frame-Options: DENY` |

---

## Setelah FASE 4 selesai

Lanjut ke **FASE 5 — Testing & Verification**:
- Unit tests untuk InputPipelineWorker (tier transitions, CPU sampling)
- Unit tests untuk TouchInjector (LRU eviction, pointer pool exhaustion)
- Integration test untuk Shizuku offline simulation
- E2E test untuk profile load → pipeline → touch injection flow
- Test coverage report

**Konfirmasi jika siap lanjut ke FASE 5?**
