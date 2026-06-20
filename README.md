# GameMapperMind

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android, ditenagai oleh integrasi Shizuku.

## Perbaikan & Arsitektur Baru (v1.0.0-FINAL)
- **Capacitor Integration**: Menggunakan arsitektur hybrid modern dengan sinkronisasi natif Android komprehensif.
- **Express Backend Stabil**: Logging dan persistensi *state* (`state.json`) dengan Zod validation dan batasan muatan payload 1mb.
- **Gamepad API Otomatis**: Integrasi native hardware input melalui `MainActivity.kt` & React Hook loop berkinerja tinggi (`useGamepad.ts`) via requestAnimationFrame.
- **Visual Editor (WYSIWYG)**: Atur tata letak tombol, analog stick, dan area swipe secara langsung.
- **Injeksi Sentuhan Tanpa Root**: Menggunakan Shizuku untuk mensimulasikan sentuhan layar yang responsif dan aman tanpa perlu root.
- **Multi Profil**: Simpan berbagai konfigurasi kontrol.

## Build Instruksi
Untuk kompilasi penuh dan pembuatan APK:
```bash
npm run cap:build
cd android && ./gradlew assembleDebug
```
