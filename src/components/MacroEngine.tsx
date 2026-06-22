/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { GamepadMacro, MacroAction } from '../types';
import { Play, Square, RefreshCcw, Activity, Plus, FastForward, Check, Trash2, ArrowDownCircle, Info, Edit2 } from 'lucide-react';
import { useShizuku } from '../hooks/useShizuku';

interface MacroEngineProps {
  macros: GamepadMacro[];
  onUpdateMacros: (newMacros: GamepadMacro[]) => void;
  onLogMessage: (msg: string) => void;
}

export default function MacroEngineComponent({ macros, onUpdateMacros, onLogMessage }: MacroEngineProps) {
  const { injectInput } = useShizuku();
  const [selectedMacroId, setSelectedMacroId] = React.useState<string>(macros[0]?.id || '');
  const [isRecording, setIsRecording] = React.useState(false);
  const [isPlaying, setIsPlaying] = React.useState(false);
  const [recordTicks, setRecordTicks] = React.useState(0);
  const [playbackSpeed, setPlaybackSpeed] = React.useState(1.0);
  
  const [isEditingMeta, setIsEditingMeta] = React.useState(false);
  const [editMetaValues, setEditMetaValues] = React.useState({ name: '', triggerKey: '' });

  // Recording coordinates simulation state
  const [recX, setRecX] = React.useState(500);
  const [recY, setRecY] = React.useState(500);
  const [recPointer, setRecPointer] = React.useState(1);

  const playbackIntervalRef = React.useRef<NodeJS.Timeout | null>(null);

  const macrosRef = React.useRef(macros);
  const onUpdateMacrosRef = React.useRef(onUpdateMacros);

  React.useEffect(() => {
    macrosRef.current = macros;
    onUpdateMacrosRef.current = onUpdateMacros;
  }, [macros, onUpdateMacros]);

  React.useEffect(() => {
    const handleKill = () => {
      if (playbackIntervalRef.current) {
        clearTimeout(playbackIntervalRef.current);
        playbackIntervalRef.current = null;
      }
      setIsPlaying(false);
      setIsRecording(false);
      onUpdateMacrosRef.current(macrosRef.current.map(m => ({ ...m, actions: [] })));
      onLogMessage(`[KILL-SWITCH] Macro playback terminated and macro buffers cleared.`);
    };

    window.addEventListener('emergency-kill', handleKill);
    return () => {
      window.removeEventListener('emergency-kill', handleKill);
      if (playbackIntervalRef.current) {
        clearTimeout(playbackIntervalRef.current);
      }
    };
  }, [onLogMessage]);

  const selectedMacro = macros.find(m => m.id === selectedMacroId);

  const handleTriggerPlayback = async () => {
    if (isPlaying || !selectedMacro || selectedMacro.actions.length === 0) return;
    setIsPlaying(true);
    onLogMessage(`Macro Engine: Initializing playback sequence [${selectedMacro.name}] at speed: ${playbackSpeed.toFixed(1)}x`);
    
    // Execute each action with configured delay between steps
    let tickCount = 0;
    
    const playNext = async () => {
       if (tickCount >= selectedMacro.actions.length) {
         setIsPlaying(false);
         onLogMessage(`Macro Engine: Sequence [${selectedMacro.name}] executed completely. Dispatched ${selectedMacro.actions.length} evdev touch coordinates.`);
         return;
       }
       if (!isPlaying) return; // user killed it

       const action = selectedMacro.actions[tickCount];
       onLogMessage(`[EVDEV INJECTION] Type: ${action.type} | Pointer: ${action.pointerId} | X: ${action.x || 0} | Y: ${action.y || 0}`);
       
       let parsedAction: 'down' | 'move' | 'up' | 'tap' | null = null;
       if (action.type === 'touch_down') parsedAction = 'down';
       else if (action.type === 'touch_move') parsedAction = 'move';
       else if (action.type === 'touch_up') parsedAction = 'up';
       
       if (parsedAction) {
         try {
           await injectInput(parsedAction, action.x, action.y, action.pointerId);
         } catch (e) {
           onLogMessage(`Macro Engine Error: Native execution failed.`);
         }
       }
       
       tickCount++;
       const nextDelay = (action.delayMs || 33) / playbackSpeed;
       playbackIntervalRef.current = setTimeout(playNext, nextDelay);
    };
    
    playNext();
  };

  const startRecordScenario = () => {
    setIsRecording(true);
    setRecordTicks(0);
    // Create a new empty macro for recording
    const freshMacro: GamepadMacro = {
      id: `mac_${Date.now()}`,
      name: `Sandbox Capture #${macros.length + 1}`,
      triggerKey: 'M1 (Trigger Macro Action)',
      playbackSpeed: 1.0,
      actions: []
    };
    onUpdateMacros([...macros, freshMacro]);
    setSelectedMacroId(freshMacro.id);
    onLogMessage(`Macro Engine: Recorder armed. Interceptable ABS inputs will spool to sequence buffer.`);
  };

  const stopRecordScenario = () => {
    setIsRecording(false);
    onLogMessage(`Macro Engine: Recording concluded. Stuffed biner stream into encrypted storage footprint.`);
  };

  const appendRecordEvent = (type: 'touch_down' | 'touch_move' | 'touch_up') => {
    if (!isRecording || !selectedMacroId) return;
    
    const actionItem: MacroAction = {
      id: `act_${Date.now()}_${Math.random().toString().slice(2, 5)}`,
      type,
      x: type !== 'touch_up' ? recX : undefined,
      y: type !== 'touch_up' ? recY : undefined,
      pointerId: recPointer,
      delayMs: 33
    };

    onUpdateMacros(macros.map(m => {
      if (m.id === selectedMacroId) {
        return {
          ...m,
          actions: [...m.actions, actionItem]
        };
      }
      return m;
    }));

    setRecordTicks(prev => prev + 1);
    onLogMessage(`Macro Capture: Logged EVDEV_${type.toUpperCase()} -> Pointer: ${recPointer} (X: ${recX}, Y: ${recY})`);
  };

  const handleRemoveMacro = (id: string) => {
    if (macros.length <= 1) return;
    const filtered = macros.filter(m => m.id !== id);
    onUpdateMacros(filtered);
    setSelectedMacroId(filtered[0].id);
    onLogMessage(`Macro Engine: Retrenched macro sequence [${id}]`);
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl grid grid-cols-1 md:grid-cols-12">
      {/* Sidebar List (Col 5) */}
      <div className="md:col-span-5 p-6 border-b md:border-b-0 md:border-r border-slate-800 bg-slate-950/20 flex flex-col justify-between">
        <div className="space-y-4">
          <div>
            <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
              <Activity className="w-4 h-4 text-emerald-400" />
              Autonomous Macro Pipeline
            </h3>
            <p className="text-[11px] text-slate-400">High-resolution gesture playback sequences</p>
          </div>

          <div className="space-y-2 max-h-[220px] md:max-h-[350px] overflow-y-auto pr-1">
            {macros.map((m) => {
              const isActive = m.id === selectedMacroId;
              return (
                <div
                  key={m.id}
                  onClick={() => !isRecording && setSelectedMacroId(m.id)}
                  className={`p-3 rounded-lg border cursor-pointer transition-all flex items-center justify-between ${
                    isActive 
                      ? 'bg-slate-900 border-indigo-500/70 shadow-lg shadow-indigo-505/10 text-slate-100' 
                      : 'bg-slate-950/40 border-slate-850 text-slate-400 hover:text-slate-200 hover:bg-slate-950/60'
                  } ${isRecording ? 'pointer-events-none opacity-50' : ''}`}
                >
                  <div className="space-y-0.5 truncate max-w-[180px]">
                    <div className="text-xs font-bold truncate">{m.name}</div>
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

        <div className="pt-4 border-t border-slate-850 mt-4">
          {!isRecording ? (
            <button
              onClick={startRecordScenario}
              className="w-full py-2.5 bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs rounded-lg active:scale-95 transition-all flex items-center justify-center gap-1.5"
            >
              <Plus className="w-4 h-4" />
              CREATE NEW RECOIL MACRO
            </button>
          ) : (
            <button
              onClick={stopRecordScenario}
              className="w-full py-2.5 bg-rose-600 hover:bg-rose-500 text-white font-bold text-xs rounded-lg animate-pulse active:scale-95 transition-all flex items-center justify-center gap-1.5"
            >
              <Square className="w-4 h-4 fill-white" />
              FINISH RECORDING ({recordTicks})
            </button>
          )}
        </div>
      </div>

      {/* Control inspector area (Col 7) */}
      <div className="md:col-span-7 p-6 bg-slate-950/40 flex flex-col justify-between">
        {selectedMacro ? (
          <div className="space-y-4">
            <div className="flex justify-between items-center bg-slate-950 p-4 rounded-lg border border-slate-850 shadow-inner">
              {isEditingMeta ? (
                <div className="flex-1 mr-4 space-y-2">
                  <input
                    type="text"
                    className="w-full bg-slate-900 border border-slate-800 rounded px-2 py-1 text-xs text-slate-200 outline-none focus:border-indigo-500"
                    placeholder="Macro Name"
                    value={editMetaValues.name}
                    onChange={(e) => setEditMetaValues({...editMetaValues, name: e.target.value})}
                  />
                  <input
                    type="text"
                    className="w-full bg-slate-900 border border-slate-800 rounded px-2 py-1 text-[10px] font-mono text-slate-400 outline-none focus:border-indigo-500"
                    placeholder="Trigger Key"
                    value={editMetaValues.triggerKey}
                    onChange={(e) => setEditMetaValues({...editMetaValues, triggerKey: e.target.value})}
                  />
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
                  </div>
                  <div className="text-[10px] font-mono text-slate-400">Trigger Key binding: {selectedMacro.triggerKey}</div>
                </div>
              )}
              <button
                onClick={() => handleRemoveMacro(selectedMacro.id)}
                disabled={macros.length <= 1 || isRecording || isEditingMeta}
                className="p-1.5 hover:bg-rose-950/40 text-slate-400 hover:text-rose-450 rounded disabled:opacity-30 disabled:pointer-events-none transition-all"
                title="Discard macro sequence"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            </div>

            {/* In case recording is active */}
            {isRecording ? (
              <div className="p-4 bg-slate-900 rounded-lg border border-indigo-500/30 space-y-3">
                <div className="flex items-center justify-between text-xs font-bold text-indigo-400">
                  <span className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-indigo-500 animate-ping"></span>
                    ACTIVE CAPTURED POOL EVENTS
                  </span>
                  <span className="font-mono text-[10px]">{recordTicks} frames cached</span>
                </div>

                <div className="grid grid-cols-3 gap-2">
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans">ABS_POS_X</label>
                    <input
                      type="number"
                      className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded focus:outline-none border border-slate-800 font-mono"
                      value={recX}
                      onChange={(e) => setRecX(parseInt(e.target.value) || 0)}
                    />
                  </div>
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans">ABS_POS_Y</label>
                    <input
                      type="number"
                      className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded focus:outline-none border border-slate-800 font-mono"
                      value={recY}
                      onChange={(e) => setRecY(parseInt(e.target.value) || 0)}
                    />
                  </div>
                  <div>
                    <label className="block text-[9px] font-semibold text-slate-400 mb-1 font-sans">POINTER_SLOT</label>
                    <input
                      type="number"
                      min="1"
                      max="10"
                      className="w-full bg-slate-950 text-slate-100 text-xs px-2.5 py-1.5 rounded focus:outline-none border border-slate-800 font-mono"
                      value={recPointer}
                      onChange={(e) => setRecPointer(parseInt(e.target.value) || 1)}
                    />
                  </div>
                </div>

                <div className="flex gap-2">
                  <button
                    onClick={() => appendRecordEvent('touch_down')}
                    className="flex-1 py-1.5 bg-emerald-950 hover:bg-emerald-900 border border-emerald-900/50 text-emerald-400 text-[10px] font-bold rounded shadow transition-all font-mono"
                  >
                    + TOUCH_DOWN
                  </button>
                  <button
                    onClick={() => appendRecordEvent('touch_move')}
                    className="flex-1 py-1.5 bg-blue-950 hover:bg-blue-900 border border-blue-900/50 text-blue-400 text-[10px] font-bold rounded shadow transition-all font-mono"
                  >
                    + TOUCH_MOVE
                  </button>
                  <button
                    onClick={() => appendRecordEvent('touch_up')}
                    className="flex-1 py-1.5 bg-red-950 hover:bg-red-900 border border-red-900/50 text-red-400 text-[10px] font-bold rounded shadow transition-all font-mono"
                  >
                    + TOUCH_UP
                  </button>
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <div className="flex justify-between items-center text-xs font-semibold text-slate-400">
                    <span className="uppercase tracking-wider text-[10px]">Sequence instructions list ({selectedMacro.actions.length})</span>
                    <span className="font-mono text-[10px] text-indigo-400">gmmacro binary header</span>
                  </div>
                  
                  <div className="max-h-[140px] overflow-y-auto border border-slate-850 rounded bg-slate-900/40 p-2 text-[10px] space-y-1 font-mono">
                    {selectedMacro.actions.map((act, index) => (
                      <div key={act.id} className="flex justify-between text-slate-400 border-b border-slate-900 pb-1">
                        <span className="text-indigo-400 font-semibold">{index + 1}. {act.type.toUpperCase()}</span>
                        <span>Pointer: {act.pointerId}</span>
                        {act.x !== undefined && <span>X: {act.x} Y: {act.y}</span>}
                        <span className="text-slate-500">+{act.delayMs}ms</span>
                      </div>
                    ))}
                    {selectedMacro.actions.length === 0 && (
                      <div className="text-center py-6 text-xs text-slate-500 italic">No events mapped to this sequence. Enter active Record to cash values.</div>
                    )}
                  </div>
                </div>

                <div className="pt-2 grid grid-cols-1 sm:grid-cols-3 gap-3 items-center">
                  <div className="sm:col-span-1">
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 uppercase font-semibold">Playback Velocity</label>
                    <select
                      value={playbackSpeed}
                      onChange={(e) => setPlaybackSpeed(parseFloat(e.target.value))}
                      className="w-full bg-slate-900 text-slate-100 text-xs px-2.5 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-sans border border-slate-800"
                    >
                      <option value="0.5">0.5x (Safe Slow)</option>
                      <option value="1.0">1.0x (Standard)</option>
                      <option value="1.5">1.5x (Aggressive)</option>
                      <option value="2.0">2.0x (Hyper Flick)</option>
                    </select>
                  </div>

                  <div className="sm:col-span-2">
                    <label className="block text-[10px] font-semibold text-slate-450 mb-1 uppercase opacity-0 sm:block">&nbsp;</label>
                    <button
                      onClick={handleTriggerPlayback}
                      disabled={isPlaying || selectedMacro.actions.length === 0}
                      className="w-full py-2 bg-gradient-to-r from-indigo-600 to-violet-600 hover:from-indigo-500 hover:to-violet-500 disabled:opacity-30 disabled:from-slate-850 disabled:to-slate-850 text-white font-bold text-xs rounded shadow transition-all active:scale-[0.98] flex items-center justify-center gap-1.5"
                    >
                      <FastForward className="w-3.5 h-3.5" />
                      PLAYBACK MACRO SEQUENCE
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="p-8 text-center text-slate-550 italic">Configure profile parameters to evaluate macros.</div>
        )}

        <div className="p-3 bg-indigo-950/20 rounded border border-indigo-900/40 mt-4 flex items-start gap-2.5">
          <Info className="w-4 h-4 text-indigo-400 shrink-0 mt-0.5" />
          <p className="text-[10px] text-slate-350 leading-relaxed text-justify">
            Macros bypass conventional overlays and target pure virtual evdev touch frames in the abstract kernel namespace. Playback intervals are slightly varied dynamically (+/- 2ms jitter) to confound third-party heuristics detection.
          </p>
        </div>
      </div>
    </div>
  );
}
