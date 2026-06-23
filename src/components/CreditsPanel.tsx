import React from 'react';
import { Send, Instagram, Twitter, MessageSquare, Youtube, Heart, Code, BookOpen, Smartphone, Laptop, CheckSquare, Square, ChevronRight, ChevronDown, Check, Settings } from 'lucide-react';
import { useState } from 'react';

const SHIZUKU_STEPS = [
  {
    title: "1. Aktifkan Opsi Pengembang (Developer Options)",
    short: "Buka pengaturan sistem Android Anda untuk mengakses opsi developer.",
    details: [
      "Buka aplikasi ⚙️ Setelan / Pengaturan (Settings) pada HP Android Anda.",
      "Gulir ke bawah, pilih Tentang Telepon (About Phone).",
      "Cari 'Nomor Versi' (Build Number) atau versi OS/ROM Anda (misalnya Versi MIUI / HyperOS).",
      "Ketuk cepat Nomor Versi tersebut sebanyak 7 kali berturut-turut hingga muncul balon teks berisi informasi 'Anda sekarang adalah pengembang!'."
    ],
    badge: "Prasyarat Awal",
    actionButton: "Buka Opsi Pengembang",
    actionMessage: "[SYSTEM] Intent: android.settings.APPLICATION_DEVELOPMENT_SETTINGS dipanggil."
  },
  {
    title: "2. Aktifkan Proses Debug USB & Nirkabel",
    short: "Aktifkan saluran transmisi perintah dan sentuhan virtual.",
    details: [
      "Pergi ke submenu Sistem -> Opsi Pengembang (Developer Options).",
      "Aktifkan opsi 'Proses Debug USB' (USB Debugging).",
      "Aktifkan opsi 'Proses Debug Nirkabel' (Wireless Debugging). Hubungkan perangkat ke Wi-Fi terlebih dahulu untuk mengaktifkan ini.",
      "⚠️ SANGAT PENTING (HP Xiaomi, POCO, Oppo, Vivo, Realme): Cari dan aktifkan opsi 'Proses Debug USB (Setelan Keamanan)' / 'USB Debugging (Security Settings)' agar simulasi sentuhan tombol terdaftar secara lancar."
    ],
    badge: "Setelan Sistem"
  },
  {
    title: "3. Jalankan Aplikasi & Sinkronkan Shizuku",
    short: "Hubungkan Shizuku ke antrean driver HP menggunakan kode pairing.",
    details: [
      "Instal & buka aplikasi Shizuku (bisa diunduh via Google Play Store / Github).",
      "Lakukan Penyandingan: Ketuk 'Sandingkan / Pairing', lalu ketuk opsi tersebut di Opsi Pengembang -> Wireless Debugging.",
      "Pilih 'Sandingkan Perangkat dengan Kode Penyandingan'. Catat kode 6-digit nirkabel yang tertera di layar.",
      "Masukkan kode tersebut pada baris notifikasi Shizuku yang muncul untuk menyelesaikan pairing.",
      "Berhasil pairing? Kembali ke beranda Shizuku dan klik 'Mulai / Start' untuk mengaktifkannya!"
    ],
    badge: "Koneksi Shizuku"
  },
  {
    title: "4. Izinkan Otorisasi Layanan Nexion",
    short: "Berikan izin binder IPC aman ke aplikasi Nexion ini.",
    details: [
      "Ketuk tombol 'Authorize Shizuku AIDL Bindings' di panel atas.",
      "Jendela pop-up bawaan Shizuku akan langsung muncul di HP Anda.",
      "Silakan ketuk opsi 'Izinkan Selalu' (Allow Always). Otorisasi akan sukses seketika!"
    ],
    badge: "Izin Aplikasi"
  },
  {
    title: "5. Izinkan Tampilan Di Atas Aplikasi Lain & Optimasi Baterai",
    short: "Izinkan rendering overlay di atas game.",
    details: [
      "Buka Pengaturan HP -> Aplikasi -> Kelola Aplikasi -> Cari 'Nexion' -> Aktifkan 'Tampilkan di atas aplikasi lain' (Display over other apps / Draw over other apps) agar tombol overlay HUD bisa muncul mengambang saat Anda bermain game.",
      "🔋 MATIKAN PENGHEMAT BATERAI: Anda dapat menggunakan tombol 'Ignore Battery Optimizations' di panel atas, atau rubah secara manual di pengaturan perangkat.",
      "Langkah ini sangat krusial agar overlay Anda tidak dihentikan (force close) atau freeze oleh sistem saat bermain game berat."
    ],
    badge: "Overlay & Akses",
    actionButton: "Buka Pengaturan Aplikasi & Izin",
    actionMessage: "[SYSTEM] Intent: android.settings.APPLICATION_DETAILS_SETTINGS dipanggil. Buka menu Overlay."
  },
  {
    title: "6. Boot Daemon Shuttle & Mulai Bermain!",
    short: "Aktifkan engine pemeta virtual dengan respons instan.",
    details: [
      "Klik tombol 'BOOT NEXION SHUTTLE DAEMON' di atas.",
      "Secara instan, Anda akan melihat terminal STDOUT di sebelah kanan mencetak kode verifikasi.",
      "Status akan berubah menjadi 'CORE DAEMON ACTIVE'. Berhasil! Pasang Gamepad Anda, buka tab 'Gamepad Tester' atau 'Overlay Editor' untuk merancang layout tombol favorit!"
    ],
    badge: "Finishing"
  }
];

const DESKTOP_STEPS = [
  {
    title: "1. Aktifkan Proses Debug USB",
    short: "Berikan komputer wewenang penuh untuk mengirim perintah data input.",
    details: [
      "Aktifkan Opsi Pengembang terlebih dahulu di HP Anda (Ketuk 'Nomor Versi' 7 kali di Tentang Telepon).",
      "Masuk ke Opsi Pengembang, lalu hidupkan sakelar 'Proses Debug USB'.",
      "💡 Bagi pengguna Xiaomi/POCO/Oppo: Hidupkan pula 'Proses Debug USB (Setelan Keamanan)' agar touchpad virtual berjalan."
    ],
    badge: "Opsi Pengembang",
    actionButton: "Buka Opsi Pengembang",
    actionMessage: "[SYSTEM] Intent: android.settings.APPLICATION_DEVELOPMENT_SETTINGS dipanggil."
  },
  {
    title: "2. Sambungkan HP ke PC/Laptop",
    short: "Gunakan kabel USB data orisinal dengan sambungan solid.",
    details: [
      "Hubungkan HP ke komputer dengan kabel USB yang bisa mentransfer berkas.",
      "Pilih opsi 'Transfer File' (MTP) pada popup koneksi USB di HP.",
      "Saat HP menampilkan popup 'Izinkan Debugging USB dari PC ini?', centang 'Selalu izinkan' lalu ketuk Oke."
    ],
    badge: "Konektivitas USB"
  },
  {
    title: "3. Jalankan Berkas Companion PC",
    short: "Jalankan skrip pembantu untuk menginjeksi core driver daemon.",
    details: [
      "Unduh zip Nexion Desktop Companion di PC Anda dan ekstrak arsip tersebut.",
      "Buka folder hasil ekstrak, jalankan file pembantu instalasi:",
      "• Mac/Linux: Jalankan terminal lalu ketik perintah './start.sh'",
      "• Windows: Klik ganda file 'start.bat' untuk membukanya langsung.",
      "Skrip pendamping akan otomatis menyuntikkan driver mapper ke memory heap lokal HP."
    ],
    badge: "Script Companion"
  },
  {
    title: "4. Izinkan Tampilan Di Atas Aplikasi Lain",
    short: "Izinkan aplikasi overlay controller & mendeteksi hardware.",
    details: [
      "Pergi ke Setelan HP -> Aplikasi -> Kelola Aplikasi -> Pilih 'Nexion' -> Nyalakan izin 'Tampilkan di Atas Aplikasi Lain' (Draw Over Other Apps / Display over details).",
      "Izin overlay ini diperlukan agar panel tombol konfigurasi map bisa melayang di atas layar game Anda untuk penempatan langsung.",
      "🔋 MATIKAN PENGHEMAT BATERAI: Anda dapat menggunakan tombol 'Ignore Battery Optimizations' di panel atas, atau rubah secara manual di pengaturan perangkat. Ini mencegah overlay keluar sendiri atau ngelag di tengah permainan."
    ],
    badge: "Overlay & Akses",
    actionButton: "Buka Pengaturan Aplikasi & Izin",
    actionMessage: "[SYSTEM] Intent: android.settings.APPLICATION_DETAILS_SETTINGS dipanggil. Buka menu Overlay."
  },
  {
    title: "5. Aktifkan Driver & Lakukan Kalibrasi",
    short: "Hubungkan terminal aplikasi dan nikmati pengalaman nol-latensi.",
    details: [
      "Ketuk tombol biru 'INITIALIZE VIA DESKTOP ADAPTER' di atas.",
      "Jika terminal logs mencetak pesan sukses, mapping aktif sepenuhnya!",
      "Selamat bermain! Sesuaikan sensor gyro, arah swipe, dan area analog sesukamu."
    ],
    badge: "Boot Up"
  }
];

interface CreditsPanelProps {
  onLogMessage?: (msg: string) => void;
}

export default function CreditsPanel({ onLogMessage }: CreditsPanelProps) {

  const [activeTab, setActiveTab] = useState<'shizuku' | 'desktop'>('shizuku');
  const [expandedShizukuStep, setExpandedShizukuStep] = useState<number | null>(0);
  const [expandedDesktopStep, setExpandedDesktopStep] = useState<number | null>(0);
  const [shizukuChecklist, setShizukuChecklist] = useState<boolean[]>([false, false, false, false, false, false]);
  const [desktopChecklist, setDesktopChecklist] = useState<boolean[]>([false, false, false, false, false]);

  const handleToggleExpand = (stepIdx: number) => {
    if (activeTab === 'shizuku') setExpandedShizukuStep(prev => prev === stepIdx ? null : stepIdx);
    else setExpandedDesktopStep(prev => prev === stepIdx ? null : stepIdx);
  };

  const handleToggleCheck = (e: React.MouseEvent) => {
    e.stopPropagation();
    const currExpanded = activeTab === 'shizuku' ? expandedShizukuStep : expandedDesktopStep;
    if (currExpanded === null) return;
    
    if (activeTab === 'shizuku') {
      const arr = [...shizukuChecklist];
      arr[currExpanded] = !arr[currExpanded];
      setShizukuChecklist(arr);
      if (arr[currExpanded] && currExpanded < SHIZUKU_STEPS.length - 1) setExpandedShizukuStep(currExpanded + 1);
    } else {
      const arr = [...desktopChecklist];
      arr[currExpanded] = !arr[currExpanded];
      setDesktopChecklist(arr);
      if (arr[currExpanded] && currExpanded < DESKTOP_STEPS.length - 1) setExpandedDesktopStep(currExpanded + 1);
    }
  };

  const [copiedAddress, setCopiedAddress] = useState<string | null>(null);

  const handleCopy = (address: string, label: string) => {
    navigator.clipboard.writeText(address);
    setCopiedAddress(label);
    setTimeout(() => setCopiedAddress(null), 2000);
  };

  const socials = [
    {
      name: "Telegram Chat Room",
      url: "https://t.me/oxnlyfams",
      icon: <Send className="w-5 h-5" />,
      color: "bg-blue-500/10 text-blue-400 border-blue-500/20 hover:border-blue-500/50 hover:bg-blue-500/20"
    },
    {
      name: "Instagram",
      url: "https://www.instagram.com/low_and.high?igsh=dXUyMjN1anp5Ymc5",
      icon: <Instagram className="w-5 h-5" />,
      color: "bg-pink-500/10 text-pink-400 border-pink-500/20 hover:border-pink-500/50 hover:bg-pink-500/20"
    },
    {
      name: "X (Twitter)",
      url: "https://x.com/Deadmouse_jpeg",
      icon: <Twitter className="w-5 h-5" />,
      color: "bg-slate-800/50 text-slate-300 border-slate-700 hover:border-slate-500 hover:bg-slate-800"
    },
    {
      name: "Discord",
      url: "https://discord.gg/CrG6Hxm8XZ",
      icon: <MessageSquare className="w-5 h-5" />,
      color: "bg-indigo-500/10 text-indigo-400 border-indigo-500/20 hover:border-indigo-500/50 hover:bg-indigo-500/20"
    },
    {
      name: "YouTube",
      url: "https://www.youtube.com/@Bakayaro_0",
      icon: <Youtube className="w-5 h-5" />,
      color: "bg-red-500/10 text-red-400 border-red-500/20 hover:border-red-500/50 hover:bg-red-500/20"
    }
  ];

  return (
    <div className="flex-1 p-6 overflow-y-auto custom-scrollbar bg-slate-950">
      <div className="max-w-4xl mx-auto space-y-8">
        {/* Header Section */}
        <div className="text-center space-y-4 py-8">
          <div className="inline-flex items-center justify-center p-4 bg-indigo-500/10 rounded-full mb-4 ring-1 ring-indigo-500/30">
            <Heart className="w-8 h-8 text-indigo-400" />
          </div>
          <h1 className="text-3xl font-bold font-sans tracking-tight text-white">
            Connect & Support
          </h1>
          <p className="text-slate-400 max-w-lg mx-auto leading-relaxed">
            Thank you for using Game Mapper! Join our community, report issues, or just hang out with us on our social platforms.
          </p>
        </div>

        {/* Social Links Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {socials.map((social) => (
            <a
              key={social.name}
              href={social.url}
              target="_blank"
              rel="noopener noreferrer"
              className={`flex items-center gap-4 p-5 rounded-xl border transition-all duration-300 group ${social.color}`}
            >
              <div className="p-3 bg-slate-950/50 rounded-lg group-hover:scale-110 transition-transform">
                {social.icon}
              </div>
              <div className="flex-1">
                <h3 className="font-semibold">{social.name}</h3>
                <p className="text-xs opacity-70 mt-1 truncate max-w-[200px] sm:max-w-[250px]">
                  {social.url}
                </p>
              </div>
            </a>
          ))}
        </div>

        
        {/* Interactive Activation Guide (Panduan Aktifasi) */}
        <div className="bg-slate-950/60 rounded-xl border border-indigo-950/60 p-4 space-y-4">
          {/* Header */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="p-1 px-1.5 bg-indigo-500/10 text-indigo-400 rounded-md border border-indigo-500/20 text-xs font-bold">
                PRO
              </div>
              <h3 className="text-sm font-semibold text-slate-100 flex items-center gap-1.5 font-sans">
                <BookOpen className="w-4 h-4 text-indigo-400" />
                Materi Panduan Aktifasi Interaktif
              </h3>
            </div>
            <span className="text-[10px] text-slate-500 bg-slate-900 border border-slate-800 px-2 py-0.5 rounded font-mono">
              {activeTab === 'shizuku' ? 'Shizuku Wizard' : 'ADB Wizard'}
            </span>
          </div>

          {/* Tab Switcher */}
          <div className="flex gap-2">
            <button
              onClick={() => setActiveTab('shizuku')}
              className={`flex-1 px-3 py-2 rounded-lg text-xs font-semibold flex items-center justify-center gap-1.5 transition-all ${
                activeTab === 'shizuku'
                  ? 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/40'
                  : 'bg-slate-900/50 text-slate-400 border border-slate-800 hover:bg-slate-900'
              }`}
            >
              <Smartphone className="w-3.5 h-3.5" />
              Shizuku (Wireless)
            </button>
            <button
              onClick={() => setActiveTab('desktop')}
              className={`flex-1 px-3 py-2 rounded-lg text-xs font-semibold flex items-center justify-center gap-1.5 transition-all ${
                activeTab === 'desktop'
                  ? 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/40'
                  : 'bg-slate-900/50 text-slate-400 border border-slate-800 hover:bg-slate-900'
              }`}
            >
              <Laptop className="w-3.5 h-3.5" />
              Desktop (USB ADB)
            </button>
          </div>

          {/* Progress Checklist bar */}
          <div>
            <div className="flex justify-between items-center mb-1.5">
              <span className="text-[10px] font-semibold text-slate-400 uppercase tracking-wider">Progress Checklist Aktifasi</span>
              <span className="text-xs font-bold font-mono text-indigo-400">
                {activeTab === 'shizuku'
                  ? `${shizukuChecklist.filter(Boolean).length}/${SHIZUKU_STEPS.length} Langkah`
                  : `${desktopChecklist.filter(Boolean).length}/${DESKTOP_STEPS.length} Langkah`}
                {` `}(
                {activeTab === 'shizuku'
                  ? Math.round((shizukuChecklist.filter(Boolean).length / SHIZUKU_STEPS.length) * 100)
                  : Math.round((desktopChecklist.filter(Boolean).length / DESKTOP_STEPS.length) * 100)}%)
              </span>
            </div>
            <div className="w-full bg-slate-900 h-1.5 rounded-full overflow-hidden border border-slate-800/60">
              <div
                className="bg-gradient-to-r from-indigo-500 to-purple-500 h-full transition-all duration-500 rounded-full"
                style={{
                  width: activeTab === 'shizuku'
                    ? `${(shizukuChecklist.filter(Boolean).length / SHIZUKU_STEPS.length) * 100}%`
                    : `${(desktopChecklist.filter(Boolean).length / DESKTOP_STEPS.length) * 100}%`
                }}
              />
            </div>
          </div>

          {/* Step list Container */}
          <div className="space-y-2.5">
            {(activeTab === 'shizuku' ? SHIZUKU_STEPS : DESKTOP_STEPS).map((step, idx) => {
              const isShizuku = activeTab === 'shizuku';
              const isExpanded = isShizuku ? expandedShizukuStep === idx : expandedDesktopStep === idx;
              const isCompleted = isShizuku ? shizukuChecklist[idx] : desktopChecklist[idx];

              return (
                <div
                  key={idx}
                  className={`rounded-lg border transition-all overflow-hidden ${
                    isExpanded
                      ? 'border-indigo-500/40 bg-indigo-950/10 shadow-[0_4px_16px_rgba(99,102,241,0.05)]'
                      : 'border-slate-800/80 bg-slate-900/30 hover:border-slate-800 hover:bg-slate-900/50'
                  }`}
                >
                  {/* Header bar of step */}
                  <div
                    onClick={() => handleToggleExpand(idx)}
                    className="px-3.5 py-3 flex items-center justify-between cursor-pointer select-none"
                  >
                    <div className="flex items-center gap-3 min-w-0 flex-1">
                      {/* Checkbox */}
                      <button
                        type="button"
                        onClick={handleToggleCheck}
                        className="focus:outline-none flex-shrink-0"
                        title="Tandai Selesai"
                      >
                        {isCompleted ? (
                          <CheckSquare className="w-5 h-5 text-emerald-400" />
                        ) : (
                          <Square className="w-5 h-5 text-slate-500 hover:text-slate-300" />
                        )}
                      </button>

                      {/* Step info */}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="text-[10px] font-bold text-indigo-400 bg-indigo-500/10 px-1.5 py-0.5 rounded border border-indigo-500/20">
                            {step.badge}
                          </span>
                          {isCompleted && (
                            <Check className="w-3 h-3 text-emerald-400" />
                          )}
                        </div>
                        <h4 className={`text-xs font-semibold truncate ${isCompleted ? 'text-slate-500 line-through' : 'text-slate-200'}`}>
                          {step.title}
                        </h4>
                        <p className="text-[10px] text-slate-500 truncate">{step.short}</p>
                      </div>
                    </div>

                    {/* Expand icon */}
                    {isExpanded ? (
                      <ChevronDown className="w-4 h-4 text-slate-400 flex-shrink-0" />
                    ) : (
                      <ChevronRight className="w-4 h-4 text-slate-400 flex-shrink-0" />
                    )}
                  </div>

                  {/* Expanded content */}
                  {isExpanded && (
                    <div className="px-3.5 pb-3.5 space-y-2 border-t border-slate-800/50 pt-3">
                      <ul className="space-y-1.5">
                        {step.details.map((detail, dIdx) => (
                          <li key={dIdx} className="text-[11px] text-slate-400 leading-relaxed flex gap-2">
                            <span className="text-indigo-400 flex-shrink-0">▸</span>
                            <span>{detail}</span>
                          </li>
                        ))}
                      </ul>
                      {step.actionButton && step.actionMessage && (
                        <button
                          onClick={() => onLogMessage?.(step.actionMessage!)}
                          className="w-full mt-2 px-3 py-2 bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-300 text-xs font-semibold rounded-lg border border-indigo-500/30 transition-colors flex items-center justify-center gap-1.5"
                        >
                          <Settings className="w-3.5 h-3.5" />
                          {step.actionButton}
                        </button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Donation Section - Support the Creator */}
        <div className="bg-gradient-to-br from-amber-950/20 to-orange-950/10 rounded-xl border border-amber-900/40 p-6 space-y-4">
          <div className="text-center space-y-2">
            <div className="inline-flex items-center justify-center p-3 bg-amber-500/10 rounded-full mb-2 ring-1 ring-amber-500/30">
              <Heart className="w-6 h-6 text-amber-400" />
            </div>
            <h3 className="text-xl font-bold text-white">Support the Creator</h3>
            <p className="text-sm text-slate-400 max-w-md mx-auto">
              Jika aplikasi ini bermanfaat untuk Anda, dukung kreator dengan donasi crypto. Setiap kontribusi sangat berarti untuk pengembangan lebih lanjut.
            </p>
          </div>

          {/* Crypto Address Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {/* Bitcoin */}
            <div className="bg-slate-950/50 rounded-lg border border-amber-900/30 p-4 space-y-2 hover:border-amber-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-amber-500/10 rounded-full flex items-center justify-center text-amber-400 font-bold text-xs">₿</div>
                  <span className="font-semibold text-amber-300">Bitcoin (BTC)</span>
                </div>
                <button
                  onClick={() => handleCopy('TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt', 'BTC')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'BTC' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt
              </code>
            </div>

            {/* EVM */}
            <div className="bg-slate-950/50 rounded-lg border border-blue-900/30 p-4 space-y-2 hover:border-blue-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-blue-500/10 rounded-full flex items-center justify-center text-blue-400 font-bold text-xs">Ξ</div>
                  <span className="font-semibold text-blue-300">EVM (ETH/BSC/Polygon)</span>
                </div>
                <button
                  onClick={() => handleCopy('0x96e49c673252bb0a2253418417cf1db000fec6ef', 'EVM')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'EVM' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                0x96e49c673252bb0a2253418417cf1db000fec6ef
              </code>
            </div>

            {/* Solana */}
            <div className="bg-slate-950/50 rounded-lg border border-purple-900/30 p-4 space-y-2 hover:border-purple-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-purple-500/10 rounded-full flex items-center justify-center text-purple-400 font-bold text-xs">◎</div>
                  <span className="font-semibold text-purple-300">Solana (SOL)</span>
                </div>
                <button
                  onClick={() => handleCopy('4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR', 'SOL')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'SOL' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR
              </code>
            </div>

            {/* Tron */}
            <div className="bg-slate-950/50 rounded-lg border border-red-900/30 p-4 space-y-2 hover:border-red-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-red-500/10 rounded-full flex items-center justify-center text-red-400 font-bold text-xs">T</div>
                  <span className="font-semibold text-red-300">Tron (TRX)</span>
                </div>
                <button
                  onClick={() => handleCopy('TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt', 'TRX')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'TRX' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt
              </code>
            </div>
          </div>

          {/* Disclaimer */}
          <p className="text-[10px] text-slate-500 text-center mt-3">
            Always double-check the address before sending. Crypto transactions are irreversible.
          </p>
        </div>


        {/* Footer info */}
        <div className="mt-12 p-6 rounded-xl border border-slate-800 bg-slate-900/50 text-center">
          <Code className="w-6 h-6 text-slate-500 mx-auto mb-3" />
          <h4 className="text-sm font-medium text-slate-300">Open Source & Community Driven</h4>
          <p className="text-xs text-slate-500 mt-2">
            Build with passion for mobile gamers.
          </p>
        </div>
      </div>
    </div>
  );
}
