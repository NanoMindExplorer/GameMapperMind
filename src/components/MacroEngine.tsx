/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { GamepadMacro, MacroAction, MacroCaptureEvent } from '../types';
import { Play, Square, RefreshCcw, Activity, Plus, FastForward, Check, Trash2, ArrowDownCircle, Info, Edit2, Circle, Radio } from 'lucide-react';
import { useShizuku } from '../hooks/useShizuku';
import { useMacroCapture } from '../hooks/useMacroCapture';
import { Capacitor } from '@capacitor/core';
import GameMapper from '../plugins/GameMapper';

interface MacroEngineProps {
  macros: GamepadMacro[];
  onUpdateMacros: (newMacros: GamepadMacro[]) => void;
  onLogMessage: (msg: string) => void;
}

export default function MacroEngineComponent({ macros, onUpdateMacros, onLogMessage }: MacroEngineProps) {
  const { injectInput } = useShizuku();
  const [isShizukuConnected, setIsShizukuConnected] = React.useState(false);

  // Track Shizuku connection status to prevent dual injection
  React.useEffect(() => {
    const checkStatus = async () => {
      if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
        try {
          const status = await GameMapper.checkShizukuStatus();
          setIsShizukuConnected(status.granted && (status.binderAlive ?? false));
        } catch {
          setIsShizukuConnected(false);
        }
      }
    };
    checkStatus();
    const interval = setInterval(checkStatus, 3000);
    return () => clearInterval(interval);
  }, []);
  const [selectedMacroId, setSelectedMacroId] = React.useState<string>(macros[0]?.id || '');
  const [isRecording, setIsRecording] = React.useState(false);
  const [isPlaying, setIsPlaying] = React.useState(false);
  const [isRealRecording, setIsRealRecording] = React.useState(false);
  const [recordTicks, setRecordTicks] = React.useState(0);
  const [playbackSpeed, setPlaybackSpeed] = React.useState(1.0);
  const [loopCount, setLoopCount] = React.useState(0);

  const [isEditingMeta, setIsEditingMeta] = React.useState(false);
  const [editMetaValues, setEditMetaValues] = React.useState({ name: '', triggerKey: '' });

  const [recX, setRecX] = React.useState(500);
  const [recY, setRecY] = React.useState(500);
  const [recPointer, setRecPointer] = React.useState(1);

  const playbackTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const isPlayingRef = React.useRef(false);
  const capturedEventsRef = React.useRef<MacroCaptureEvent[]>([]);

  const macrosRef = React.useRef(macros);
  const onUpdateMacrosRef = React.useRef(onUpdateMacros);

  React.useEffect(() => {
    macrosRef.current = macros;
    onUpdateMacrosRef.current = onUpdateMacros;
  }, [macros, onUpdateMacros]);

  React.useEffect(() => {
    if (macros.length === 0) return;
    const exists = macros.some(m => m.id === selectedMacroId);
    if (!exists) setSelectedMacroId(macros[0].id);
  }, [macros, selectedMacroId]);

  // Real macro capture hook
  const { startCapture, stopCapture, eventsToActions } = useMacroCapture({
    onCapture: (event) => {
      capturedEventsRef.current.push(event);
      setRecordTicks(prev => prev + 1);
      if (capturedEventsRef.current.length % 10 === 0) {
        onLogMessage(`Macro Capture: ${capturedEventsRef.current.length} events captured (ptr ${event.pointerId} ${event.action} @ ${event.x},${event.y})`);
      }
    },
  });

  React.useEffect(() => {
    const handleKill = () => {
      isPlayingRef.current = false;
      setIsPlaying(false);
      setIsRecording(false);
      setIsRealRecording(false);
      if (playbackTimeoutRef.current) {
        clearTimeout(playbackTimeoutRef.current);
        playbackTimeoutRef.current = null;
      }
      stopCapture();
      onUpdateMacrosRef.current(macrosRef.current.map(m => ({ ...m, actions: [] })));
      onLogMessage(`[KILL-SWITCH] Macro playback terminated and macro buffers cleared.`);
    };

    window.addEventListener('emergency-kill', handleKill);
    return () => {
      window.removeEventListener('emergency-kill', handleKill);
      isPlayingRef.current = false;
      if (playbackTimeoutRef.current) clearTimeout(playbackTimeoutRef.current);
      stopCapture();
    };
  }, [onLogMessage, stopCapture]);

  const selectedMacro = macros.find(m => m.id === selectedMacroId);

  const buildCommand = (action: MacroAction): string => {
    switch (action.type) {
      case 'touch_down': return `down ${action.x ?? 0} ${action.y ?? 0}`;
      case 'touch_move': return `move ${action.x ?? 0} ${action.y ?? 0}`;
      case 'touch_up':   return `up 0 0`;
      case 'delay':
      default: return '';
    }
  };

  const handleTriggerPlayback = async () => {
    if (isPlayingRef.current || !selectedMacro || selectedMacro.actions.length === 0) return;
    isPlayingRef.current = true;
    setIsPlaying(true);
    const totalLoops = loopCount === -1 ? Infinity : Math.max(1, loopCount + 1);
    let currentLoop = 0;
    let tickCount = 0;
    const totalActions = selectedMacro.actions.length;

    onLogMessage(`Macro Engine: Playback start [${selectedMacro.name}] speed=${playbackSpeed.toFixed(1)}x loops=${loopCount === -1 ? '∞' : loopCount}`);

    const playNext = () => {
      if (!isPlayingRef.current) {
        onLogMessage(`Macro Engine: Playback aborted before action ${tickCount + 1}/${totalActions} (loop ${currentLoop + 1}).`);
        return;
      }

      if (tickCount >= totalActions) {
        currentLoop++;
        if (currentLoop >= totalLoops) {
          isPlayingRef.current = false;
          setIsPlaying(false);
          onLogMessage(`Macro Engine: Sequence [${selectedMacro.name}] completed ${currentLoop} loop(s).`);
          return;
        }
        tickCount = 0;
        onLogMessage(`Macro Engine: Loop ${currentLoop + 1}/${totalLoops === Infinity ? '∞' : totalLoops}`);
      }

      const action = selectedMacro.actions[tickCount];
      const cmd = buildCommand(action);
      if (cmd) {
        onLogMessage(`[MACRO INJECTION] ${action.type} ptr=${action.pointerId} x=${action.x ?? 0} y=${action.y ?? 0}`);
        // FIX #12: Only use JS-side injection if Shizuku is NOT connected.
        // When Shizuku IS connected, the native pipeline (Path B) handles
        // all injection. JS-side injection (Path A) is for fallback/dev mode only.
        if (!isShizukuConnected) {
          injectInput(cmd).catch(() => onLogMessage(`Macro Engine Error: action ${tickCount + 1} failed.`));
        } else {
          onLogMessage(`[MACRO] Skipped JS injection — Shizuku native pipeline active. Macro playback is visual-only when Shizuku is connected.`);
        }
      }

      tickCount++;
      const nextDelay = (action.delayMs ?? 33) / playbackSpeed;
      playbackTimeoutRef.current = setTimeout(playNext, nextDelay);
    };

    playNext();
  };

  const startRecordScenario = () => {
    setIsRecording(true);
    setRecordTicks(0);
    const freshMacro: GamepadMacro = {
      id: `mac_${Date.now()}`,
      name: `Sandbox Capture #${macros.length + 1}`,
      triggerKey: 'M1 (Trigger Macro Action)',
      playbackSpeed: 1.0,
      actions: [],
      loopCount: 0,
      recordedVia: 'manual',
    };
    onUpdateMacros([...macros, freshMacro]);
    setSelectedMacroId(freshMacro.id);
    onLogMessage(`Macro Engine: Manual recorder armed.`);
  };

  // ============================================================
  // Real macro capture — uses AccessibilityService to intercept
  // MotionEvents on screen while the user performs the action.
  // ============================================================
  const startRealCapture = async () => {
    capturedEventsRef.current = [];
    setRecordTicks(0);
    const ok = await startCapture();
    if (!ok) {
      onLogMessage('Macro Engine: Real capture requires native Android + Accessibility permission.');
      return;
    }
    setIsRealRecording(true);
    const freshMacro: GamepadMacro = {
      id: `mac_real_${Date.now()}`,
      name: `Real Capture #${macros.length + 1}`,
      triggerKey: 'M2 (Real Recorded)',
      playbackSpeed: 1.0,
      actions: [],
      loopCount: 0,
      recordedVia: 'real_capture',
      recordedAt: new Date().toISOString(),
    };
    onUpdateMacros([...macros, freshMacro]);
    setSelectedMacroId(freshMacro.id);
    onLogMessage(`Macro Engine: REAL capture started. Perform your action on screen now.`);
  };

  const stopRealCapture = async () => {
    await stopCapture();
    setIsRealRecording(false);
    const events = capturedEventsRef.current;
    const actions = eventsToActions(events);
    if (actions.length === 0) {
      onLogMessage('Macro Engine: No events captured.');
      return;
    }
    const duration = events.length > 0 ? events[events.length - 1].timestamp - events[0].timestamp : 0;
    // Update the last-created macro with the captured actions
    onUpdateMacros(macros.map((m, i) => {
      if (i === macros.length - 1) {
        return {
          ...m,
          actions,
          recordedDurationMs: duration,
          name: `Real Capture #${macros.length} (${(duration / 1000).toFixed(1)}s, ${actions.length} actions)`,
        };
      }
      return m;
    }));
    onLogMessage(`Macro Engine: REAL capture complete. ${actions.length} actions, duration ${(duration / 1000).toFixed(2)}s.`);
    capturedEventsRef.current = [];
  };

  const stopRecordScenario = () => {
    setIsRecording(false);
    onLogMessage(`Macro Engine: Manual recording concluded.`);
  };

  const appendMockRecordEvent = (type: 'touch_down' | 'touch_move' | 'touch_up') => {
    if (!isRecording || !selectedMacroId) return;
    const actionItem: MacroAction = {
      id: `act_${Date.now()}_${Math.random().toString().slice(2, 5)}`,
      type,
      x: type !== 'touch_up' ? recX : undefined,
      y: type !== 'touch_up' ? recY : undefined,
      pointerId: recPointer,
      delayMs: 33,
    };
    onUpdateMacros(macros.map(m => m.id === selectedMacroId ? { ...m, actions: [...m.actions, actionItem] } : m));
    setRecordTicks(prev => prev + 1);
    onLogMessage(`Macro Capture: Logged ${type.toUpperCase()} Pointer: ${recPointer} (X: ${recX}, Y: ${recY})`);
  };

  const handleRemoveMacro = (id: string) => {
    if (macros.length <= 1) return;
    const filtered = macros.filter(m => m.id !== id);
    onUpdateMacros(filtered);
    setSelectedMacroId(filtered[0].id);
    onLogMessage(`Macro Engine: Removed macro [${id}]`);
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl grid grid-cols-1 md:grid-cols-12">
      <div className="md:col-span-5 p-6 border-b md:border-b-0 md:border-r border-slate-800 bg-slate-950/20 flex flex-col justify-between">
        <div className="space-y-4">
          <div>
            <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
              <Activity className="w-4 h-4 text-emerald-400" />
              Autonomous Macro Pipeline
            </h3>
            <p className="text-[11px] text-slate-400">Record &amp; playback gesture sequences</p>
          </div>

          <div className="space-y-2 max-h-[220px] overflow-y-auto pr-1">
            {macros.map((m) => {
              const isActive = m.id === selectedMacroId;
              return (
                <div
                  key={m.id}
                  onClick={() => !isRecording && !isRealRecording && setSelectedMacroId(m.id)}
                  className={`p-3 rounded-lg border cursor-pointer transition-all flex items-center justify-between ${
                    isActive ? 'bg-slate-900 border-indigo-500/70 shadow-lg text-slate-100'
                      : 'bg-slate-950/40 border-slate-850 text-slate-400 hover:text-slate-200 hover:bg-slate-950/60'
                  } ${(isRecording || isRealRecording) ? 'pointer-events-none opacity-50' : ''}`}
                >
                  <div className="space-y-0.5 truncate max-w-[180px]">
                    <div className="text-xs font-bold truncate flex items-center gap-1.5">
                      {m.recordedVia === 'real_capture' && <Radio className="w-3 h-3 text-emerald-400" />}
                      {m.name}
                    </div>
                    <div className="text-[9px] font-mono text-slate-500">Trigger: {m.triggerKey}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-mono bg-slate-950 border border-slate-800 text-indigo-400 px-2 py-0.5 rounded">
                      {m.actions.length} inst
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="pt-4 border-t border-slate-850 mt-4 space-y-2">
          {!isRecording && !isRealRecording ? (
            <>
              <button
                onClick={startRealCapture}
                className="w-full py-2.5 bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs rounded-lg active:scale-95 transition-all flex items-center justify-center gap-1.5"
              >
                <Radio className="w-4 h-4" /> REAL CAPTURE (record from screen)
              </button>
              <button
                onClick={startRecordScenario}
                className="w-full py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-lg active:scale-95 transition-all flex items-center justify-center gap-1.5"
              >
                <Plus className="w-3.5 h-3.5" /> Manual Capture
              </button>
            </>
          ) : isRealRecording ? (
            <button
              onClick={stopRealCapture}
              className="w-full py-2.5 bg-rose-600 hover:bg-rose-500 text-white font-bold text-xs rounded-lg animate-pulse active:scale-95 transition-all flex items-center justify-center gap-1.5"
            >
              <Square className="w-4 h-4 fill-white" /> STOP REAL CAPTURE ({recordTicks})
            </button>
          ) : (
            <button
              onClick={stopRecordScenario}
              className="w-full py-2.5 bg-rose-600 hover:bg-rose-500 text-white font-bold text-xs rounded-lg animate-pulse active:scale-95 transition-all flex items-center justify-center gap-1.5"
            >
              <Square className="w-4 h-4 fill-white" /> FINISH MANUAL ({recordTicks})
            </button>
          )}
        </div>
      </div>

      <div className="md:col-span-7 p-6 bg-slate-950/40 flex flex-col justify-between">
        {selectedMacro ? (
          <div className="space-y-4">
            <div className="flex justify-between items-center bg-slate-950 p-4 rounded-lg border border-slate-850 shadow-inner">
              {isEditingMeta ? (
                <div className="flex-1 mr-4 space-y-2">
                  <input type="text" className="w-full bg-slate-900 border border-slate-800 rounded px-2 py-1 text-xs text-slate-200 outline-none focus:border-indigo-500"
                    placeholder="Macro Name" value={editMetaValues.name}
                    onChange={(e) => setEditMetaValues({...editMetaValues, name: e.target.value})} />
                  <input type="text" className="w-full bg-slate-900 border border-slate-800 rounded px-2 py-1 text-[10px] font-mono text-slate-400 outline-none focus:border-indigo-500"
                    placeholder="Trigger Key" value={editMetaValues.triggerKey}
                    onChange={(e) => setEditMetaValues({...editMetaValues, triggerKey: e.target.value})} />
                  <div className="flex justify-end gap-2 mt-1">
                    <button onClick={() => setIsEditingMeta(false)} className="px-2 py-1 rounded text-[9px] font-bold text-slate-400 hover:text-slate-200 bg-slate-800 uppercase">Cancel</button>
                    <button onClick={() => {
                      onUpdateMacros(macros.map(m => m.id === selectedMacro.id ? { ...m, name: editMetaValues.name, triggerKey: editMetaValues.triggerKey } : m));
                      setIsEditingMeta(false);
                      onLogMessage(`Macro Engine: Metadata updated for [${editMetaValues.name}]`);
                    }} className="px-2 py-1 flex items-center gap-1 rounded text-[9px] font-bold text-white bg-indigo-500 hover:bg-indigo-400 uppercase"><Check className="w-3 h-3" /> Save</button>
                  </div>
                </div>
              ) : (
                <div className="space-y-0.5 flex-1 cursor-pointer group" onClick={() => {
                  setEditMetaValues({ name: selectedMacro.name, triggerKey: selectedMacro.triggerKey });
                  setIsEditingMeta(true);
                }}>
                  <div className="flex items-center gap-2">
                    <h4 className="text-xs font-bold text-slate-200 group-hover:text-indigo-300 transition-colors">{selectedMacro.name}</h4>
                    <Edit2 className="w-3 h-3 text-slate-500 group-hover:text-indigo-400 opacity-0 group-hover:opacity-100 transition-opacity" />
                    {selectedMacro.recordedVia === 'real_capture' && (
                      <span className="text-[8px] font-bold bg-emerald-950/40 text-emerald-400 border border-emerald-900/40 px-1.5 py-0.5 rounded uppercase">REAL</span>
                    )}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400">Trigger: {selectedMacro.triggerKey} • {selectedMacro.actions.length} actions</div>
                </div>
              )}
              <button
                onClick={() => handleRemoveMacro(selectedMacro.id)}
                disabled={macros.length <= 1 || isRecording || isRealRecording || isEditingMeta}
                className="p-1.5 hover:bg-rose-950/40 text-slate-400 hover:text-rose-450 rounded disabled:opacity-30 disabled:pointer-events-none transition-all"
                title="Discard macro"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            </div>

            {isRecording ? (
              <div className="p-4 bg-slate-900 rounded-lg border border-indigo-500/30 space-y-3">
                <div className="flex items-center justify-between text-xs font-bold text-indigo-400">
                  <span className="flex items-center gap-1.5">
                    <Circle className="w-2 h-2 rounded-full bg-indigo-500 animate-ping" />
                    MANUAL CAPTURE — input events manually
                  </span>
                  <span className="font-mono text-[10px]">{recordTicks} events</span>
                </div>

                <div className="grid grid-cols-3 gap-2">
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans uppercase">X</label>
                    <input type="number" className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={recX} onChange={(e) => setRecX(parseInt(e.target.value) || 0)} />
                  </div>
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans uppercase">Y</label>
                    <input type="number" className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={recY} onChange={(e) => setRecY(parseInt(e.target.value) || 0)} />
                  </div>
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans uppercase">PTR</label>
                    <input type="number" min="1" max="10" className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={recPointer} onChange={(e) => setRecPointer(parseInt(e.target.value) || 1)} />
                  </div>
                </div>

                <div className="flex gap-2">
                  <button onClick={() => appendMockRecordEvent('touch_down')}
                    className="flex-1 py-1.5 bg-emerald-950 hover:bg-emerald-900 border border-emerald-900/50 text-emerald-400 text-[10px] font-bold rounded">+ DOWN</button>
                  <button onClick={() => appendMockRecordEvent('touch_move')}
                    className="flex-1 py-1.5 bg-blue-950 hover:bg-blue-900 border border-blue-900/50 text-blue-400 text-[10px] font-bold rounded">+ MOVE</button>
                  <button onClick={() => appendMockRecordEvent('touch_up')}
                    className="flex-1 py-1.5 bg-red-950 hover:bg-red-900 border border-red-900/50 text-red-400 text-[10px] font-bold rounded">+ UP</button>
                </div>
              </div>
            ) : isRealRecording ? (
              <div className="p-4 bg-emerald-950/30 rounded-lg border border-emerald-500/40 space-y-3">
                <div className="flex items-center justify-between text-xs font-bold text-emerald-300">
                  <span className="flex items-center gap-1.5">
                    <Circle className="w-2 h-2 rounded-full bg-emerald-500 animate-ping" />
                    REAL CAPTURE ACTIVE — perform action on screen
                  </span>
                  <span className="font-mono text-[10px]">{recordTicks} events captured</span>
                </div>
                <p className="text-[10px] text-slate-400 leading-relaxed">
                  Sentuh layar Anda seperti biasa — GameMapperMind akan merekam setiap touch event.
                  Tekan tombol STOP ketika selesai.
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <div className="flex justify-between items-center text-xs font-semibold text-slate-400">
                    <span className="uppercase tracking-wider text-[10px]">Actions ({selectedMacro.actions.length})</span>
                    {selectedMacro.recordedDurationMs && (
                      <span className="font-mono text-[10px] text-emerald-400">Duration: {(selectedMacro.recordedDurationMs / 1000).toFixed(2)}s</span>
                    )}
                  </div>

                  <div className="max-h-[140px] overflow-y-auto border border-slate-850 rounded bg-slate-900/40 p-2 text-[10px] space-y-1 font-mono">
                    {selectedMacro.actions.map((act, index) => (
                      <div key={act.id} className="flex justify-between text-slate-400 border-b border-slate-900 pb-1">
                        <span className="text-indigo-400 font-semibold">{index + 1}. {act.type.toUpperCase()}</span>
                        <span>P{act.pointerId}</span>
                        {act.x !== undefined && <span>X:{act.x} Y:{act.y}</span>}
                        <span className="text-slate-500">+{act.delayMs}ms</span>
                      </div>
                    ))}
                    {selectedMacro.actions.length === 0 && (
                      <div className="text-center py-6 text-xs text-slate-500 italic">No events. Use REAL CAPTURE or MANUAL to record.</div>
                    )}
                  </div>
                </div>

                <div className="pt-2 grid grid-cols-1 sm:grid-cols-3 gap-3 items-center">
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 uppercase">Speed</label>
                    <select value={playbackSpeed} onChange={(e) => setPlaybackSpeed(parseFloat(e.target.value))}
                      className="w-full bg-slate-900 text-slate-100 text-xs px-2.5 py-1.5 rounded border border-slate-800">
                      <option value="0.5">0.5x (Slow)</option>
                      <option value="1.0">1.0x (Normal)</option>
                      <option value="1.5">1.5x (Fast)</option>
                      <option value="2.0">2.0x (Hyper)</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 uppercase">Loop</label>
                    <select value={loopCount} onChange={(e) => setLoopCount(parseInt(e.target.value))}
                      className="w-full bg-slate-900 text-slate-100 text-xs px-2.5 py-1.5 rounded border border-slate-800">
                      <option value="0">No loop</option>
                      <option value="1">2x</option>
                      <option value="2">3x</option>
                      <option value="4">5x</option>
                      <option value="9">10x</option>
                      <option value="-1">∞ (Infinite)</option>
                    </select>
                  </div>
                  <div className="flex flex-col">
                    <label className="block text-[10px] font-semibold text-slate-450 mb-1 uppercase opacity-0 sm:block">&nbsp;</label>
                    <button
                      onClick={handleTriggerPlayback}
                      disabled={isPlaying || selectedMacro.actions.length === 0}
                      className="w-full py-2 bg-gradient-to-r from-indigo-600 to-violet-600 hover:from-indigo-500 hover:to-violet-500 disabled:opacity-30 text-white font-bold text-xs rounded shadow flex items-center justify-center gap-1.5"
                    >
                      <FastForward className="w-3.5 h-3.5" /> PLAY
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="p-8 text-center text-slate-550 italic">No macro selected. Create one to begin.</div>
        )}

        <div className="p-3 bg-indigo-950/20 rounded border border-indigo-900/40 mt-4 flex items-start gap-2.5">
          <Info className="w-4 h-4 text-indigo-400 shrink-0 mt-0.5" />
          <p className="text-[10px] text-slate-350 leading-relaxed text-justify">
            <strong>REAL CAPTURE</strong> uses AccessibilityService to record actual touch events on screen.
            <strong>Manual</strong> lets you input coordinates by hand. Both produce identical playback format.
          </p>
        </div>
      </div>
    </div>
  );
}
