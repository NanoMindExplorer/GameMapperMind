import React from 'react';
import { Trash2 } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function ButtonPropertyPanel({ h }: { h: OverlayWysiwygHook }) {
  if (h.isNativeOverlay) return null;
  
  const { selectedButton } = h;
  
  return (
    <div className="w-full lg:w-80 p-6 bg-slate-950/40 flex flex-col justify-between shrink-0 overflow-y-auto custom-scrollbar" style={{ maxHeight: '100%' }}>
      <div className="space-y-6">
        <div>
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-1">Overlay Node Inspector</h4>
          <p className="text-[11px] text-slate-400 leading-relaxed">Customize tactile physical execution targets</p>
        </div>

        {selectedButton ? (
          <div className="space-y-4">
            {/* Card info */}
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

                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Tactile Target Key</label>
                    <select
                      className="w-full bg-slate-900 text-slate-100 text-[11px] px-1.5 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800 cursor-pointer"
                      value={selectedButton.mappedKey}
                      onChange={(e) => {
                        const val = e.target.value;
                        h.handleUpdateBtnProperty('mappedKey', val);
                        if (val === 'R_STICK_UP' || val === 'SWIPE_UP') {
                          h.handleUpdateBtnProperty('type', 'swipe');
                          h.handleUpdateBtnProperty('androidEventCode', 201);
                          h.handleUpdateBtnProperty('label', 'Swipe Atas (UP)');
                        } else if (val === 'R_STICK_DOWN' || val === 'SWIPE_DOWN') {
                          h.handleUpdateBtnProperty('type', 'swipe');
                          h.handleUpdateBtnProperty('androidEventCode', 202);
                          h.handleUpdateBtnProperty('label', 'Swipe Bawah (DOWN)');
                        } else if (val === 'R_STICK_LEFT' || val === 'SWIPE_LEFT') {
                          h.handleUpdateBtnProperty('type', 'swipe');
                          h.handleUpdateBtnProperty('androidEventCode', 203);
                          h.handleUpdateBtnProperty('label', 'Swipe Kiri (LEFT)');
                        } else if (val === 'R_STICK_RIGHT' || val === 'SWIPE_RIGHT') {
                          h.handleUpdateBtnProperty('type', 'swipe');
                          h.handleUpdateBtnProperty('androidEventCode', 204);
                          h.handleUpdateBtnProperty('label', 'Swipe Kanan (RIGHT)');
                        } else if (val === 'BUTTON_A') {
                          h.handleUpdateBtnProperty('androidEventCode', 96);
                        } else if (val === 'BUTTON_B') {
                          h.handleUpdateBtnProperty('androidEventCode', 97);
                        } else if (val === 'BUTTON_X') {
                          h.handleUpdateBtnProperty('androidEventCode', 99);
                        } else if (val === 'BUTTON_Y') {
                          h.handleUpdateBtnProperty('androidEventCode', 100);
                        } else if (val === 'BUTTON_L1') {
                          h.handleUpdateBtnProperty('androidEventCode', 101);
                        } else if (val === 'BUTTON_R1') {
                          h.handleUpdateBtnProperty('androidEventCode', 102);
                        } else if (val === 'BUTTON_L2') {
                          h.handleUpdateBtnProperty('androidEventCode', 104);
                        } else if (val === 'BUTTON_R2') {
                          h.handleUpdateBtnProperty('androidEventCode', 105);
                        } else if (val === 'BUTTON_L3') {
                          h.handleUpdateBtnProperty('androidEventCode', 103);
                        } else if (val === 'BUTTON_R3') {
                          h.handleUpdateBtnProperty('androidEventCode', 106);
                        } else if (val === 'DPAD_UP') {
                          h.handleUpdateBtnProperty('androidEventCode', 106);
                        } else if (val === 'DPAD_DOWN') {
                          h.handleUpdateBtnProperty('androidEventCode', 107);
                        } else if (val === 'DPAD_LEFT') {
                          h.handleUpdateBtnProperty('androidEventCode', 108);
                        } else if (val === 'DPAD_RIGHT') {
                          h.handleUpdateBtnProperty('androidEventCode', 109);
                        }
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
                      <optgroup label="Virtual Swipes & Motions">
                        <option value="SWIPE_UP">Swipe Up</option>
                        <option value="SWIPE_DOWN">Swipe Down</option>
                        <option value="SWIPE_LEFT">Swipe Left</option>
                        <option value="SWIPE_RIGHT">Swipe Right</option>
                      </optgroup>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Event Code (Raw)</label>
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
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Radial Threshold (Deadzone)</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.deadzone || 0.15}</span>
                    </div>
                    <input
                      type="range"
                      min="0"
                      max="1"
                      step="0.01"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.deadzone || 0.15}
                      onChange={(e) => h.handleUpdateBtnProperty('deadzone', parseFloat(e.target.value))}
                    />
                  </div>
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Sensitivity Curve</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.sensitivity || 1.0}</span>
                    </div>
                    <input
                      type="range"
                      min="0.1"
                      max="3.0"
                      step="0.1"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.sensitivity || 1.0}
                      onChange={(e) => h.handleUpdateBtnProperty('sensitivity', parseFloat(e.target.value))}
                    />
                  </div>
                </div>
              )}
              
              {selectedButton.type === 'swipe' && (
                <div className="p-3.5 bg-slate-950 rounded-lg border border-slate-800 space-y-3 shadow-inner">
                  <h5 className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Swipe Macro Properties</h5>
                  <div>
                    <div className="flex justify-between items-center mb-1">
                      <label className="text-[10px] font-semibold text-slate-400 font-sans uppercase">Tap Hit Duration (ms)</label>
                      <span className="text-[10px] font-mono text-indigo-400">{selectedButton.tapDuration || 30} ms</span>
                    </div>
                    <input
                      type="range"
                      min="10"
                      max="150"
                      step="5"
                      className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                      value={selectedButton.tapDuration || 30}
                      onChange={(e) => h.handleUpdateBtnProperty('tapDuration', parseInt(e.target.value))}
                    />
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
