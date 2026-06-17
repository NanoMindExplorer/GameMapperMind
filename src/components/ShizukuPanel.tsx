/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { 
  Cpu, Zap, RefreshCw, Layers, ShieldCheck, CheckCircle2, XCircle, 
  Settings, Play, Terminal, HelpCircle, Laptop, Smartphone, AlertTriangle,
  BookOpen, ChevronRight, ChevronDown, CheckSquare, Square, Sparkles, Check, Battery
} from 'lucide-react';
import { ShizukuState } from '../types';
import { useShizuku } from '../hooks/useShizuku';

interface ShizukuPanelProps {
  shizukuState: ShizukuState;
  setShizukuState: React.Dispatch<React.SetStateAction<ShizukuState>>;
  onLogMessage: (msg: string) => void;
}

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

export default function ShizukuPanel({ shizukuState, setShizukuState, onLogMessage }: ShizukuPanelProps) {
  const { requestShizukuPermission: nativeRequestPerm, executeShizukuCommand, startDaemon, stopDaemon, checkBattery, requestBatteryIgnore } = useShizuku();
  const [activeTab, setActiveTab] = React.useState<'shizuku' | 'desktop'>('shizuku');
  const [isLoading, setIsLoading] = React.useState(false);
  const [shizukuPermission, setShizukuPermission] = React.useState<'GRANTED' | 'DENIED' | 'PROMPT'>('PROMPT');
  const [isBatteryIgnored, setIsBatteryIgnored] = React.useState(true);
  const [customLog, setCustomLog] = React.useState('');

  // Interactive guide states
  const [expandedShizukuStep, setExpandedShizukuStep] = React.useState<number | null>(0);
  const [expandedDesktopStep, setExpandedDesktopStep] = React.useState<number | null>(0);
  
  const [shizukuChecklist, setShizukuChecklist] = React.useState<boolean[]>([false, false, false, false, false, false]);
  const [desktopChecklist, setDesktopChecklist] = React.useState<boolean[]>([false, false, false, false, false]);

  React.useEffect(() => {
    checkBattery().then(ignored => {
      if (ignored !== undefined) setIsBatteryIgnored(ignored);
    });
  }, []);

  const triggerAction = async (action: 'start' | 'stop' | 'toggle_mode', mode?: 'shizuku' | 'desktop') => {
    setIsLoading(true);
    try {
      if (action === 'start') {
        const res = await startDaemon();
        if (res) {
           onLogMessage(`[sh] Daemon started successfully.`);
        } else {
           onLogMessage(`[sh ERROR] Failed to start Daemon. (Pastikan native plugin terpasang / simulator aktif)`);
        }
      } else if (action === 'stop') {
        const res = await stopDaemon();
        if (res) {
           onLogMessage(`[sh] Nexion Shuttle Daemon Terminated.`);
        }
      }

      setTimeout(() => {
        if (action === 'toggle_mode') {
          setShizukuState(prev => ({ ...prev, mode: mode || prev.mode }));
          onLogMessage(`Daemon mode switched: ${mode || 'current mode'} (Visual Check only)`);
        }
        setIsLoading(false);
      }, 400); // simulate UI loading delay
    } catch (err) {
      console.error(err);
      onLogMessage(`Error executing daemon control: ${action}`);
      setIsLoading(false);
    }
  };

  const requestShizukuPermission = async () => {
    setIsLoading(true);
    onLogMessage("Invoking Shizuku.requestPermission() via android.os.Binder IPC");
    const result = await nativeRequestPerm();
    if (result && !result.success) {
      onLogMessage(`[sh] ${result.error || 'Menunggu approval dialog Shizuku...'}`);
    } else {
      onLogMessage(`[sh] ✅ Izin diberikan! Menghubungkan daemon otomatis...`);
      // Auto-start daemon after permission granted
      try {
        await startDaemon();
        onLogMessage(`[sh] ✅ Daemon berhasil di-boot! Touch injection aktif.`);
        setShizukuState(prev => ({ ...prev, status: 'CONNECTED_SHIZUKU', daemonRunning: true }));
      } catch (e: any) {
        onLogMessage(`[sh ERROR] Gagal start daemon: ${e.message}`);
      }
    }
    setIsLoading(false);
  };

  // Listen for async permission result (when user approves Shizuku dialog)
  React.useEffect(() => {
    let listener: any;
    import('../plugins/GameMapper').then(({ default: GameMapper }) => {
      GameMapper.addListener('onShizukuPermissionGranted', (data: any) => {
        if (data.granted) {
          onLogMessage('[sh] ✅ Shizuku permission granted via dialog! Auto-starting daemon...');
          startDaemon().then(() => {
            onLogMessage('[sh] ✅ Daemon auto-started after permission grant!');
            setShizukuState(prev => ({ ...prev, status: 'CONNECTED_SHIZUKU', daemonRunning: true }));
          }).catch((e: any) => {
            onLogMessage(`[sh ERROR] Auto-start failed: ${e.message}`);
          });
        } else {
          onLogMessage('[sh] ❌ Shizuku permission denied by user.');
        }
      }).then((l: any) => { listener = l; });
    });
    return () => { listener?.remove(); };
  }, []);

  // Sync component permission status with global state
  React.useEffect(() => {
    if (shizukuState.status === 'CONNECTED_SHIZUKU') {
      setShizukuPermission('GRANTED');
    } else if (shizukuState.status === 'DISCONNECTED') {
      setShizukuPermission('PROMPT');
    }
  }, [shizukuState.status]);


  const sendCustomCommand = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!customLog.trim()) return;
    onLogMessage(`[sh] $ ${customLog}`);
    
    // Execute real command if native
    const res = await executeShizukuCommand(customLog);
    if (res) {
       if (res.output) {
           const lines = res.output.split('\n').filter(l => l.trim() !== '');
           lines.forEach(line => onLogMessage(`[sh] ${line}`));
       }
       if (res.error) {
           const lines = res.error.split('\n').filter(l => l.trim() !== '');
           lines.forEach(line => onLogMessage(`[sh ERROR] ${line}`));
       }
       if (!res.output && !res.error && res.exitCode === 0) {
           onLogMessage(`[sh] Command completed with exit code 0`);
       } else if (res.exitCode !== 0) {
           onLogMessage(`[sh ERROR] Command exited with code ${res.exitCode}`);
       }
    } else {
       // Mock for non-native context
       onLogMessage(`Executed locally (WebView context only): ${customLog}`);
    }

    setCustomLog('');
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl">
      {/* Title Header with status */}
      <div className="bg-slate-950 px-6 py-4 border-b border-slate-800 flex flex-wrap justify-between items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-indigo-500/10 text-indigo-400 rounded-lg border border-indigo-500/20">
            <Cpu className="w-5 h-5 animate-pulse" />
          </div>
          <div>
            <h2 className="text-base font-bold font-sans tracking-tight text-slate-100 flex items-center gap-2">
              Nexion Orchestration Control
              <span className="text-[10px] bg-indigo-950 text-indigo-400 px-2 py-0.5 rounded-full border border-indigo-900 font-mono">
                {shizukuState.daemonVersion}
              </span>
            </h2>
            <p className="text-xs text-slate-400">Zero-Latency Activation Controller Mode</p>
          </div>
        </div>

        {/* Global Connection Badge */}
        <div className="flex items-center gap-2">
          {shizukuState.daemonRunning ? (
            <span className="flex items-center gap-1 text-xs bg-emerald-950/80 text-emerald-400 px-3 py-1 rounded-full border border-emerald-900/50 font-medium">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span>
              CORE DAEMON ACTIVE
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs bg-rose-950/80 text-rose-400 px-3 py-1 rounded-full border border-rose-900/50 font-medium animate-pulse">
              <span className="w-2 h-2 rounded-full bg-rose-500"></span>
              DAEMON TERMINATED
            </span>
          )}
        </div>
      </div>

      {/* Tabs Selector */}
      <div className="flex border-b border-slate-800 bg-slate-950/40">
        <button
          onClick={() => { setActiveTab('shizuku'); triggerAction('toggle_mode', 'shizuku'); }}
          className={`flex-1 py-3 text-sm font-medium flex items-center justify-center gap-2 transition-all ${
            activeTab === 'shizuku' 
              ? 'text-indigo-400 border-b-2 border-indigo-500 bg-slate-900/60' 
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Smartphone className="w-4 h-4" />
          Shizuku Mode (Android 11+)
        </button>
        <button
          onClick={() => { setActiveTab('desktop'); triggerAction('toggle_mode', 'desktop'); }}
          className={`flex-1 py-3 text-sm font-medium flex items-center justify-center gap-2 transition-all ${
            activeTab === 'desktop' 
              ? 'text-indigo-400 border-b-2 border-indigo-500 bg-slate-900/60' 
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          <Laptop className="w-4 h-4" />
          Desktop ADB Companion
        </button>
      </div>

      <div className="p-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Main Orchestration & Activation parameters */}
        <div className="lg:col-span-7 space-y-5">
          {activeTab === 'shizuku' ? (
            <div className="space-y-4">
              <div className="p-4 bg-slate-950/60 rounded-lg border border-slate-800 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Binder IPC Authorization</span>
                  <span className={`text-xs px-2 py-0.5 rounded-full font-mono ${
                    shizukuPermission === 'GRANTED' ? 'bg-emerald-950 text-emerald-400 border border-emerald-900' : 'bg-amber-950 text-amber-400 border border-amber-900'
                  }`}>
                    {shizukuPermission}
                  </span>
                </div>
                <p className="text-xs text-slate-300 leading-relaxed text-justify">
                  Calls <code className="font-mono text-indigo-400 bg-slate-900 px-1 py-0.5 rounded">Shizuku.checkSelfPermission()</code> dynamically.
                  Runs the touch daemon inside an isolated shell process securely, avoiding the need for root user privileges or USB connections.
                </p>
                {shizukuPermission !== 'GRANTED' && (
                  <button
                    onClick={requestShizukuPermission}
                    disabled={isLoading}
                    className="w-full flex items-center justify-center gap-2 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-medium text-xs rounded-lg shadow-lg active:scale-[0.98] transition-all"
                  >
                    <ShieldCheck className="w-4 h-4" />
                    Authorize Shizuku AIDL Bindings
                  </button>
                )}
              </div>

              {/* Battery Optimization Exempted */}
              <div className="p-4 bg-slate-950/60 rounded-lg border border-slate-800 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Battery Optimization</span>
                  <span className={`text-xs px-2 py-0.5 rounded-full font-mono ${
                    isBatteryIgnored ? 'bg-emerald-950 text-emerald-400 border border-emerald-900' : 'bg-red-950 text-red-400 border border-red-900'
                  }`}>
                    {isBatteryIgnored ? 'EXEMPTED' : 'RESTRICTED'}
                  </span>
                </div>
                <p className="text-xs text-slate-300 leading-relaxed text-justify">
                  Game Mapper relies on a background execution process to intercept key inputs and render touch overlays. If the Android system restricts battery usage, your mapping tool might be forcefully terminated by the OS.
                </p>
                {!isBatteryIgnored && (
                  <button
                    onClick={async () => {
                      const result = await requestBatteryIgnore();
                      if (result) onLogMessage('SYSTEM: Redirected to battery settings.');
                    }}
                    className="w-full flex items-center justify-center gap-2 py-2 bg-red-600 hover:bg-red-500 text-white font-medium text-xs rounded-lg shadow-lg active:scale-[0.98] transition-all"
                  >
                    <Battery className="w-4 h-4 z-10" />
                    Ignore Battery Optimizations
                  </button>
                )}
              </div>

              {/* Shizuku Core Controller Status */}
              <div className="grid grid-cols-2 gap-4">
                <div className="p-4 bg-slate-950/30 rounded-lg border border-slate-800">
                  <div className="text-xs text-slate-400 mb-1">IPC Socket Endpoint</div>
                  <div className="font-mono text-sm text-indigo-300 font-semibold flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-indigo-400"></span>
                    @gampad_mapper_ipc
                  </div>
                </div>
                <div className="p-4 bg-slate-950/30 rounded-lg border border-slate-800">
                  <div className="text-xs text-slate-400 mb-1">Service Type</div>
                  <div className="font-mono text-sm text-pink-300 font-semibold flex items-center gap-1.5">
                    <Layers className="w-3.5 h-3.5 text-pink-400" />
                    IUserService
                  </div>
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex gap-3">
                  <button
                    onClick={() => triggerAction('start', 'shizuku')}
                    disabled={shizukuState.daemonRunning || isLoading}
                    className="flex-1 py-3 bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 disabled:opacity-30 disabled:from-slate-800 disabled:to-slate-800 disabled:text-slate-500 text-white font-semibold text-xs rounded-lg shadow-lg active:scale-[0.98] transition-all flex items-center justify-center gap-2"
                  >
                    <Play className="w-4 h-4 fill-white" />
                    BOOT NEXION SHUTTLE DAEMON
                  </button>
                  {shizukuState.daemonRunning && (
                    <button
                      onClick={() => triggerAction('stop')}
                      disabled={isLoading}
                      className="py-3 px-5 bg-rose-950 hover:bg-rose-900 border border-rose-800 text-rose-300 font-semibold text-xs rounded-lg active:scale-[0.98] transition-all flex items-center justify-center gap-2"
                    >
                      <XCircle className="w-4 h-4" />
                      KILL DAEMON
                    </button>
                  )}
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="p-4 bg-slate-950/60 rounded-lg border border-slate-800 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Desktop USB Active Link</span>
                  <span className="text-xs px-2 py-0.5 rounded-full font-mono bg-blue-950 text-blue-400 border border-blue-900">
                    Companion Active
                  </span>
                </div>
                <p className="text-xs text-slate-300 leading-relaxed text-justify">
                  Allows deployment via our lightweight Electron / Node.js companion script. Automatically pushes and triggers execution of binary daemon code directly into absolute native memory <code className="font-mono text-indigo-400 bg-slate-900 px-1 rounded">/data/local/tmp/gmm_daemon</code>.
                </p>
                <div className="flex items-center justify-between p-2 bg-slate-900/50 rounded border border-slate-800">
                  <span className="text-[11px] font-mono text-slate-400">adb shell sh /sdcard/.../start.sh</span>
                  <span className="text-[10px] text-emerald-400 font-semibold font-mono flex items-center gap-1">
                    <CheckCircle2 className="w-3 h-3" /> Ready
                  </span>
                </div>
              </div>

              {/* Desktop companion credentials & actions */}
              <div className="space-y-2">
                <div className="flex gap-3">
                  <button
                    onClick={() => triggerAction('start', 'desktop')}
                    disabled={shizukuState.daemonRunning || isLoading}
                    className="flex-1 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 disabled:opacity-30 disabled:from-slate-800 disabled:to-slate-800 disabled:text-slate-500 text-white font-semibold text-xs rounded-lg shadow-lg active:scale-[0.98] transition-all flex items-center justify-center gap-2"
                  >
                    <Laptop className="w-4 h-4" />
                    INITIALIZE VIA DESKTOP ADAPTER
                  </button>
                  {shizukuState.daemonRunning && (
                    <button
                      onClick={() => triggerAction('stop')}
                      disabled={isLoading}
                      className="py-3 px-5 bg-rose-950 hover:bg-rose-900 border border-rose-800 text-rose-300 font-semibold text-xs rounded-lg active:scale-[0.98] transition-all flex items-center justify-center gap-2"
                    >
                      <XCircle className="w-4 h-4" />
                      TERMINATE
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* Quick Troubleshooting Guide */}
          <div className="p-4 bg-amber-950/20 border border-amber-900/40 rounded-lg flex gap-3">
            <AlertTriangle className="w-5 h-5 text-amber-500 shrink-0 mt-0.5" />
            <div className="space-y-1">
              <h4 className="text-xs font-bold text-amber-400">Low-Level System Guard Notification</h4>
              <p className="text-[11px] text-slate-300 leading-relaxed">
                Jika input tidak terdeteksi, pastikan Anda juga mengaktifkan opsi "Bypass touch input driver queue / USB Debugging (Setelan Keamanan)" di Opsi Developer masing-masing merk handphone.
              </p>
            </div>
          </div>

          {/* INTERACTIVE ACTIVATION GUIDE (PANDUAN AKTIFASI) */}
          <div className="bg-slate-950/60 rounded-xl border border-indigo-950/60 p-4 space-y-4">
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
                
                const handleToggleExpand = () => {
                  if (isShizuku) {
                    setExpandedShizukuStep(isExpanded ? null : idx);
                  } else {
                    setExpandedDesktopStep(isExpanded ? null : idx);
                  }
                };

                const handleToggleCheck = (e: React.MouseEvent) => {
                  e.stopPropagation();
                  if (isShizuku) {
                    const newChecklist = [...shizukuChecklist];
                    newChecklist[idx] = !newChecklist[idx];
                    setShizukuChecklist(newChecklist);
                    if (newChecklist[idx]) {
                      onLogMessage(`Tutorial: Langkah Shizuku ${idx + 1} ditandai selesai ✅`);
                    }
                  } else {
                    const newChecklist = [...desktopChecklist];
                    newChecklist[idx] = !newChecklist[idx];
                    setDesktopChecklist(newChecklist);
                    if (newChecklist[idx]) {
                      onLogMessage(`Tutorial: Langkah ADB ${idx + 1} ditandai selesai ✅`);
                    }
                  }
                };

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
                      onClick={handleToggleExpand}
                      className="px-3.5 py-3 flex items-center justify-between cursor-pointer select-none"
                    >
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        {/* Custom Interactive Checkbox */}
                        <button 
                          type="button"
                          onClick={handleToggleCheck}
                          className="focus:outline-none flex-shrink-0"
                          title="Tandai Selesai"
                        >
                          {isCompleted ? (
                            <div className="w-5 h-5 bg-gradient-to-br from-emerald-500 to-teal-600 border border-emerald-400 rounded flex items-center justify-center text-white shadow-md active:scale-95 transition-transform">
                              <Check className="w-3.5 h-3.5 stroke-[3px]" />
                            </div>
                          ) : (
                            <div className="w-5 h-5 rounded border border-slate-700 hover:border-slate-500 flex items-center justify-center bg-slate-950 active:scale-95 transition-transform">
                              <span className="text-[10px] text-slate-500 font-bold font-mono">{idx + 1}</span>
                            </div>
                          )}
                        </button>

                        <div className="min-w-0 pr-2">
                          <h4 className={`text-xs font-bold tracking-tight transition-colors ${
                            isCompleted ? 'text-slate-400 line-through' : 'text-slate-100 font-sans'
                          }`}>
                            {step.title}
                          </h4>
                          <p className="text-[10px] text-slate-400 truncate mt-0.5 font-sans">
                            {step.short}
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-2">
                        <span className="text-[8px] bg-slate-950 border border-slate-800 text-indigo-300 font-mono px-1.5 py-0.5 rounded uppercase font-semibold">
                          {step.badge}
                        </span>
                        {isExpanded ? (
                          <ChevronDown className="w-4 h-4 text-slate-500 shrink-0" />
                        ) : (
                          <ChevronRight className="w-4 h-4 text-slate-500 shrink-0" />
                        )}
                      </div>
                    </div>

                    {/* Expandable step guidelines */}
                    {isExpanded && (
                      <div className="px-3.5 pb-4 pt-1 border-t border-slate-800/40 bg-slate-950/40 text-[11px] text-slate-300 space-y-2 leading-relaxed">
                        <ul className="list-disc pl-4 space-y-2 text-slate-300 text-justify font-sans">
                          {step.details.map((detail, dIdx) => (
                            <li key={dIdx} className="marker:text-indigo-400">
                              {detail}
                            </li>
                          ))}
                        </ul>
                        
                        <div className="pt-2 flex justify-between items-center">
                          {('actionButton' in step && step.actionButton) ? (
                            <button
                              onClick={() => {
                                onLogMessage((step as any).actionMessage);
                              }}
                              className="px-3 py-1.5 bg-slate-800 text-slate-200 border border-slate-700 rounded text-[10px] sm:text-xs hover:bg-indigo-600/30 hover:border-indigo-500/40 hover:text-indigo-300 flex items-center gap-1.5 transition-all active:scale-95 shadow font-semibold"
                            >
                              <Settings className="w-3.5 h-3.5" />
                              {(step as any).actionButton}
                            </button>
                          ) : <div />}
                          
                          <button 
                            type="button"
                            onClick={handleToggleCheck}
                            className={`px-3 py-1.5 text-[10px] sm:text-xs font-semibold rounded transition-all active:scale-[0.97] flex items-center gap-1 ${
                              isCompleted 
                                ? 'bg-slate-900 border border-slate-800 text-slate-400 hover:bg-slate-800/50' 
                                : 'bg-emerald-600/20 border border-emerald-500/40 text-emerald-400 hover:bg-emerald-600/30'
                            }`}
                          >
                            {isCompleted ? 'Batalkan Status Selesai' : 'Tandai Selesai & Lanjut'}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Live daemon logs (Simulated dynamic native terminal output) */}
        <div className="lg:col-span-12 xl:col-span-5 flex flex-col h-[320px] bg-slate-950 border border-slate-800 rounded-lg overflow-hidden">
          <div className="px-4 py-2 bg-slate-900 border-b border-slate-800 flex justify-between items-center">
            <span className="text-xs font-mono font-bold text-slate-400 flex items-center gap-1.5">
              <Terminal className="w-3.5 h-3.5 text-indigo-400" />
              NATIVE DAEMON STDOUT
            </span>
            <div className="flex gap-1.5">
              <span className="w-2.5 h-2.5 rounded-full bg-rose-500/20 border border-rose-500/40"></span>
              <span className="w-2.5 h-2.5 rounded-full bg-amber-500/20 border border-amber-500/40"></span>
              <span className="w-2.5 h-2.5 rounded-full bg-emerald-500/20 border border-emerald-500/40"></span>
            </div>
          </div>
          
          <div className="flex-1 p-3 font-mono text-[11px] text-emerald-400 space-y-1.5 overflow-y-auto scrollbar-thin scrollbar-thumb-indigo-500/20 select-text">
            {shizukuState.logLines.map((log, idx) => {
              let color = "text-emerald-400";
              if (log.includes("[INFO]")) color = "text-slate-300";
              else if (log.includes("[CALIBRATE]")) color = "text-amber-300";
              else if (log.includes("[SUCCESS]")) color = "text-teal-300 font-semibold";
              else if (log.includes("[GYRO]")) color = "text-pink-300";
              else if (log.includes("[USER]")) color = "text-indigo-300 font-medium";
              else if (log.includes("kill") || log.includes("Error") || log.includes("TERMINATED")) color = "text-rose-400";
              
              return (
                <div key={idx} className={`${color} leading-relaxed break-all`}>
                  {log}
                </div>
              );
            })}
          </div>

          <form onSubmit={sendCustomCommand} className="p-2 border-t border-slate-800 bg-slate-900/60 flex gap-2">
            <input 
              type="text" 
              value={customLog}
              onChange={(e) => setCustomLog(e.target.value)}
              placeholder="Inject custom log or shell command..."
              className="flex-1 bg-slate-950 border border-slate-800 px-3 py-1.5 text-xs text-slate-200 rounded font-mono focus:outline-none focus:border-indigo-500 transition-colors"
            />
            <button 
              type="submit"
              className="px-3 py-1 bg-indigo-600 hover:bg-slate-500 text-white font-mono text-xs font-bold rounded shadow transition-all active:scale-95"
            >
              INJECT
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
