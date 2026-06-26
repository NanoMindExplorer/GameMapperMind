/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { Compass } from 'lucide-react';
import { DEVICE_RAW_NODES } from '../defaults';

interface GamepadTesterProps {
  onLogMessage: (msg: string) => void;
}

export default function GamepadTesterComponent({ onLogMessage }: GamepadTesterProps) {
  // Pressed-buttons state (driven by native Android gamepad events via Capacitor plugin)
  const [pressedButtons, setPressedButtons] = React.useState<Record<string, boolean>>({});
  const [stickLeft, setStickLeft] = React.useState({ x: 0, y: 0 });
  const [stickRight, setStickRight] = React.useState({ x: 0, y: 0 });
  const [triggers, setTriggers] = React.useState({ lt: 0, rt: 0 });

  // Native gamepad connected state — set true only when a REAL button/axis event arrives.
  // BUG-FAKE-GAMEPAD FIX: Previously, handleNativeBtn set connectedGamepad to a fake
  // "Shizuku Emulated Native Gamepad" object whenever ANY native-gamepad-button event
  // fired — including ERROR_NO_GAMEPAD, ERROR_SHIZUKU_NOT_RUNNING, etc. This made the
  // UI say "Gamepad Connected" even when no gamepad was turned on. Now: only set
  // connected=true when a real button name (A/B/X/Y/LB/RB/etc.) arrives.
  const [nativeGamepadActive, setNativeGamepadActive] = React.useState(false);
  const lastBtnLogRef = React.useRef(0);

  React.useEffect(() => {
    // Real button names emitted by GamepadPlugin (Android API path) and
    // GamepadListenerService (Shizuku getevent path). ERROR_* events are NOT
    // real button presses and must NOT trigger the "connected" state.
    const REAL_BUTTON_NAMES = new Set([
      'A', 'B', 'X', 'Y',
      'LB', 'RB', 'LT', 'RT',
      'L3', 'R3',
      'START', 'SELECT', 'HOME',
      'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'
    ]);

    const handleNativeBtn = (e: Event) => {
      const data = (e as CustomEvent).detail;
      const btnName: string = data.buttonName || '';

      // Ignore ERROR_* events — they are status notifications, not button presses.
      if (btnName.startsWith('ERROR_')) {
        onLogMessage(`[SHIZUKU] ${btnName}`);
        return;
      }

      // Only process real button names.
      if (!REAL_BUTTON_NAMES.has(btnName)) return;

      // Mark native gamepad as active — a real button event arrived.
      if (!nativeGamepadActive) {
        setNativeGamepadActive(true);
      }

      setPressedButtons(prev => {
        const next = { ...prev };
        const kMap: Record<string, string> = {
           'A': 'a', 'B': 'b', 'X': 'x', 'Y': 'y',
           'LB': 'l_shoulder', 'RB': 'r_shoulder',
           'LT': 'lt_trigger', 'RT': 'rt_trigger',
           'L3': 'l3', 'R3': 'r3',
           'START': 'start', 'SELECT': 'select', 'HOME': 'home',
           'DPAD_UP': 'd_up', 'DPAD_DOWN': 'd_down',
           'DPAD_LEFT': 'd_left', 'DPAD_RIGHT': 'd_right'
        };
        const mapped = kMap[btnName];
        if (mapped) {
           if (data.value === 1) next[mapped] = true;
           else delete next[mapped];
        }
        return next;
      });

      // Throttle button logs to 1 per 200ms (avoid flooding when holding button)
      const now = Date.now();
      if (now - lastBtnLogRef.current > 200) {
        lastBtnLogRef.current = now;
        onLogMessage(`[GAMEPAD] ${btnName} ${data.value === 1 ? '▼ DOWN' : '▲ UP'}`);
      }
    };

    const handleNativeAxis = (e: Event) => {
        const data = (e as CustomEvent).detail;
        if (!data.axes || data.axes.length < 4) return;
        if (!nativeGamepadActive) {
          setNativeGamepadActive(true);
        }
        setStickLeft({ x: data.axes[0], y: data.axes[1] });
        setStickRight({ x: data.axes[2], y: data.axes[3] });
        if (data.axes.length >= 6) {
          setTriggers({ lt: data.axes[4], rt: data.axes[5] });
        }
    };

    window.addEventListener('native-gamepad-button', handleNativeBtn);
    window.addEventListener('native-gamepad-axis', handleNativeAxis);

    return () => {
      window.removeEventListener('native-gamepad-button', handleNativeBtn);
      window.removeEventListener('native-gamepad-axis', handleNativeAxis);
    };
  }, [onLogMessage, nativeGamepadActive]);

  // On-screen simulator handlers (for users without physical gamepad to test UI)
  const setSimButtonState = (key: string, state: boolean) => {
    setPressedButtons(prev => {
      if (prev[key] === state) return prev;
      onLogMessage(`Gamepad Tester Simulator: Key ${key.toUpperCase()} -> ${state ? 'PRESSED' : 'RELEASED'}`);
      return { ...prev, [key]: state };
    });
  };

  const handleStickMoveSimulate = (stick: 'l' | 'r', x: number, y: number) => {
    if (stick === 'l') {
      setStickLeft({ x, y });
    } else {
      setStickRight({ x, y });
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl flex flex-col">

      {/* Left Column: UI controller visualizer */}
      <div className="w-full p-6 flex flex-col justify-between">
        <div className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
                <Compass className="w-5 h-5 text-indigo-400 animate-spin-slow" />
                Gamepad Input Tester
              </h3>
              <p className="text-[11px] text-slate-400">High-frequency gamepad diagnostics</p>
            </div>
          </div>

          {/* Native Gamepad Event Status Banner */}
          <div className={`p-3 rounded-lg border transition-all duration-300 ${
            nativeGamepadActive
              ? 'bg-emerald-950/40 border-emerald-500/30 text-emerald-300 shadow-md shadow-emerald-500/5'
              : 'bg-slate-950/80 border-slate-850 text-slate-400'
          }`}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="relative flex h-2 w-2">
                  <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${nativeGamepadActive ? 'bg-emerald-400' : 'bg-slate-650'}`}></span>
                  <span className={`relative inline-flex rounded-full h-2 w-2 ${nativeGamepadActive ? 'bg-emerald-500' : 'bg-slate-500'}`}></span>
                </span>
                <span className="text-[11px] font-medium font-sans">
                  {nativeGamepadActive
                    ? 'Gamepad aktif — event diterima dari native path'
                    : 'Menunggu event gamepad... (nyalakan gamepad Bluetooth/OTG dan tekan tombol)'
                  }
                </span>
              </div>
              {nativeGamepadActive && (
                <span className="text-[9px] font-mono bg-emerald-900/30 px-2 py-0.5 rounded border border-emerald-800 text-emerald-450 animate-pulse">
                  EVENT ACTIVE
                </span>
              )}
            </div>
          </div>

          {/* Interactive Gamepad Simulator graphic layout representation */}
          <div className="relative w-full overflow-hidden bg-slate-950 rounded-xl border border-slate-850/80 p-4 shadow-inner">
            <div className="absolute inset-0 pointer-events-none bg-[radial-gradient(ellipse_at_center,rgba(99,102,241,0.06),transparent)]" />

            {/* Top Triggers Indicators */}
            <div className="relative w-full max-w-[380px] mx-auto flex justify-between px-6 mb-3">
              <div className="flex flex-col items-center w-20">
                <span className="text-[10px] font-mono text-slate-400 mb-1">LT {Math.round(triggers.lt * 100)}%</span>
                <div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700 cursor-pointer pointer-events-auto touch-none"
  onPointerDown={(e) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    const rect = e.currentTarget.getBoundingClientRect();
    const val = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    setTriggers(p => ({...p, lt: val}));
  }}
  onPointerMove={(e) => {
    if (e.buttons > 0) {
      const rect = e.currentTarget.getBoundingClientRect();
      const val = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      setTriggers(p => ({...p, lt: val}));
    }
  }}
  onPointerUp={(e) => {
    e.currentTarget.releasePointerCapture(e.pointerId);
    setTriggers(p => ({...p, lt: 0}));
  }}
  onPointerCancel={(e) => {
    e.currentTarget.releasePointerCapture(e.pointerId);
    setTriggers(p => ({...p, lt: 0}));
  }}
>
                   <div className="h-full bg-indigo-500 transition-all duration-75" style={{ width: `${triggers.lt * 100}%` }}></div>
                </div>
              </div>
              <div className="flex flex-col items-center w-20">
                <span className="text-[10px] font-mono text-slate-400 mb-1">RT {Math.round(triggers.rt * 100)}%</span>
                <div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700 cursor-pointer pointer-events-auto touch-none"
  onPointerDown={(e) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    const rect = e.currentTarget.getBoundingClientRect();
    const val = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    setTriggers(p => ({...p, rt: val}));
  }}
  onPointerMove={(e) => {
    if (e.buttons > 0) {
      const rect = e.currentTarget.getBoundingClientRect();
      const val = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      setTriggers(p => ({...p, rt: val}));
    }
  }}
  onPointerUp={(e) => {
    e.currentTarget.releasePointerCapture(e.pointerId);
    setTriggers(p => ({...p, rt: 0}));
  }}
  onPointerCancel={(e) => {
    e.currentTarget.releasePointerCapture(e.pointerId);
    setTriggers(p => ({...p, rt: 0}));
  }}
>
                   <div className="h-full bg-indigo-500 transition-all duration-75" style={{ width: `${triggers.rt * 100}%` }}></div>
                </div>
              </div>
            </div>

            {/* Gamepad Body */}
            <div className="relative w-full max-w-[420px] aspect-[2/1] mx-auto bg-slate-900 border-2 border-slate-800 rounded-[5rem] shadow-2xl flex p-4 pb-8 sm:p-6 sm:pb-10">

              {/* Left Side (Left Stick Top, DPad Bottom) */}
              <div className="relative w-1/3 h-full flex flex-col justify-between">

                {/* Left Stick (Top Left) */}
                <div
                  className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mt-1 sm:mt-2 touch-none cursor-crosshair"
                  onPointerDown={(e) => {
                    e.currentTarget.setPointerCapture(e.pointerId);
                  }}
                  onPointerMove={(e) => {
                    if (e.buttons > 0) {
                      const rect = e.currentTarget.getBoundingClientRect();
                      const centerX = rect.left + rect.width / 2;
                      const centerY = rect.top + rect.height / 2;
                      const maxDist = rect.width / 2;

                      let dx = e.clientX - centerX;
                      let dy = e.clientY - centerY;
                      const dist = Math.sqrt(dx * dx + dy * dy);
                      if (dist > maxDist) {
                        dx = (dx / dist) * maxDist;
                        dy = (dy / dist) * maxDist;
                      }
                      handleStickMoveSimulate('l', dx / maxDist, dy / maxDist);
                    }
                  }}
                  onPointerUp={(e) => {
                    e.currentTarget.releasePointerCapture(e.pointerId);
                    handleStickMoveSimulate('l', 0, 0);
                  }}
                  onPointerCancel={(e) => {
                    e.currentTarget.releasePointerCapture(e.pointerId);
                    handleStickMoveSimulate('l', 0, 0);
                  }}
                >
                  <div
                     className={`w-9 h-9 sm:w-10 sm:h-10 bg-slate-600 rounded-full shadow-lg border-b-2 border-slate-900 transition-transform duration-75 ${pressedButtons['l3'] ? 'bg-indigo-500 scale-90' : ''}`}
                     style={{ transform: `translate(${stickLeft.x * 24}px, ${stickLeft.y * 24}px)` }}
                  ></div>
                  <span className="absolute -top-4 text-[8px] font-bold text-slate-500 uppercase font-mono tracking-wider">L-Stick</span>
                </div>

                {/* D-Pad (Bottom Left) */}
                <div className="relative w-16 h-16 mx-auto mb-1 sm:mb-2">
                  <div className="absolute top-0 left-1/2 -translate-x-1/2 w-5 h-6 bg-slate-800 border border-slate-700 rounded-t flex justify-center items-start pt-1">
                     <div className={`w-2.5 h-2.5 rounded-full shadow-inner ${pressedButtons['d_up'] ? 'bg-indigo-500' : 'bg-slate-700'}`}></div>
                  </div>
                  <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-5 h-6 bg-slate-800 border border-slate-700 rounded-b flex justify-center items-end pb-1">
                     <div className={`w-2.5 h-2.5 rounded-full shadow-inner ${pressedButtons['d_down'] ? 'bg-indigo-500' : 'bg-slate-700'}`}></div>
                  </div>
                  <div className="absolute left-0 top-1/2 -translate-y-1/2 w-6 h-5 bg-slate-800 border border-slate-700 rounded-l flex justify-start items-center pl-1">
                     <div className={`w-2.5 h-2.5 rounded-full shadow-inner ${pressedButtons['d_left'] ? 'bg-indigo-500' : 'bg-slate-700'}`}></div>
                  </div>
                  <div className="absolute right-0 top-1/2 -translate-y-1/2 w-6 h-5 bg-slate-800 border border-slate-700 rounded-r flex justify-end items-center pr-1">
                     <div className={`w-2.5 h-2.5 rounded-full shadow-inner ${pressedButtons['d_right'] ? 'bg-indigo-500' : 'bg-slate-700'}`}></div>
                  </div>
                  <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-5 h-5 bg-slate-800 z-10"></div>
                </div>
              </div>

              {/* Middle Logo details */}
              <div className="w-1/3 flex flex-col items-center justify-center space-y-4 sm:space-y-6 z-10 pt-2 sm:pt-4">
                <div className="font-semibold text-[13px] sm:text-[15px] text-indigo-400 uppercase tracking-widest leading-none drop-shadow-md">GameMapperMind</div>
                <div className="flex gap-4 sm:gap-6">
                   {/* Select */}
                   <div className="flex flex-col items-center gap-1.5">
                     <div className={`w-3.5 h-1.5 rounded-full shadow-inner ${pressedButtons['select'] ? 'bg-indigo-400' : 'bg-slate-700'}`}></div>
                     <span className="text-[6px] text-slate-500 uppercase font-bold tracking-widest">Select</span>
                   </div>
                   {/* Middle LED */}
                   <div className="w-2.5 h-2.5 bg-indigo-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(99,102,241,0.6)] mt-0.5"></div>
                   {/* Start */}
                   <div className="flex flex-col items-center gap-1.5">
                     <div className={`w-3.5 h-1.5 rounded-full shadow-inner ${pressedButtons['start'] ? 'bg-indigo-400' : 'bg-slate-700'}`}></div>
                     <span className="text-[6px] text-slate-500 uppercase font-bold tracking-widest">Start</span>
                   </div>
                </div>
              </div>

              {/* Right Side (ABXY Top, Right Stick Bottom) */}
              <div className="relative w-1/3 h-full flex flex-col justify-between">

                {/* ABXY (Top Right) */}
                <div className="relative w-20 h-20 sm:w-24 sm:h-24 mx-auto mt-1 sm:mt-2">
                   {/* Y */}
                   <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("y", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("y", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("y", false); }} className={`absolute top-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons['y'] ? 'bg-yellow-500 text-slate-900 scale-95' : 'bg-slate-800 border-b-2 border-slate-900 text-yellow-500'}`}>Y</div>
                   {/* X */}
                   <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("x", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("x", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("x", false); }} className={`absolute left-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons['x'] ? 'bg-blue-500 text-slate-900 scale-95' : 'bg-slate-800 border-b-2 border-slate-900 text-blue-500'}`}>X</div>
                   {/* B */}
                   <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("b", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("b", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("b", false); }} className={`absolute right-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons['b'] ? 'bg-red-500 text-slate-900 scale-95' : 'bg-slate-800 border-b-2 border-slate-900 text-red-500'}`}>B</div>
                   {/* A */}
                   <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("a", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("a", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("a", false); }} className={`absolute bottom-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons['a'] ? 'bg-emerald-500 text-slate-900 scale-95' : 'bg-slate-800 border-b-2 border-slate-900 text-emerald-500'}`}>A</div>
                </div>

                {/* Right Stick (Bottom Right) */}
                <div
                  className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mb-1 sm:mb-2 touch-none cursor-crosshair"
                  onPointerDown={(e) => {
                    e.currentTarget.setPointerCapture(e.pointerId);
                  }}
                  onPointerMove={(e) => {
                    if (e.buttons > 0) {
                      const rect = e.currentTarget.getBoundingClientRect();
                      const centerX = rect.left + rect.width / 2;
                      const centerY = rect.top + rect.height / 2;
                      const maxDist = rect.width / 2;

                      let dx = e.clientX - centerX;
                      let dy = e.clientY - centerY;
                      const dist = Math.sqrt(dx * dx + dy * dy);
                      if (dist > maxDist) {
                        dx = (dx / dist) * maxDist;
                        dy = (dy / dist) * maxDist;
                      }
                      handleStickMoveSimulate('r', dx / maxDist, dy / maxDist);
                    }
                  }}
                  onPointerUp={(e) => {
                    e.currentTarget.releasePointerCapture(e.pointerId);
                    handleStickMoveSimulate('r', 0, 0);
                  }}
                  onPointerCancel={(e) => {
                    e.currentTarget.releasePointerCapture(e.pointerId);
                    handleStickMoveSimulate('r', 0, 0);
                  }}
                >
                  <div
                     className={`w-9 h-9 sm:w-10 sm:h-10 bg-slate-600 rounded-full shadow-lg border-b-2 border-slate-900 transition-transform duration-75 ${pressedButtons['r3'] ? 'bg-indigo-500 scale-90' : ''}`}
                     style={{ transform: `translate(${stickRight.x * 24}px, ${stickRight.y * 24}px)` }}
                  ></div>
                  <span className="absolute -bottom-4 text-[8px] font-bold text-slate-500 uppercase font-mono tracking-wider">R-Stick</span>
                </div>

              </div>

            </div>

            {/* L1 / R1 Shoulders */}
            <div className="absolute top-14 left-1/2 -translate-x-1/2 w-[280px] sm:w-[320px] flex justify-between px-2 opacity-80">
               <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("l_shoulder", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("l_shoulder", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("l_shoulder", false); }} className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg pointer-events-auto cursor-pointer touch-none transition-colors duration-75 ${pressedButtons['l_shoulder'] ? 'bg-indigo-500' : 'bg-slate-800'}`}></div>
               <div onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); setSimButtonState("r_shoulder", true); }} onPointerUp={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("r_shoulder", false); }} onPointerCancel={(e) => { e.currentTarget.releasePointerCapture(e.pointerId); setSimButtonState("r_shoulder", false); }} className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg pointer-events-auto cursor-pointer touch-none transition-colors duration-75 ${pressedButtons['r_shoulder'] ? 'bg-indigo-500' : 'bg-slate-800'}`}></div>
            </div>
            <div className="absolute top-10 left-1/2 -translate-x-1/2 w-[280px] sm:w-[320px] flex justify-between px-8 text-[8px] font-mono font-bold text-slate-500 uppercase pointer-events-none">
              <span>LB</span><span>RB</span>
            </div>

            {/* Fallback Simulator Guide */}
            {!nativeGamepadActive && (
              <div className="mt-4 text-[9px] text-center text-slate-500 uppercase tracking-widest font-mono">
                 <span className="border border-slate-800 bg-slate-900 rounded px-2 py-1">Mode Simulasi. Hubungkan Gamepad USB/Bluetooth untuk input nyata.</span>
              </div>
            )}
          </div>
        </div>

        {/* DEAD-CODE CLEANUP: Removed the entire "Dynamic Low-Latency Optimization Engine"
            panel (formerly ~140 lines). It contained fake toggles for "UINPUT Input Queue
            Bypass", "SCHED_FIFO High CPU Priority", "Bluetooth BLE Interval Tuning", and
            "Active Packet Jitter Stabilizer". None of these toggles actually wired into
            any native code or kernel parameter — they only set local React state and
            emitted misleading log messages ("Overclocked gamepad polling rate to 1000Hz",
            "SCHED_FIFO Priority boost Dilock pada level tertinggi", etc.). This gave users
            a false impression that the app was performing kernel-level tuning that it
            could not perform from a WebView context. The `calculatedLatency` value
            (claimed sub-millisecond precision) was also pure fabrication:
              `let base = 1000 / selectedPollingRate; ... base *= 0.85; ...`
            Real input latency on Android depends on the kernel input subsystem, the
            Shizuku daemon's MotionEvent injection cadence, and the target game's frame
            rate — none of which can be measured or influenced by these toggles. */}

        {/* Mapped Nodes details */}
        <div className="mt-5 p-4 bg-slate-950 rounded-lg border border-slate-850 flex flex-col gap-2 shadow-inner">
          <span className="text-[10px] font-mono font-bold text-slate-500 uppercase">AVAILABLE HARDWARE NODES (/dev/input)</span>
          <div className="space-y-1 max-h-[140px] overflow-y-auto">
            {DEVICE_RAW_NODES.map((node, i) => (
              <div key={i} className="flex justify-between items-center bg-slate-900/50 p-2 rounded border border-slate-850/80 hover:bg-slate-900 transition-colors">
                <span className="text-[10px] font-bold text-slate-300 truncate max-w-[170px]">{node.name}</span>
                <div className="flex items-center gap-2">
                  <span className="text-[9px] font-mono text-indigo-400 bg-indigo-950/40 px-1.5 py-0.5 rounded border border-indigo-900">{node.type}</span>
                  <span className="text-[9px] font-mono text-slate-500 bg-slate-950 px-1.5 py-0.5 rounded border border-slate-850">{node.path}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

    </div>
  );
}
