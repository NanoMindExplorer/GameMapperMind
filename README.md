# GameMapperMind

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android, ditenagai oleh integrasi Shizuku.

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
