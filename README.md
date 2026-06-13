# Gamepad Mapper (Capacitor Android Project)

Aplikasi Gamepad Mapper modern, interaktif, dan penuh fitur yang dibangun menggunakan **React**, **Vite**, **Tailwind CSS**, dan diintegrasikan ke platform Android menggunakan **Capacitor**.

Aplikasi ini dapat membantu para gamer mengonfigurasi gamepad, melakukan pemetaan tombol virtual overlay, membuat makro untuk otomasi tombol, serta diintegrasikan dengan Shizuku untuk akses tingkat sistem yang aman tanpa memerlukan root penuh.

---

## 🎮 Fitur Utama

- **Gamepad Tester**: Menguji input tombol, analog (joystick), dan D-Pad Anda secara real-time dengan representasi visual yang akurat.
- **Macro Engine**: Mengonfigurasi dan merekam urutan tombol (macro) kompleks untuk eksekusi aksi berulang secara cepat.
- **Overlay WYSIWYG**: Desain penempatan tombol virtual langsung pada layar (What You See Is What You Get) agar pas dengan kontrol game Anda.
- **Shizuku Integration Panel**: Panduan langkah-demi-langkah dan kontrol koneksi untuk mengaktifkan pemetaan tombol canggih menggunakan API Shizuku.
- **AI Tunnel Panel**: Asisten optimasi performa pintar berbasis AI yang dirancang untuk memperkecil ketertelambatan input (latency) gamepad Anda.
- **Game Selector**: Mengelola dan mengaitkan profil pemetaan tombol yang berbeda-beda untuk setiap game favorit secara otomatis.

---

## 🛠️ Pengembangan Lokal (Development)

### Prasyarat
- **Node.js** (Versi 20 atau lebih baru disarankan)
- **NPM** (Bawaan dari Node.js)

### Langkah-langkah Menjalankan Aplikasi Web:
1. **Pasang (Install) Dependensi**:
   ```bash
   npm install
   ```
2. **Jalankan Server Pengembang**:
   ```bash
   npm run dev
   ```
3. Buka web browser Anda di alamat `http://localhost:3000`.

---

## 🤖 Cara Membangun APK Android via GitHub Actions

Proyek ini telah dikonfigurasi dengan alur kerja otomatis (**GitHub Actions**) kelas produksi. Setiap kali Anda melakukan **Push** atau **Pull Request** ke branch `main`, GitHub akan otomatis mengompilasi kode dan menyediakan berkas APK siap pasang.

### Langkah demi Langkah Mendownload APK:
1. **Push perubahan** terbaru ke branch `main` repositori GitHub Anda.
2. Pergi ke tab **Actions** di halaman repositori GitHub Anda.
3. Klik pada jalannya workflow terbaru yang bernama **Build Android APK (Capacitor)**.
4. Tunggu hingga proses build selesai (biasanya berkisar 3-5 menit).
5. Setelah selesai (berwarna hijau), scroll ke bawah halaman tersebut ke bagian **Artifacts**.
6. Klik pada berkas bernama **`game-mapper-debug-apk`**.
7. Ekstrak file `.zip` yang diunduh untuk mendapatkan file `.apk`, lalu instal di perangkat Android Anda!

---

## 🔧 Penanganan Masalah & Optimasi Build CI

Dalam alur build CI (Continuous Integration), lingkungan build telah dikonfigurasi secara optimal untuk menangani potensi kendala Gradle modern:

- **JDK 21 Runtime**: Versi JDK telah ditingkatkan ke Zulu **JDK 21** untuk keselarasan dengan dekompilasi Kotlin dan Gradle Compiler 8.+ terbaru.
- **Pembersihan Modul Duplikat**: Workflow ini menyertakan skrip injeksi otomatis pada build-time untuk mengecualikan dependensi usang Kotlin (`kotlin-stdlib-jdk7` dan `kotlin-stdlib-jdk8`) yang sering memicu konflik `checkDebugDuplicateClasses` pada proyek Android hibrida modern.
- **Izin Eksekusi Gradle**: Script `.github/workflows/build-apk.yml` secara dinamis memberikan izin eksekusi (`chmod +x gradlew`) sebelum memulai kompilasi Android guna menghindari kendala izin akses repositori.
