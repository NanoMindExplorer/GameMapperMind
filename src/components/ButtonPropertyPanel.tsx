import React from 'react';
import { Trash2, Gamepad2, Repeat, Timer, Spline, Plus, X, Play, Zap, ToggleLeft, Hand } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';
import { VirtualButton, InteractionType, GesturePoint } from '../types';

export default function ButtonPropertyPanel({ h, macros }: { h: OverlayWysiwygHook; macros?: any[] }) {
  if (h.isNativeOverlay) return null;

  const { selectedButton } = h;

  // INTERACTION-EXPANSION: Learn Trigger mode (single + chord)
  const [learnMode, setLearnMode] = React.useState<'off' | 'single' | 'chord'>('off');
  const [chordInputs, setChordInputs] = React.useState<string[]>([]);

  React.useEffect(() => {
    if (learnMode === 'off') return;
    const handleBtn = (e: Event) => {
      const data = (e as CustomEvent).detail;
      if (data.value === 1 && data.buttonName && !data.buttonName.startsWith('ERROR_')) {
        if (learnMode === 'single') {
          h.handleUpdateBtnProperty('trigger', { type: 'button', inputs: [data.buttonName] });
          h.handleUpdateBtnProperty('mappedKey', data.buttonName);
          h.onLogMessage(`[LEARN] Trigger assigned: ${data.buttonName}`);
          setLearnMode('off');
        } else if (learnMode === 'chord') {
          // Add to chord inputs (avoid duplicates)
          setChordInputs(prev => {
            if (prev.includes(data.buttonName)) return prev;
            const next = [...prev, data.buttonName];
            h.onLogMessage(`[LEARN] Chord +${data.buttonName} (${next.join(' + ')})`);
            return next;
          });
        }
      }
    };
    window.addEventListener('native-gamepad-button', handleBtn);
    return () => window.removeEventListener('native-gamepad-button', handleBtn);
  }, [learnMode, h]);

  const finishChord = () => {
    if (chordInputs.length > 0) {
      h.handleUpdateBtnProperty('trigger', { type: 'chord', inputs: chordInputs });
      h.handleUpdateBtnProperty('mappedKey', chordInputs[0]);
      h.onLogMessage(`[LEARN] Chord trigger assigned: ${chordInputs.join(' + ')}`);
    }
    setChordInputs([]);
    setLearnMode('off');
  };

  const cancelLearn = () => {
    setChordInputs([]);
    setLearnMode('off');
  };

  // Gesture point editing
  const [editingGesture, setEditingGesture] = React.useState(false);

  const addGesturePoint = () => {
    const current = selectedButton?.gesturePoints || [];
    const lastPoint = current[current.length - 1];
    const newPoint: GesturePoint = {
      x: lastPoint ? lastPoint.x + 10 : (selectedButton?.x || 50) + 10,
      y: lastPoint ? lastPoint.y + 10 : (selectedButton?.y || 50) + 10,
      delayMs: 50
    };
    h.handleUpdateBtnProperty('gesturePoints', [...current, newPoint]);
  };

  const updateGesturePoint = (index: number, field: keyof GesturePoint, value: number) => {
    const current = selectedButton?.gesturePoints || [];
    const updated = current.map((p, i) => i === index ? { ...p, [field]: value } : p);
    h.handleUpdateBtnProperty('gesturePoints', updated);
  };

  const removeGesturePoint = (index: number) => {
    const current = selectedButton?.gesturePoints || [];
    h.handleUpdateBtnProperty('gesturePoints', current.filter((_, i) => i !== index));
  };

  const interactionType = selectedButton?.interactionType || 'hold';

  // Interaction type icon for display
  const getInteractionIcon = () => {
    switch (interactionType) {
      case 'turbo': return <Zap className="w-3 h-3 text-amber-400" />;
      case 'toggle': return <ToggleLeft className="w-3 h-3 text-purple-400" />;
      case 'charge': return <Timer className="w-3 h-3 text-blue-400" />;
      case 'gesture': return <Spline className="w-3 h-3 text-cyan-400" />;
      case 'tap': return <Hand className="w-3 h-3 text-green-400" />;
      default: return null;
    }
  };

  return (
    <div className="absolute right-0 top-0 bottom-0 w-72 p-4 bg-slate-950/95 border-l border-slate-800 flex flex-col justify-between overflow-y-auto custom-scrollbar z-30 shadow-2xl" style={{ maxHeight: '100%' }}>
      <div className="space-y-6">
        <div>
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-1">Overlay Node Inspector</h4>
          <p className="text-[11px] text-slate-400 leading-relaxed">Customize tactile physical execution targets</p>
        </div>

        {selectedButton ? (
          <div className="space-y-4">
            <div className="p-3.5 bg-slate-950 rounded-lg border border-slate-800 space-y-3 shadow-inner">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-mono font-bold text-indigo-400 flex items-center gap-1.5">
                  {getInteractionIcon()}
                  NODE: {selectedButton.id.slice(0, 8)}
                </span>
                <button
                  onClick={() => h.handleRemoveButton(selectedButton.id)}
                  className="p-1 hover:bg-rose-950/60 text-slate-400 hover:text-rose-400 rounded transition-colors"
                  title="Remove virtual node mapping"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>

              <div className="space-y-2">
                <div>
                  <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Display Label</label>
                  <input
                    type="text"
                    className="w-full bg-slate-900 text-slate-100 text-xs px-3 py-2 rounded focus:outline-none focus:border-indigo-500 font-sans border border-slate-800"
                    value={selectedButton.label}
                    onChange={(e) => h.handleUpdateBtnProperty('label', e.target.value)}
                  />
                </div>

                {/* TRIGGER SECTION: Learn single + chord */}
                <div>
                  <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Trigger (Physical Button)</label>
                  <div className="px-3 py-2 bg-slate-900 text-slate-100 text-xs rounded border border-slate-800 font-mono mb-2">
                    {selectedButton.trigger?.inputs?.join(' + ') || selectedButton.mappedKey || '—'}
                    {selectedButton.trigger?.type === 'chord' && (
                      <span className="ml-2 text-[9px] text-purple-400 uppercase">CHORD</span>
                    )}
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      onClick={() => { setLearnMode(learnMode === 'single' ? 'off' : 'single'); setChordInputs([]); }}
                      className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-center gap-1 transition-colors ${
                        learnMode === 'single'
                          ? 'bg-rose-600 text-white animate-pulse'
                          : 'bg-indigo-600 hover:bg-indigo-500 text-white'
                      }`}
                    >
                      <Gamepad2 className="w-3 h-3" />
                      {learnMode === 'single' ? 'Listening...' : 'Learn Single'}
                    </button>
                    <button
                      onClick={() => { setLearnMode(learnMode === 'chord' ? 'off' : 'chord'); setChordInputs([]); }}
                      className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-center gap-1 transition-colors ${
                        learnMode === 'chord'
                          ? 'bg-rose-600 text-white animate-pulse'
                          : 'bg-purple-600 hover:bg-purple-500 text-white'
                      }`}
                    >
                      <Gamepad2 className="w-3 h-3" />
                      {learnMode === 'chord' ? 'Add...' : 'Learn Chord'}
                    </button>
                  </div>
                  {learnMode === 'single' && (
                    <p className="text-[10px] text-amber-400 mt-1 animate-pulse">Press any gamepad button to assign...</p>
                  )}
                  {learnMode === 'chord' && (
                    <div className="mt-2 p-2 bg-purple-950/40 border border-purple-800 rounded space-y-2">
                      <p className="text-[10px] text-purple-300">Press buttons to add to chord. Tap "Done" when finished.</p>
                      {chordInputs.length > 0 && (
                        <div className="flex flex-wrap gap-1">
                          {chordInputs.map(input => (
                            <span key={input} className="px-1.5 py-0.5 bg-purple-800 text-purple-200 text-[9px] rounded font-mono">{input}</span>
                          ))}
                        </div>
                      )}
                      <div className="flex gap-2">
                        <button onClick={finishChord} className="flex-1 px-2 py-1 bg-emerald-600 hover:bg-emerald-500 text-white text-[10px] font-bold rounded">Done</button>
                        <button onClick={cancelLearn} className="flex-1 px-2 py-1 bg-slate-700 hover:bg-slate-600 text-white text-[10px] font-bold rounded">Cancel</button>
                      </div>
                    </div>
                  )}
                </div>

                {/* INTERACTION TYPE SELECTOR */}
                {selectedButton.type !== 'analog_stick' && (
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Interaction Type</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800 cursor-pointer"
                      value={interactionType}
                      onChange={(e) => h.handleUpdateBtnProperty('interactionType', e.target.value as InteractionType)}
                    >
                      <option value="hold">Hold (press &amp; release)</option>
                      <option value="tap">Tap (single quick touch)</option>
                      <option value="turbo">Turbo (auto-repeat)</option>
                      <option value="toggle">Toggle (press to lock)</option>
                      <option value="charge">Charge (hold then release)</option>
                      <option value="gesture">Gesture (multi-point path)</option>
                      {macros && macros.length > 0 && <option value="macro">Macro (trigger recorded sequence)</option>}
                    </select>
                  </div>
                )}

                {/* DYNAMIC PARAMS per interactionType */}
                {interactionType === 'turbo' && (
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase flex items-center gap-1">
                        <Repeat className="w-3 h-3" /> Repeat Interval
                      </label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.repeatIntervalMs || 50} ms</span>
                    </div>
                    <input type="range" min="20" max="200" step="5"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.repeatIntervalMs || 50}
                      onChange={(e) => h.handleUpdateBtnProperty('repeatIntervalMs', parseInt(e.target.value))}
                    />
                  </div>
                )}

                {interactionType === 'charge' && (
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase flex items-center gap-1">
                        <Timer className="w-3 h-3" /> Charge Threshold
                      </label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.chargeThresholdMs || 500} ms</span>
                    </div>
                    <input type="range" min="100" max="3000" step="50"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.chargeThresholdMs || 500}
                      onChange={(e) => h.handleUpdateBtnProperty('chargeThresholdMs', parseInt(e.target.value))}
                    />
                  </div>
                )}

                {interactionType === 'tap' && (
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Tap Duration</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.tapDuration || 60} ms</span>
                    </div>
                    <input type="range" min="20" max="300" step="5"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.tapDuration || 60}
                      onChange={(e) => h.handleUpdateBtnProperty('tapDuration', parseInt(e.target.value))}
                    />
                  </div>
                )}

                {/* GESTURE POINT EDITOR */}
                {interactionType === 'gesture' && (
                  <div className="p-2.5 bg-cyan-950/30 border border-cyan-800/50 rounded space-y-2">
                    <div className="flex items-center justify-between">
                      <label className="text-[10px] font-semibold text-cyan-300 font-sans uppercase flex items-center gap-1">
                        <Spline className="w-3 h-3" /> Gesture Path Points
                      </label>
                      <button onClick={addGesturePoint} className="p-1 bg-cyan-600 hover:bg-cyan-500 text-white rounded" title="Add point">
                        <Plus className="w-3 h-3" />
                      </button>
                    </div>
                    {(selectedButton.gesturePoints || []).length === 0 && (
                      <p className="text-[10px] text-slate-500">No points. Tap + to add. Start point = button position.</p>
                    )}
                    {(selectedButton.gesturePoints || []).map((pt, i) => (
                      <div key={i} className="flex items-center gap-1 bg-slate-900 p-1.5 rounded">
                        <span className="text-[9px] font-mono text-cyan-400 w-6">P{i+1}</span>
                        <input type="number" className="w-12 bg-slate-950 text-slate-200 text-[10px] px-1 py-0.5 rounded border border-slate-700 font-mono"
                          value={pt.x} onChange={(e) => updateGesturePoint(i, 'x', parseFloat(e.target.value) || 0)} title="X %" />
                        <input type="number" className="w-12 bg-slate-950 text-slate-200 text-[10px] px-1 py-0.5 rounded border border-slate-700 font-mono"
                          value={pt.y} onChange={(e) => updateGesturePoint(i, 'y', parseFloat(e.target.value) || 0)} title="Y %" />
                        <input type="number" className="w-12 bg-slate-950 text-slate-200 text-[10px] px-1 py-0.5 rounded border border-slate-700 font-mono"
                          value={pt.delayMs} onChange={(e) => updateGesturePoint(i, 'delayMs', parseInt(e.target.value) || 0)} title="delay ms" />
                        <button onClick={() => removeGesturePoint(i)} className="p-0.5 text-rose-400 hover:bg-rose-950/60 rounded">
                          <X className="w-3 h-3" />
                        </button>
                      </div>
                    ))}
                    {(selectedButton.gesturePoints || []).length > 0 && (
                      <p className="text-[9px] text-slate-500">Path: start → P1 → P2 → ... → release. Coords are % of screen.</p>
                    )}
                  </div>
                )}

                {/* MACRO SELECTOR */}
                {interactionType === 'macro' && macros && macros.length > 0 && (
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Macro to Trigger</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={selectedButton.macroId || ''}
                      onChange={(e) => h.handleUpdateBtnProperty('macroId', e.target.value)}
                    >
                      <option value="">— Select macro —</option>
                      {macros.map((m: any) => (
                        <option key={m.id} value={m.id}>{m.name} ({m.actions?.length || 0} steps)</option>
                      ))}
                    </select>
                  </div>
                )}

                {/* STICK MODE for analog_stick */}
                {selectedButton.type === 'analog_stick' && (
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Stick Mode</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={selectedButton.stickMode || 'joystick'}
                      onChange={(e) => h.handleUpdateBtnProperty('stickMode', e.target.value as 'joystick' | 'drag')}
                    >
                      <option value="joystick">Joystick (virtual stick — relative to center)</option>
                      <option value="drag">Drag (continuous move — mortar/sniper aim)</option>
                    </select>
                  </div>
                )}

                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Legacy Key</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={selectedButton.mappedKey}
                      onChange={(e) => {
                        const val = e.target.value;
                        const updates: Partial<VirtualButton> = { mappedKey: val as any };
                        const codeMap: Record<string, number> = {
                          'A': 96, 'B': 97, 'X': 99, 'Y': 100, 'LB': 102, 'RB': 103,
                          'LT': 104, 'RT': 105, 'L3': 106, 'R3': 107, 'SELECT': 109, 'START': 108,
                          'DPAD_UP': 19, 'DPAD_DOWN': 20, 'DPAD_LEFT': 21, 'DPAD_RIGHT': 22
                        };
                        if (codeMap[val]) updates.androidEventCode = codeMap[val];
                        h.handleUpdateBtnProperties(updates);
                      }}
                    >
                      <optgroup label="Buttons">
                        <option value="A">A (Cross)</option><option value="B">B (Circle)</option>
                        <option value="X">X (Square)</option><option value="Y">Y (Triangle)</option>
                        <option value="LB">L1 / LB</option><option value="RB">R1 / RB</option>
                        <option value="LT">L2 / LT</option><option value="RT">R2 / RT</option>
                        <option value="SELECT">Select</option><option value="START">Start</option>
                        <option value="L3">L3</option><option value="R3">R3</option>
                      </optgroup>
                      <optgroup label="D-Pad">
                        <option value="DPAD_UP">Up</option><option value="DPAD_DOWN">Down</option>
                        <option value="DPAD_LEFT">Left</option><option value="DPAD_RIGHT">Right</option>
                      </optgroup>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Event Code</label>
                    <input type="number"
                      className="w-full bg-slate-900 text-slate-100 text-xs px-3 py-1.5 rounded border border-slate-800 font-mono"
                      value={selectedButton.androidEventCode}
                      onChange={(e) => h.handleUpdateBtnProperty('androidEventCode', parseInt(e.target.value) || 0)}
                    />
                  </div>
                </div>
              </div>

              {selectedButton.type === 'analog_stick' && (
                <div className="p-3.5 bg-slate-950 rounded-lg border border-slate-800 space-y-3 shadow-inner">
                  <h5 className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Analog Stick Properties</h5>
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Deadzone</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.deadzone || 0.15}</span>
                    </div>
                    <input type="range" min="0" max="1" step="0.01"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.deadzone || 0.15}
                      onChange={(e) => h.handleUpdateBtnProperty('deadzone', parseFloat(e.target.value))}
                    />
                  </div>
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Sensitivity</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.sensitivity || 1.0}</span>
                    </div>
                    <input type="range" min="0.1" max="5.0" step="0.1"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.sensitivity || 1.0}
                      onChange={(e) => h.handleUpdateBtnProperty('sensitivity', parseFloat(e.target.value))}
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Sensitivity Curve</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded border border-slate-800 font-mono"
                      value={selectedButton.sensitivityCurve || 'linear'}
                      onChange={(e) => h.handleUpdateBtnProperty('sensitivityCurve', e.target.value)}
                    >
                      <option value="linear">Linear</option>
                      <option value="exponential">Exponential (smooth)</option>
                      <option value="parabolic">Parabolic (aggressive)</option>
                      <option value="concave">Concave (precise near center)</option>
                    </select>
                  </div>
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center border-2 border-dashed border-slate-800 rounded-xl p-6 text-center opacity-70">
            <div className="w-12 h-12 rounded-full bg-slate-900 border border-slate-800 flex items-center justify-center mb-3">
              <span className="text-slate-500 font-mono text-[10px]">null</span>
            </div>
            <h4 className="text-sm font-semibold text-slate-300">No Target Selected</h4>
            <p className="text-[11px] text-slate-500 max-w-[200px] mt-1">
              Select a visual overlay node from the canvas to edit its properties.
            </p>
          </div>
        )}
      </div>

      <div className="text-[10px] text-slate-500 font-mono text-center pt-6 border-t border-slate-900">
        Nexion Engine Layout Profile Configurator
      </div>
    </div>
  );
}
