/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import {
  Heart, Shield, Settings, Gamepad2, Crosshair, Check, X,
  ExternalLink, AlertTriangle, Cpu, Compass, Zap, ArrowRight, ArrowLeft
} from 'lucide-react';
import { Capacitor } from '@capacitor/core';
import { ShizukuState, OnboardingState, ControllerMode } from '../types';
import { detectControllerMode } from '../defaults';

interface OnboardingProps {
  shizukuState: ShizukuState;
  onComplete: (state: OnboardingState) => void;
  onSkip: () => void;
  detectedControllerId?: string;
}

const TOTAL_STEPS = 5;

export default function Onboarding({ shizukuState, onComplete, onSkip, detectedControllerId }: OnboardingProps) {
  const [step, setStep] = React.useState(0);
  const [state, setState] = React.useState<OnboardingState>({
    completed: false,
    currentStep: 0,
    steps: {
      welcome: false,
      installShizuku: false,
      grantPermissions: false,
      connectGamepad: false,
      calibrateProfile: false,
    },
    shizukuSkipped: false,
  });
  const [connectedGamepadId, setConnectedGamepadId] = React.useState<string>('');
  const [controllerMode, setControllerMode] = React.useState<ControllerMode | null>(null);

  // Listen for gamepad connections during step 3
  React.useEffect(() => {
    if (step !== 3) return;

    const checkGamepads = () => {
      const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
      for (let i = 0; i < gamepads.length; i++) {
        const gp = gamepads[i];
        if (gp) {
          setConnectedGamepadId(gp.id);
          const { mode, vendor, isDualMode } = detectControllerMode(gp.id);
          setControllerMode(mode);
          setState(prev => ({
            ...prev,
            detectedController: { id: gp.id, mode, vendor },
            steps: { ...prev.steps, connectGamepad: true },
          }));
          return;
        }
      }
    };

    checkGamepads();
    const interval = setInterval(checkGamepads, 500);

    const handleConnect = (e: GamepadEvent) => {
      setConnectedGamepadId(e.gamepad.id);
      const { mode, vendor } = detectControllerMode(e.gamepad.id);
      setControllerMode(mode);
      setState(prev => ({
        ...prev,
        detectedController: { id: e.gamepad.id, mode, vendor },
        steps: { ...prev.steps, connectGamepad: true },
      }));
    };
    const handleDisconnect = () => {
      setConnectedGamepadId('');
      setControllerMode(null);
      setState(prev => ({
        ...prev,
        detectedController: undefined,
        steps: { ...prev.steps, connectGamepad: false },
      }));
    };

    window.addEventListener('gamepadconnected', handleConnect);
    window.addEventListener('gamepaddisconnected', handleDisconnect);

    return () => {
      clearInterval(interval);
      window.removeEventListener('gamepadconnected', handleConnect);
      window.removeEventListener('gamepaddisconnected', handleDisconnect);
    };
  }, [step]);

  const goNext = () => {
    if (step < TOTAL_STEPS - 1) {
      const next = step + 1;
      setStep(next);
      setState(prev => ({ ...prev, currentStep: next }));
    } else {
      // Final step — mark complete
      const finalState = {
        ...state,
        completed: true,
        currentStep: TOTAL_STEPS,
        steps: {
          ...state.steps,
          calibrateProfile: true,
        },
      };
      setState(finalState);
      onComplete(finalState);
    }
  };

  const goBack = () => {
    if (step > 0) {
      const prev = step - 1;
      setStep(prev);
      setState(s => ({ ...s, currentStep: prev }));
    }
  };

  const markStepDone = (stepKey: keyof OnboardingState['steps']) => {
    setState(prev => ({ ...prev, steps: { ...prev.steps, [stepKey]: true } }));
  };

  // ============================================================
  // Render each step
  // ============================================================
  const renderStep = () => {
    switch (step) {
      case 0:
        return (
          <div className="text-center space-y-6 max-w-2xl mx-auto">
            <div className="inline-flex items-center justify-center p-6 bg-gradient-to-br from-indigo-500/20 to-pink-500/20 rounded-full ring-1 ring-indigo-500/30">
              <Crosshair className="w-12 h-12 text-indigo-400" />
            </div>
            <div>
              <h1 className="text-4xl font-black text-white mb-3 tracking-tight">
                Selamat datang di <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 to-pink-500">GameMapperMind</span>
              </h1>
              <p className="text-slate-400 text-lg leading-relaxed">
                Mapping tombol layar untuk gamepad fisik. Main game Android dengan controller Bluetooth/USB — termasuk Vortex XP107, Xbox, Switch Pro, dan lainnya.
              </p>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-left">
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800">
                <Gamepad2 className="w-5 h-5 text-indigo-400 mb-1.5" />
                <div className="text-xs font-bold text-slate-200">Multi-Controller</div>
                <div className="text-[10px] text-slate-500">Xbox / Switch / Vortex XP107</div>
              </div>
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800">
                <Zap className="w-5 h-5 text-emerald-400 mb-1.5" />
                <div className="text-xs font-bold text-slate-200">Low Latency</div>
                <div className="text-[10px] text-slate-500">Multi-pointer + anti-ban</div>
              </div>
              <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800">
                <Compass className="w-5 h-5 text-pink-400 mb-1.5" />
                <div className="text-xs font-bold text-slate-200">Gyro Support</div>
                <div className="text-[10px] text-slate-500">Camera control via motion</div>
              </div>
            </div>
          </div>
        );

      case 1:
        // Shizuku installation step
        const isShizukuConnected = shizukuState.status === 'CONNECTED_SHIZUKU';
        return (
          <div className="space-y-6 max-w-2xl mx-auto">
            <div className="text-center">
              <div className="inline-flex p-4 bg-indigo-500/10 rounded-full ring-1 ring-indigo-500/30 mb-3">
                <Shield className="w-8 h-8 text-indigo-400" />
              </div>
              <h2 className="text-2xl font-bold text-white mb-1">Aktifkan Shizuku</h2>
              <p className="text-slate-400 text-sm">Shizuku memberikan privilege shell untuk touch injection tanpa root.</p>
            </div>

            <div className={`p-4 rounded-lg border ${isShizukuConnected ? 'bg-emerald-950/40 border-emerald-500/30' : 'bg-amber-950/30 border-amber-900/40'}`}>
              <div className="flex items-start gap-3">
                {isShizukuConnected ? (
                  <Check className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
                ) : (
                  <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
                )}
                <div className="flex-1">
                  <div className="text-sm font-bold text-slate-200 mb-1">
                    Status: {isShizukuConnected ? 'Terhubung' : 'Belum terhubung'}
                  </div>
                  {!isShizukuConnected && (
                    <div className="text-xs text-slate-400 leading-relaxed">
                      Ikuti langkah-langkah di bawah untuk mengaktifkan Shizuku di HP Anda.
                    </div>
                  )}
                </div>
              </div>
            </div>

            {!isShizukuConnected && (
              <ol className="space-y-3 text-sm text-slate-300">
                <li className="flex gap-3">
                  <span className="shrink-0 w-6 h-6 rounded-full bg-indigo-500/20 text-indigo-400 text-xs font-bold flex items-center justify-center">1</span>
                  <div>
                    Install aplikasi <strong className="text-white">Shizuku</strong> dari Play Store:
                    <a href="https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
                       target="_blank" rel="noopener noreferrer"
                       className="ml-1 inline-flex items-center gap-1 text-indigo-400 hover:text-indigo-300">
                      Buka Play Store <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                </li>
                <li className="flex gap-3">
                  <span className="shrink-0 w-6 h-6 rounded-full bg-indigo-500/20 text-indigo-400 text-xs font-bold flex items-center justify-center">2</span>
                  <div>Buka aplikasi Shizuku → ikuti instruksi "Start via ADB" atau "Start via Root"</div>
                </li>
                <li className="flex gap-3">
                  <span className="shrink-0 w-6 h-6 rounded-full bg-indigo-500/20 text-indigo-400 text-xs font-bold flex items-center justify-center">3</span>
                  <div>Kembali ke GameMapperMind, tekan tombol "Refresh Status" di panel Orchestration Control</div>
                </li>
              </ol>
            )}

            <div className="flex items-center justify-between pt-2">
              <button
                onClick={() => {
                  setState(prev => ({ ...prev, shizukuSkipped: true, steps: { ...prev.steps, installShizuku: true } }));
                  goNext();
                }}
                className="text-xs text-slate-500 hover:text-slate-300 underline"
              >
                Skip (gunakan Accessibility fallback)
              </button>
              {isShizukuConnected && (
                <button
                  onClick={() => { markStepDone('installShizuku'); goNext(); }}
                  className="px-4 py-2 bg-indigo-500 hover:bg-indigo-400 text-white rounded-lg text-xs font-bold flex items-center gap-1.5"
                >
                  Lanjut <ArrowRight className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>
        );

      case 2:
        // Permissions step
        return (
          <div className="space-y-6 max-w-2xl mx-auto">
            <div className="text-center">
              <div className="inline-flex p-4 bg-pink-500/10 rounded-full ring-1 ring-pink-500/30 mb-3">
                <Settings className="w-8 h-8 text-pink-400" />
              </div>
              <h2 className="text-2xl font-bold text-white mb-1">Grant Permissions</h2>
              <p className="text-slate-400 text-sm">GameMapperMind butuh 3 permission untuk berfungsi penuh.</p>
            </div>

            <div className="space-y-3">
              <PermissionRow
                title="Display over other apps"
                desc="Untuk menampilkan tombol virtual di atas game"
                actionLabel="Buka Setting"
                onAction={() => {
                  if (Capacitor.isNativePlatform()) {
                    // Open SYSTEM_ALERT_WINDOW settings
                    const intent = `android.settings.action.MANAGE_OVERLAY_PERMISSION`;
                    // @ts-ignore
                    if (window.AndroidOverlay) window.AndroidOverlay.onCommand(`intent:${intent}`);
                  } else {
                    window.open('https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION');
                  }
                }}
              />
              <PermissionRow
                title="Accessibility Service"
                desc="Untuk auto-detect game aktif + capture macro"
                actionLabel="Buka Setting"
                onAction={() => {
                  if (Capacitor.isNativePlatform()) {
                    const intent = `android.settings.ACCESSIBILITY_SETTINGS`;
                    // @ts-ignore
                    if (window.AndroidOverlay) window.AndroidOverlay.onCommand(`intent:${intent}`);
                  }
                }}
              />
              <PermissionRow
                title="Battery Optimization Exception"
                desc="Agar service tidak ter-kill di background"
                actionLabel="Buka Setting"
                onAction={() => {
                  if (Capacitor.isNativePlatform()) {
                    const intent = `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`;
                    // @ts-ignore
                    if (window.AndroidOverlay) window.AndroidOverlay.onCommand(`intent:${intent}`);
                  }
                }}
              />
            </div>

            <div className="flex justify-end pt-2">
              <button
                onClick={() => { markStepDone('grantPermissions'); goNext(); }}
                className="px-4 py-2 bg-indigo-500 hover:bg-indigo-400 text-white rounded-lg text-xs font-bold flex items-center gap-1.5"
              >
                Saya sudah aktifkan semua <ArrowRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        );

      case 3:
        // Gamepad connection step
        return (
          <div className="space-y-6 max-w-2xl mx-auto">
            <div className="text-center">
              <div className="inline-flex p-4 bg-emerald-500/10 rounded-full ring-1 ring-emerald-500/30 mb-3">
                <Gamepad2 className="w-8 h-8 text-emerald-400" />
              </div>
              <h2 className="text-2xl font-bold text-white mb-1">Hubungkan Gamepad</h2>
              <p className="text-slate-400 text-sm">Pair via Bluetooth atau colok USB OTG. Tekan tombol apa saja di gamepad untuk verifikasi.</p>
            </div>

            <div className={`p-4 rounded-lg border ${connectedGamepadId ? 'bg-emerald-950/40 border-emerald-500/30' : 'bg-slate-900/60 border-slate-800'}`}>
              <div className="flex items-center gap-3">
                <span className={`w-2.5 h-2.5 rounded-full ${connectedGamepadId ? 'bg-emerald-500 animate-pulse' : 'bg-slate-600'}`} />
                <div className="flex-1 min-w-0">
                  {connectedGamepadId ? (
                    <>
                      <div className="text-sm font-bold text-emerald-300 truncate">{connectedGamepadId}</div>
                      {controllerMode && (
                        <div className="text-[10px] font-mono text-slate-400 mt-0.5">
                          Mode: <span className="text-indigo-400 font-bold">{controllerMode}</span>
                          {(controllerMode === 'VORTEX_XP107') && (
                            <span className="ml-2 text-pink-400">(dual-mode — switch fisik di controller)</span>
                          )}
                        </div>
                      )}
                    </>
                  ) : (
                    <div className="text-sm text-slate-400">Belum terdeteksi. Pastikan Bluetooth aktif, lalu pair gamepad Anda.</div>
                  )}
                </div>
              </div>
            </div>

            <div className="p-3 bg-indigo-950/20 rounded-lg border border-indigo-900/40 text-[11px] text-slate-300 leading-relaxed">
              <Cpu className="w-4 h-4 inline mr-1.5 text-indigo-400" />
              <strong>Tip untuk Vortex XP107:</strong> Controller ini punya switch fisik untuk berubah antara Xbox mode (default) dan Switch mode.
              GameMapperMind akan auto-detect perubahan mode dan menyesuaikan mapping A/B otomatis.
            </div>

            <div className="flex items-center justify-between pt-2">
              <button
                onClick={goBack}
                className="text-xs text-slate-500 hover:text-slate-300 flex items-center gap-1"
              >
                <ArrowLeft className="w-3.5 h-3.5" /> Kembali
              </button>
              <button
                onClick={() => { markStepDone('connectGamepad'); goNext(); }}
                disabled={!connectedGamepadId}
                className="px-4 py-2 bg-indigo-500 hover:bg-indigo-400 disabled:bg-slate-800 disabled:text-slate-500 text-white rounded-lg text-xs font-bold flex items-center gap-1.5"
              >
                Lanjut <ArrowRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        );

      case 4:
        // Calibration step
        return (
          <div className="space-y-6 max-w-2xl mx-auto text-center">
            <div className="inline-flex p-4 bg-pink-500/10 rounded-full ring-1 ring-pink-500/30 mb-3">
              <Crosshair className="w-8 h-8 text-pink-400" />
            </div>
            <div>
              <h2 className="text-2xl font-bold text-white mb-2">Siap untuk kalibrasi!</h2>
              <p className="text-slate-400 text-sm leading-relaxed max-w-md mx-auto">
                Langkah terakhir: kalibrasi posisi tombol virtual di tab <strong className="text-white">WYSIWYG Overlay Canvas</strong>.
                Upload screenshot game Anda, lalu drag-drop tombol ke posisi yang sesuai.
              </p>
            </div>

            <div className="p-4 bg-slate-900/60 rounded-lg border border-slate-800 text-left max-w-md mx-auto">
              <div className="text-xs font-bold text-slate-300 mb-2">Checklist pasca-onboarding:</div>
              <ul className="space-y-1.5 text-[11px] text-slate-400">
                <li className="flex items-start gap-2">
                  <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                  Pilih profile game di Profile Manager (atau buat profile baru)
                </li>
                <li className="flex items-start gap-2">
                  <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                  Upload screenshot game sebagai background
                </li>
                <li className="flex items-start gap-2">
                  <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                  Drag-drop tombol virtual ke posisi yang sesuai dengan UI game
                </li>
                <li className="flex items-start gap-2">
                  <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                  Save profile → tekan Start Overlay
                </li>
                <li className="flex items-start gap-2">
                  <Check className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                  Switch ke game → tombol virtual aktif!
                </li>
              </ul>
            </div>

            <button
              onClick={() => { markStepDone('calibrateProfile'); goNext(); }}
              className="px-6 py-3 bg-gradient-to-r from-indigo-500 to-pink-500 hover:from-indigo-400 hover:to-pink-400 text-white rounded-lg text-sm font-bold flex items-center gap-2 mx-auto"
            >
              <Heart className="w-4 h-4" /> Mulai bermain!
            </button>
          </div>
        );

      default:
        return null;
    }
  };

  return (
    <div className="fixed inset-0 z-[10000] bg-[#060608] text-slate-100 flex flex-col overflow-y-auto">
      {/* Top gradient */}
      <div className="absolute inset-x-0 top-0 h-[450px] bg-gradient-to-b from-indigo-950/15 via-transparent to-transparent pointer-events-none" />

      {/* Skip button */}
      <button
        onClick={onSkip}
        className="absolute top-4 right-4 z-50 text-xs text-slate-500 hover:text-slate-300 flex items-center gap-1 bg-slate-900/60 px-3 py-1.5 rounded-lg border border-slate-800"
      >
        <X className="w-3 h-3" /> Skip
      </button>

      {/* Progress dots */}
      <div className="flex justify-center gap-2 pt-12 pb-4 relative z-10">
        {Array.from({ length: TOTAL_STEPS }).map((_, i) => (
          <div
            key={i}
            className={`h-1.5 rounded-full transition-all ${i === step ? 'w-8 bg-indigo-500' : i < step ? 'w-1.5 bg-indigo-500/60' : 'w-1.5 bg-slate-700'}`}
          />
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 flex items-center justify-center p-6 relative z-10">
        {renderStep()}
      </div>

      {/* Footer */}
      <div className="border-t border-slate-900 py-3 text-center text-[10px] font-mono text-slate-600">
        Step {step + 1} of {TOTAL_STEPS} • GameMapperMind Onboarding
      </div>
    </div>
  );
}

function PermissionRow({ title, desc, actionLabel, onAction }: {
  title: string; desc: string; actionLabel: string; onAction: () => void;
}) {
  return (
    <div className="p-3 bg-slate-900/60 rounded-lg border border-slate-800 flex items-center gap-3">
      <div className="flex-1">
        <div className="text-sm font-bold text-slate-200">{title}</div>
        <div className="text-[11px] text-slate-500">{desc}</div>
      </div>
      <button
        onClick={onAction}
        className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[10px] font-bold uppercase flex items-center gap-1"
      >
        <ExternalLink className="w-3 h-3" /> {actionLabel}
      </button>
    </div>
  );
}
