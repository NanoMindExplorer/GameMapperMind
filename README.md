# GameMapperMind

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android, ditenagai oleh integrasi Shizuku.

## Perbaikan & Arsitektur Baru (v1.0.0-FINAL)
- **Capacitor Integration**: Menggunakan arsitektur hybrid modern dengan sinkronisasi natif Android komprehensif.
- **Express Backend Stabil**: Logging dan persistensi *state* (`state.json`) dengan Zod validation dan batasan muatan payload 1mb.
- **Gamepad API Otomatis**: Integrasi native hardware input melalui `MainActivity.kt` & React Hook loop berkinerja tinggi (`useGamepad.ts`) via requestAnimationFrame.
- **Visual Editor (WYSIWYG)**: Atur tata letak tombol, analog stick, dan area swipe secara langsung.
- **Injeksi Sentuhan Tanpa Root**: Menggunakan Shizuku untuk mensimulasikan sentuhan layar yang responsif dan aman tanpa perlu root.
- **Multi Profil**: Simpan berbagai konfigurasi kontrol (3 profile default: Genshin Impact, PUBG Mobile, Mobile Legends).

## Prasyarat

Sebelum build, pastikan environment Anda memenuhi:
- **Node.js** >= 18.x (recommended 20.x LTS)
- **JDK** >= 17 (untuk Gradle 8.x)
- **Android SDK** dengan:
  - `compileSdk` 35 (lihat `android/variables.gradle`)
  - `minSdk` 23 (Android 6.0 Marshmallow)
  - Build Tools 35.0.0
  - Platform Tools terbaru
- **Shizuku app** terinstall di device target (download dari Play Store atau https://shizuku.rikka.app/)
- **Gamepad fisik** yang mendukung Bluetooth atau USB OTG (Xbox, DualShock, 8BitDo, dll)

## Build Instruksi

### 1. Install dependencies
```bash
npm install
```

### 2. Build web assets dan sync ke Android
```bash
npm run cap:build
```
Ini akan menjalankan `vite build` dan `cap sync android`.

### 3. Build APK debug
```bash
cd android
./gradlew assembleDebug
```
APK output: `android/app/build/outputs/apk/debug/app-debug.apk`

### 4. (Optional) Build APK release
Untuk release, set environment variables untuk signing:
```bash
export KEYSTORE_FILE=/path/to/keystore.jks
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password
cd android
./gradlew assembleRelease
```

### 5. Setup environment untuk server development (opsional)
Jika menjalankan server development (`npm run dev`), set API key:
```bash
export VITE_NEXION_API_KEY=$(openssl rand -hex 32)
```
Server akan refuse to start jika env var tidak set atau kurang dari 32 karakter.

## Cara Pakai

1. Install APK di device Android
2. Install dan aktifkan Shizuku (ikuti instruksi di app Shizuku)
3. Buka GameMapperMind, berikan permission Shizuku
4. Pilih profile game (Genshin Impact, PUBG Mobile, atau Mobile Legends)
5. Sesuaikan mapping tombol di panel WYSIWYG Overlay Canvas
6. Aktifkan overlay (floating button)
7. Buka game target, tekan tombol gamepad untuk bermain
8. Tekan Kill Switch untuk menghentikan semua input darurat

## Troubleshooting

- **Gamepad tidak terdeteksi**: Pastikan gamepad terhubung via Bluetooth/USB. Cek di Settings > Bluetooth atau Settings > Connected devices. Buka panel Sensor & Input Diagnostics untuk verifikasi.
- **Sentuhan tidak ter-inject**: Pastikan Shizuku running dan permission granted. Restart Shizuku jika perlu.
- **Overlay tidak muncul**: Pastikan permission SYSTEM_ALERT_WINDOW (Display over other apps) granted.
- **Input lag saat bermain**: Aktifkan native mapping service (startNativeMapping) untuk low-latency mode.
