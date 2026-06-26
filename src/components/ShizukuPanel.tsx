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
import TouchInjection from '../plugins/TouchInjection';

interface ShizukuPanelProps {
  shizukuState: ShizukuState;
  setShizukuState: React.Dispatch<React.SetStateAction<ShizukuState>>;
  onLogMessage: (msg: string) => void;
}


export default function ShizukuPanel({ shizukuState, setShizukuState, onLogMessage }: ShizukuPanelProps) {
  const { requestShizukuPermission: nativeRequestPerm, executeShizukuCommand, startDaemon, stopDaemon, checkBattery, requestBatteryIgnore } = useShizuku();
  const [activeTab, setActiveTab] = React.useState<'shizuku' | 'desktop'>('shizuku');
  const [isLoading, setIsLoading] = React.useState(false);
  const [shizukuPermission, setShizukuPermission] = React.useState<'GRANTED' | 'DENIED' | 'PROMPT'>('PROMPT');
  const [isBatteryIgnored, setIsBatteryIgnored] = React.useState(true);
  const [customLog, setCustomLog] = React.useState('');

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
      onLogMessage(`[sh ERROR] Gagal meminta izin: ${result.error}`);
    } else {
      onLogMessage(`[sh] Permintaan Izin berhasil dikirim ke Shizuku.`);
    }
    setIsLoading(false);
  };

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

    // BUG-FIX: Diagnostics command — run full injection chain check
    if (customLog.trim() === 'diag' || customLog.trim() === 'diagnostics') {
      onLogMessage('[DIAG] Running full diagnostics...');
      try {
        const { report } = await TouchInjection.runDiagnostics();
        report.split('\n').forEach((line: string) => {
          if (line.trim()) onLogMessage(`[DIAG] ${line}`);
        });
      } catch (err: any) {
        onLogMessage(`[DIAG ERROR] ${err.message || err}`);
      }
      setCustomLog('');
      return;
    }

    // BUG-FIX: testinjection command — test injection at (500, 500) and report
    if (customLog.trim() === 'testinjection' || customLog.trim() === 'testinject') {
      onLogMessage('[TEST] Running injection test at (500, 500)...');
      try {
        const result = await TouchInjection.testInjection({ x: 500, y: 500 });
        onLogMessage(`[TEST] InputManager null: ${result.inputManager_null}`);
        onLogMessage(`[TEST] injectMethod null: ${result.injectMethod_null}`);
        onLogMessage(`[TEST] touchDown result: ${result.touchDown_result}`);
        onLogMessage(`[TEST] shellInputTap result: ${result.shellInputTap_result}`);
        onLogMessage(`[TEST] useShellFallback: ${result.useShellFallback}`);
        onLogMessage(`[TEST] Recommendation: ${result.recommendation || 'none'}`);
      } catch (err: any) {
        onLogMessage(`[TEST ERROR] ${err.message || err}`);
      }
      setCustomLog('');
      return;
    }

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

  // BUG-INJECT-FALLBACK: Test injection button — user taps this to verify
  // touch injection works WITHOUT needing the gamepad. Sends a tap to (500, 500)
  // and reports whether InputManager.injectInputEvent or shell fallback succeeded.
  const [testInjectionLoading, setTestInjectionLoading] = React.useState(false);
  const handleTestInjection = async () => {
    setTestInjectionLoading(true);
    onLogMessage('[TEST] Testing injection at screen center (500, 500)...');
    onLogMessage('[TEST] Watch your screen — a touch should appear at center.');
    try {
      const result = await TouchInjection.testInjection({ x: 500, y: 500 });
      onLogMessage(`[TEST] InputManager: ${result.inputManager_null ? 'NULL (blocked)' : 'OK'}`);
      onLogMessage(`[TEST] injectMethod: ${result.injectMethod_null ? 'NULL (not found)' : 'OK'}`);
      onLogMessage(`[TEST] touchDown: ${result.touchDown_result ? 'SUCCESS' : 'FAILED'}`);
      onLogMessage(`[TEST] shellInputTap: ${result.shellInputTap_result ? 'SUCCESS' : 'FAILED'}`);
      if (result.recommendation) {
        onLogMessage(`[TEST] → ${result.recommendation}`);
      }
    } catch (err: any) {
      onLogMessage(`[TEST ERROR] ${err.message || err}`);
      onLogMessage('[TEST] Make sure daemon is started first (tap "Start Daemon" above).');
    } finally {
      setTestInjectionLoading(false);
    }
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

          {/* BUG-INJECT-FALLBACK: Test Injection button */}
          <div className="p-4 bg-indigo-950/30 border border-indigo-800/50 rounded-lg space-y-3">
            <div className="flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-indigo-400" />
              <h4 className="text-xs font-bold text-indigo-300">Injection Verification</h4>
            </div>
            <p className="text-[11px] text-slate-300 leading-relaxed">
              Tap button below to test touch injection at screen center (500, 500) WITHOUT needing gamepad.
              A touch should appear on your screen. The log shows whether InputManager or shell fallback
              was used, and gives a recommendation if something is broken.
            </p>
            <button
              onClick={handleTestInjection}
              disabled={testInjectionLoading || shizukuState.status !== 'CONNECTED_SHIZUKU'}
              className="w-full py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-slate-800 disabled:text-slate-500 text-white text-xs font-bold rounded flex items-center justify-center gap-2 transition-colors"
            >
              {testInjectionLoading ? (
                <>
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                  Testing...
                </>
              ) : (
                <>
                  <Zap className="w-3.5 h-3.5" />
                  Test Injection (tap center of screen)
                </>
              )}
            </button>
            {shizukuState.status !== 'CONNECTED_SHIZUKU' && (
              <p className="text-[10px] text-amber-400">Start daemon first to enable test.</p>
            )}
          </div>

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
