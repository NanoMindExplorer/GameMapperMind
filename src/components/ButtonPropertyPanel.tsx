import React from 'react';
import { Trash2, Gamepad2, Zap, Repeat, ToggleLeft, Timer, Spline, Hand } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';
import { VirtualButton, InteractionType } from '../types';

export default function ButtonPropertyPanel({ h }: { h: OverlayWysiwygHook }) {
  if (h.isNativeOverlay) return null;

  const { selectedButton } = h;

  // INTERACTION-EXPANSION: Learn Trigger mode
  const [isLearning, setIsLearning] = React.useState(false);

  React.useEffect(() => {
    if (!isLearning) return;
    const handleBtn = (e: Event) => {
      const data = (e as CustomEvent).detail;
      if (data.value === 1 && data.buttonName && !data.buttonName.startsWith('ERROR_')) {
        // Assign this button as the trigger
        const currentTrigger = selectedButton?.trigger || { type: 'button' as const, inputs: [] };
        // Replace inputs with the newly learned button (single-button trigger)
        h.handleUpdateBtnProperty('trigger', { type: 'button', inputs: [data.buttonName] });
        h.handleUpdateBtnProperty('mappedKey', data.buttonName);
        h.onLogMessage(`[LEARN] Trigger assigned: ${data.buttonName}`);
        setIsLearning(false);
      }
    };
    window.addEventListener('native-gamepad-button', handleBtn);
    return () => window.removeEventListener('native-gamepad-button', handleBtn);
  }, [isLearning, selectedButton, h]);

  const interactionType = selectedButton?.interactionType || 'hold';

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
                <span className="text-[10px] font-mono font-bold text-indigo-400">NODE ID: {selectedButton.id}</span>
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

                {/* INTERACTION-EXPANSION: Learn Trigger button */}
                <div>
                  <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Trigger (Physical Button)</label>
                  <div className="flex gap-2">
                    <div className="flex-1 px-3 py-2 bg-slate-900 text-slate-100 text-xs rounded border border-slate-800 font-mono">
                      {selectedButton.trigger?.inputs?.join(' + ') || selectedButton.mappedKey || '—'}
                    </div>
                    <button
                      onClick={() => setIsLearning(!isLearning)}
                      className={`px-3 py-2 rounded text-xs font-bold flex items-center gap-1.5 transition-colors ${
                        isLearning
                          ? 'bg-rose-600 text-white animate-pulse'
                          : 'bg-indigo-600 hover:bg-indigo-500 text-white'
                      }`}
                      title="Press a gamepad button to assign it as trigger"
                    >
                      <Gamepad2 className="w-3.5 h-3.5" />
                      {isLearning ? 'Listening...' : 'Learn'}
                    </button>
                  </div>
                  {isLearning && (
                    <p className="text-[10px] text-amber-400 mt-1 animate-pulse">Press any gamepad button to assign...</p>
                  )}
                </div>

                {/* INTERACTION-EXPANSION: Interaction Type selector */}
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
                    </select>
                  </div>
                )}

                {/* INTERACTION-EXPANSION: Dynamic params based on interactionType */}
                {interactionType === 'turbo' && (
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase flex items-center gap-1">
                        <Repeat className="w-3 h-3" /> Repeat Interval
                      </label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.repeatIntervalMs || 50} ms</span>
                    </div>
                    <input
                      type="range" min="20" max="200" step="5"
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
                    <input
                      type="range" min="100" max="3000" step="50"
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
                    <input
                      type="range" min="20" max="300" step="5"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.tapDuration || 60}
                      onChange={(e) => h.handleUpdateBtnProperty('tapDuration', parseInt(e.target.value))}
                    />
                  </div>
                )}

                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Legacy Key (fallback)</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800 cursor-pointer"
                      value={selectedButton.mappedKey}
                      onChange={(e) => {
                        const val = e.target.value;
                        const updates: Partial<VirtualButton> = { mappedKey: val as any };
                        if (val === 'A') updates.androidEventCode = 96;
                        else if (val === 'B') updates.androidEventCode = 97;
                        else if (val === 'X') updates.androidEventCode = 99;
                        else if (val === 'Y') updates.androidEventCode = 100;
                        else if (val === 'LB') updates.androidEventCode = 102;
                        else if (val === 'RB') updates.androidEventCode = 103;
                        else if (val === 'LT') updates.androidEventCode = 104;
                        else if (val === 'RT') updates.androidEventCode = 105;
                        else if (val === 'L3') updates.androidEventCode = 106;
                        else if (val === 'R3') updates.androidEventCode = 107;
                        else if (val === 'DPAD_UP') updates.androidEventCode = 19;
                        else if (val === 'DPAD_DOWN') updates.androidEventCode = 20;
                        else if (val === 'DPAD_LEFT') updates.androidEventCode = 21;
                        else if (val === 'DPAD_RIGHT') updates.androidEventCode = 22;
                        else if (val === 'SELECT') updates.androidEventCode = 109;
                        else if (val === 'START') updates.androidEventCode = 108;
                        h.handleUpdateBtnProperties(updates);
                      }}
                    >
                      <optgroup label="Buttons">
                        <option value="A">A (Cross)</option>
                        <option value="B">B (Circle)</option>
                        <option value="X">X (Square)</option>
                        <option value="Y">Y (Triangle)</option>
                        <option value="LB">L1 / LB</option>
                        <option value="RB">R1 / RB</option>
                        <option value="LT">L2 / LT</option>
                        <option value="RT">R2 / RT</option>
                        <option value="SELECT">Select / Share</option>
                        <option value="START">Start / Options</option>
                        <option value="L3">L3 (Left Stick Click)</option>
                        <option value="R3">R3 (Right Stick Click)</option>
                      </optgroup>
                      <optgroup label="D-Pad">
                        <option value="DPAD_UP">D-Pad Up</option>
                        <option value="DPAD_DOWN">D-Pad Down</option>
                        <option value="DPAD_LEFT">D-Pad Left</option>
                        <option value="DPAD_RIGHT">D-Pad Right</option>
                      </optgroup>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Event Code</label>
                    <input
                      type="number"
                      className="w-full bg-slate-900 text-slate-100 text-xs px-3 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800"
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
                    <input
                      type="range" min="0" max="1" step="0.01"
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
                    <input
                      type="range" min="0.1" max="5.0" step="0.1"
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
