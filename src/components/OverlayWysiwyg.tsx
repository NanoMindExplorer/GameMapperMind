import React from 'react';
import { GamepadProfile } from '../types';
import { useOverlayWysiwyg } from '../hooks/useOverlayWysiwyg';
import { Settings, Save, Eye, EyeOff, Check, Plus, X, Trash2, Layers, ChevronUp, Upload, Crosshair, Move } from 'lucide-react';
import { VirtualButton } from '../types';

interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
  activeKeys?: string[];
  activeAxes?: {lx: number, ly: number, rx: number, ry: number};
  isNativeOverlay?: boolean;
}

export default function OverlayWysiwyg(props: OverlayWysiwygProps) {
  const h = useOverlayWysiwyg(props);
  const [saved, setSaved] = React.useState(false);

  const handleSave = () => {
    h.onUpdateProfile(h.activeProfile);
    h.onLogMessage('Profile saved successfully to storage.');
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const bgUrl = h.getBackgroundUrl();
  const isDataUrl = bgUrl && (bgUrl.startsWith('data:') || bgUrl.startsWith('blob:'));
  const isGradient = bgUrl && bgUrl.startsWith('linear-gradient');
  const selectedBtn = h.activeProfile?.buttons?.find((b: any) => b.id === h.selectedButtonId);

  return (
    <div className="flex flex-col flex-1 min-h-0 bg-slate-950 font-sans text-slate-200 overflow-hidden">

      {/* ====== TOP BAR (compact, fixed height) ====== */}
      {!h.isNativeOverlay && (
        <div className="flex items-center justify-between px-3 py-2 bg-slate-900 border-b border-slate-800 shrink-0 z-40">
          <div className="flex items-center gap-2">
            <span className="text-xs font-bold text-indigo-400">WYSIWYG</span>
            <span className="text-[10px] text-slate-500">|</span>
            <span className="text-xs text-slate-300">{h.activeProfile?.name || 'Unsaved'}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => h.setHideGrid(!h.hideGrid)}
              className={`p-1.5 rounded ${h.hideGrid ? 'bg-slate-800 text-slate-500' : 'bg-indigo-900/40 text-indigo-300'}`}
              title="Toggle Grid"
            >
              {h.hideGrid ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
            </button>
            <button
              onClick={() => h.setHideAllNodes(!h.hideAllNodes)}
              className={`p-1.5 rounded ${h.hideAllNodes ? 'bg-slate-800 text-slate-500' : 'bg-indigo-900/40 text-indigo-300'}`}
              title="Toggle Nodes"
            >
              {h.hideAllNodes ? <EyeOff className="w-3.5 h-3.5" /> : <Layers className="w-3.5 h-3.5" />}
            </button>
            <button
              onClick={() => h.setShowConfig(!h.showConfig)}
              className={`p-1.5 rounded ${h.showConfig ? 'bg-indigo-900/40 text-indigo-300' : 'bg-slate-800 text-slate-500'}`}
              title="Settings"
            >
              <Settings className="w-3.5 h-3.5" />
            </button>
            <button
              onClick={handleSave}
              className={`px-3 py-1.5 rounded text-xs font-bold flex items-center gap-1 ${saved ? 'bg-emerald-500 text-white' : 'bg-emerald-600 hover:bg-emerald-500 text-white'}`}
            >
              {saved ? <Check className="w-3.5 h-3.5" /> : <Save className="w-3.5 h-3.5" />}
              {saved ? 'Saved' : 'Save'}
            </button>
          </div>
        </div>
      )}

      {/* ====== CANVAS AREA (full area, single flex child) ====== */}
      <div className="flex-1 relative min-h-0 overflow-hidden">

        {/* Canvas container — absolute fill, NO flex competition */}
        <div
          className="absolute inset-0 overflow-hidden"
          id="canvas-container"
          onClick={h.handleContainerClick}
          onMouseMove={h.handleDragMove}
          onMouseUp={h.handleDragEnd}
          onTouchMove={h.handleDragMove}
          onTouchEnd={h.handleDragEnd}
          style={{
            ...(isGradient ? { backgroundImage: bgUrl, backgroundColor: '#0f172a' } : { backgroundColor: '#0f172a' }),
            touchAction: 'none',
            overscrollBehavior: 'none',
            WebkitUserSelect: 'none',
            WebkitTouchCallout: 'none',
          }}
        >
          {/* Screenshot */}
          {isDataUrl && (
            <img src={bgUrl} alt="Screenshot" className="absolute inset-0 w-full h-full object-contain" style={{ pointerEvents: 'none' }} />
          )}

          {/* Dim overlay */}
          <div className="absolute inset-0 bg-black pointer-events-none" style={{ opacity: h.bgDimLevel / 100 }} />

          {/* Grid */}
          {!h.hideGrid && (
            <div className="absolute inset-0 pointer-events-none opacity-15"
              style={{
                backgroundImage: 'linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)',
                backgroundSize: '20px 20px'
              }}
            />
          )}

          {/* Button nodes */}
          {!h.hideAllNodes && h.activeProfile?.buttons?.map((btn: any) => {
            const isSel = h.selectedButtonId === btn.id;
            let radius = "rounded-full";
            if (btn.type === 'swipe') radius = "rounded-lg";

            // Analog stick visual
            let analogCap = null;
            if (btn.type === 'analog_stick') {
              let sx = 0, sy = 0;
              if (btn.mappedKey === 'L_STICK') { sx = h.activeAxes.lx * (btn.width / 3.5); sy = h.activeAxes.ly * (btn.height / 3.5); }
              else if (btn.mappedKey === 'R_STICK') { sx = h.activeAxes.rx * (btn.width / 3.5); sy = h.activeAxes.ry * (btn.height / 3.5); }
              const isLeft = btn.mappedKey === 'L_STICK';
              analogCap = (
                <React.Fragment>
                  <div className="absolute inset-3 border border-white/10 rounded-full pointer-events-none" />
                  <div
                    className={`absolute w-[45%] h-[45%] ${isLeft ? 'bg-indigo-500 border-indigo-400' : 'bg-pink-500 border-pink-400'} rounded-full border-[2.5px] z-10 pointer-events-none`}
                    style={{ transform: `translate(${sx}px, ${sy}px)`, transition: h.isDragging ? 'none' : 'transform 80ms ease-out' }}
                  />
                </React.Fragment>
              );
            }

            return (
              <div
                key={btn.id}
                data-btn-node={btn.id}
                className={`absolute ${radius} flex flex-col items-center justify-center cursor-move select-none touch-none ${isSel ? 'border-2 border-indigo-400 z-40 bg-indigo-500/30' : 'border border-slate-400/40 z-20 bg-slate-900/50'}`}
                style={{
                  left: `${btn.x}%`,
                  top: `${btn.y}%`,
                  width: `${btn.width || 56}px`,
                  height: `${btn.height || 56}px`,
                  transform: 'translate(-50%, -50%)',
                  opacity: h.globalNodeOpacity / 100,
                  outline: isSel ? '2px solid #8B5CF6' : undefined,
                  outlineOffset: '2px',
                }}
                onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); h.handleDragStart(btn.id, e); }}
                onTouchStart={(e) => { e.preventDefault(); e.stopPropagation(); h.handleDragStart(btn.id, e); }}
                onClick={(e) => e.stopPropagation()}
              >
                <span className={`text-[10px] font-bold ${isSel ? 'text-white' : 'text-slate-300'}`}>{btn.label}</span>
                <span className="text-[8px] font-mono opacity-50 whitespace-nowrap">{btn.mappedKey}</span>
                {analogCap}
              </div>
            );
          })}

          {/* Coordinate display saat drag */}
          {h.isDragging && selectedBtn && (
            <div className="absolute top-2 left-1/2 -translate-x-1/2 bg-slate-950/90 text-xs font-mono text-indigo-300 px-3 py-1 rounded border border-indigo-800 pointer-events-none z-50">
              X: {selectedBtn.x.toFixed(1)}% Y: {selectedBtn.y.toFixed(1)}%
            </div>
          )}
        </div>

        {/* ====== FLOATING SETTINGS PANEL (left, toggleable) ====== */}
        {!h.isNativeOverlay && h.showConfig && (
          <div className="absolute left-2 top-2 bottom-2 w-56 bg-slate-950/95 border border-slate-700 rounded-lg p-3 flex flex-col gap-3 overflow-y-auto z-30 shadow-2xl">
            <div className="text-xs font-bold text-slate-300 flex items-center gap-1.5">
              <Settings className="w-3.5 h-3.5 text-indigo-400" /> Settings
            </div>

            {/* Screenshot selector */}
            <div>
              <label className="text-[10px] text-slate-500 block mb-1">Background</label>
              <select
                className="w-full bg-slate-900 text-slate-100 text-xs px-2 py-1.5 rounded border border-slate-700"
                value={h.screenshotMode}
                onChange={(e) => h.setScreenshotMode(e.target.value)}
              >
                <option value="genshin">Genshin Impact</option>
                <option value="pubg">PUBG Mobile</option>
                <option value="codm">COD Mobile</option>
                <option value="efootball">eFootball</option>
                <option value="custom">Custom Upload...</option>
              </select>
            </div>

            {/* Upload */}
            {h.screenshotMode === 'custom' && (
              <div>
                <input type="file" ref={h.fileInputRef} accept="image/*" className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      const reader = new FileReader();
                      reader.onload = () => h.setCustomScreenshotUrl(reader.result as string);
                      reader.readAsDataURL(file);
                    }
                    e.target.value = '';
                  }}
                />
                <button onClick={() => h.fileInputRef.current?.click()}
                  className="w-full py-1.5 bg-slate-800 hover:bg-slate-700 text-indigo-300 text-[11px] rounded border border-slate-700 flex items-center justify-center gap-1">
                  <Upload className="w-3 h-3" /> Upload
                </button>
                {h.customScreenshotUrl && <div className="text-[9px] text-emerald-400 mt-1 flex items-center gap-0.5"><Check className="w-2.5 h-2.5" /> Loaded</div>}
              </div>
            )}

            {/* Brightness */}
            <div>
              <div className="flex justify-between text-[10px] text-slate-400 mb-0.5">
                <span>Brightness</span><span className="font-mono">{h.bgDimLevel}%</span>
              </div>
              <input type="range" min="0" max="100" value={h.bgDimLevel}
                onChange={(e) => h.setBgDimLevel(Number(e.target.value))}
                className="w-full accent-indigo-500 h-1 bg-slate-800 rounded-lg appearance-none cursor-pointer" />
            </div>

            {/* Opacity */}
            <div>
              <div className="flex justify-between text-[10px] text-slate-400 mb-0.5">
                <span>Node Opacity</span><span className="font-mono">{h.globalNodeOpacity}%</span>
              </div>
              <input type="range" min="10" max="100" value={h.globalNodeOpacity}
                onChange={(e) => h.setGlobalNodeOpacity(Number(e.target.value))}
                className="w-full accent-rose-500 h-1 bg-slate-800 rounded-lg appearance-none cursor-pointer" />
            </div>

            {/* Player selector */}
            <div>
              <label className="text-[10px] text-slate-500 block mb-1">Active Player</label>
              <div className="flex gap-1">
                {([1,2,3,4] as const).map(p => (
                  <button key={p} onClick={() => h.setActivePlayer(p)}
                    className={`flex-1 py-1 text-[10px] rounded ${h.activePlayer === p ? 'bg-indigo-600 text-white' : 'bg-slate-800 text-slate-400'}`}>
                    P{p}
                  </button>
                ))}
              </div>
            </div>

            <button onClick={() => h.setShowConfig(false)}
              className="w-full py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-400 text-[10px] rounded mt-auto">
              Close
            </button>
          </div>
        )}

        {/* ====== BUTTON PALETTE (top floating, toggleable) ====== */}
        {!h.isNativeOverlay && h.showPalette && (
          <div className="absolute top-2 left-1/2 -translate-x-1/2 bg-slate-950/95 border border-slate-700 rounded-lg p-3 shadow-2xl z-40 max-w-[90%]">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[10px] font-bold text-slate-400 uppercase">Add Button</span>
              <button onClick={() => h.setShowPalette(false)} className="text-slate-500 hover:text-white"><X className="w-3.5 h-3.5" /></button>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {/* Standard buttons */}
              <button onClick={() => h.handleAddSpecificButton('A', 'A', 96)} className="w-8 h-8 rounded-full bg-slate-800 hover:bg-emerald-600 text-slate-200 text-[11px] font-bold">A</button>
              <button onClick={() => h.handleAddSpecificButton('B', 'B', 97)} className="w-8 h-8 rounded-full bg-slate-800 hover:bg-rose-600 text-slate-200 text-[11px] font-bold">B</button>
              <button onClick={() => h.handleAddSpecificButton('X', 'X', 99)} className="w-8 h-8 rounded-full bg-slate-800 hover:bg-blue-600 text-slate-200 text-[11px] font-bold">X</button>
              <button onClick={() => h.handleAddSpecificButton('Y', 'Y', 100)} className="w-8 h-8 rounded-full bg-slate-800 hover:bg-amber-500 text-slate-200 text-[11px] font-bold">Y</button>
              <button onClick={() => h.handleAddSpecificButton('LB', 'LB', 102, 64)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold">LB</button>
              <button onClick={() => h.handleAddSpecificButton('LT', 'LT', 104, 64)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold">LT</button>
              <button onClick={() => h.handleAddSpecificButton('RB', 'RB', 103, 64)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold">RB</button>
              <button onClick={() => h.handleAddSpecificButton('RT', 'RT', 105, 64)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold">RT</button>
              <div className="w-px h-8 bg-slate-700 mx-0.5" />
              <button onClick={() => h.handleAddSpecificButton('↑', 'DPAD_UP', 19, 48)} className="w-8 h-8 rounded bg-slate-800 hover:bg-indigo-500 text-slate-300 text-[11px]">↑</button>
              <button onClick={() => h.handleAddSpecificButton('↓', 'DPAD_DOWN', 20, 48)} className="w-8 h-8 rounded bg-slate-800 hover:bg-indigo-500 text-slate-300 text-[11px]">↓</button>
              <button onClick={() => h.handleAddSpecificButton('←', 'DPAD_LEFT', 21, 48)} className="w-8 h-8 rounded bg-slate-800 hover:bg-indigo-500 text-slate-300 text-[11px]">←</button>
              <button onClick={() => h.handleAddSpecificButton('→', 'DPAD_RIGHT', 22, 48)} className="w-8 h-8 rounded bg-slate-800 hover:bg-indigo-500 text-slate-300 text-[11px]">→</button>
              <div className="w-px h-8 bg-slate-700 mx-0.5" />
              <button onClick={() => h.handleAddSpecificButton('L3', 'L3', 106)} className="w-8 h-8 rounded-full border border-dashed border-slate-600 bg-slate-800 hover:bg-slate-700 text-slate-300 text-[9px] font-bold">L3</button>
              <button onClick={() => h.handleAddSpecificButton('R3', 'R3', 107)} className="w-8 h-8 rounded-full border border-dashed border-slate-600 bg-slate-800 hover:bg-slate-700 text-slate-300 text-[9px] font-bold">R3</button>
              <button onClick={() => h.handleAddSpecificButton('SELECT', 'SELECT', 109, 42)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[8px] font-bold">SEL</button>
              <button onClick={() => h.handleAddSpecificButton('START', 'START', 108, 42)} className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[8px] font-bold">STR</button>
              <div className="w-px h-8 bg-slate-700 mx-0.5" />
              <button onClick={() => h.handleAddSpecificButton('L-Stick', 'L_STICK', 0, 100, 'analog_stick')} className="h-8 px-2 rounded bg-blue-900/50 hover:bg-blue-600 border border-blue-700 text-blue-200 text-[9px] font-bold flex items-center gap-1">
                <Layers className="w-3 h-3" /> L-Stick
              </button>
              <button onClick={() => h.handleAddSpecificButton('R-Stick', 'R_STICK', 0, 100, 'analog_stick')} className="h-8 px-2 rounded bg-pink-900/50 hover:bg-pink-600 border border-pink-700 text-pink-200 text-[9px] font-bold flex items-center gap-1">
                <Layers className="w-3 h-3" /> R-Stick
              </button>
            </div>
          </div>
        )}

        {/* ====== BOTTOM BAR (add + properties, always visible) ====== */}
        {!h.isNativeOverlay && (
          <div className="absolute bottom-0 left-0 right-0 bg-slate-950/95 border-t border-slate-800 z-40">
            {/* If button selected: show properties */}
            {selectedBtn ? (
              <div className="flex items-center gap-2 px-3 py-2 overflow-x-auto">
                <span className="text-[10px] text-slate-500 shrink-0">{selectedBtn.label}</span>
                {/* Nudge controls */}
                <div className="flex gap-0.5 shrink-0">
                  <button onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(selectedBtn.id, 0, -1); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px]">↑</button>
                  <button onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(selectedBtn.id, 0, 1); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px]">↓</button>
                  <button onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(selectedBtn.id, -1, 0); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px]">←</button>
                  <button onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(selectedBtn.id, 1, 0); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px]">→</button>
                </div>
                <div className="w-px h-6 bg-slate-700 shrink-0" />
                {/* Resize */}
                <button onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperties({ width: (selectedBtn.width || 56) - 5, height: (selectedBtn.height || 56) - 5 }); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-rose-900 text-slate-300 text-[10px]">−</button>
                <button onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperties({ width: (selectedBtn.width || 56) + 5, height: (selectedBtn.height || 56) + 5 }); }} className="w-6 h-6 rounded bg-slate-800 hover:bg-indigo-900 text-slate-300 text-[10px]">+</button>
                <div className="w-px h-6 bg-slate-700 shrink-0" />
                {/* Mapped key selector */}
                <select
                  className="bg-slate-900 text-slate-100 text-[10px] px-1.5 py-1 rounded border border-slate-700 shrink-0"
                  value={selectedBtn.mappedKey}
                  onChange={(e) => {
                    const val = e.target.value;
                    const updates: Partial<VirtualButton> = { mappedKey: val as any };
                    const codeMap: Record<string, number> = { A:96, B:97, X:99, Y:100, LB:102, RB:103, LT:104, RT:105, L3:106, R3:107, DPAD_UP:19, DPAD_DOWN:20, DPAD_LEFT:21, DPAD_RIGHT:22, SELECT:109, START:108 };
                    if (val === 'SWIPE_UP') Object.assign(updates, { type:'swipe', androidEventCode:201, swipeDirection:'UP' });
                    else if (val === 'SWIPE_DOWN') Object.assign(updates, { type:'swipe', androidEventCode:202, swipeDirection:'DOWN' });
                    else if (val === 'SWIPE_LEFT') Object.assign(updates, { type:'swipe', androidEventCode:203, swipeDirection:'LEFT' });
                    else if (val === 'SWIPE_RIGHT') Object.assign(updates, { type:'swipe', androidEventCode:204, swipeDirection:'RIGHT' });
                    else if (codeMap[val]) updates.androidEventCode = codeMap[val];
                    h.handleUpdateBtnProperties(updates);
                  }}
                >
                  <optgroup label="Buttons">
                    <option value="A">A</option><option value="B">B</option><option value="X">X</option><option value="Y">Y</option>
                    <option value="LB">LB</option><option value="RB">RB</option><option value="LT">LT</option><option value="RT">RT</option>
                    <option value="L3">L3</option><option value="R3">R3</option>
                    <option value="SELECT">SELECT</option><option value="START">START</option>
                    <option value="DPAD_UP">DPAD ↑</option><option value="DPAD_DOWN">DPAD ↓</option>
                    <option value="DPAD_LEFT">DPAD ←</option><option value="DPAD_RIGHT">DPAD →</option>
                  </optgroup>
                  <optgroup label="Swipe">
                    <option value="SWIPE_UP">Swipe ↑</option><option value="SWIPE_DOWN">Swipe ↓</option>
                    <option value="SWIPE_LEFT">Swipe ←</option><option value="SWIPE_RIGHT">Swipe →</option>
                  </optgroup>
                </select>
                {/* Label input */}
                <input type="text" value={selectedBtn.label}
                  onChange={(e) => h.handleUpdateBtnProperty('label', e.target.value)}
                  className="bg-slate-900 text-slate-100 text-[10px] px-1.5 py-1 rounded border border-slate-700 w-16 shrink-0" />
                <div className="w-px h-6 bg-slate-700 shrink-0" />
                {/* Delete */}
                <button onClick={() => h.handleRemoveButton(selectedBtn.id)}
                  className="p-1.5 rounded bg-rose-950/60 hover:bg-rose-900 text-rose-400 shrink-0">
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
                {/* Deselect */}
                <button onClick={() => h.setSelectedButtonId(null)}
                  className="p-1.5 rounded bg-slate-800 hover:bg-slate-700 text-slate-400 shrink-0">
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>
            ) : (
              /* No selection: show add button */
              <div className="flex items-center gap-2 px-3 py-2">
                <button onClick={() => h.setShowPalette(!h.showPalette)}
                  className={`px-3 py-1.5 rounded text-xs font-bold flex items-center gap-1 ${h.showPalette ? 'bg-indigo-600 text-white' : 'bg-slate-800 hover:bg-slate-700 text-slate-300'}`}>
                  <Plus className="w-3.5 h-3.5" /> Add Button
                </button>
                <span className="text-[10px] text-slate-600">Tap a button to edit • Drag to move • Tap canvas to deselect</span>
              </div>
            )}
          </div>
        )}

        {/* ====== ANALOG STICK PROPERTIES (inline saat analog selected) ====== */}
        {!h.isNativeOverlay && selectedBtn?.type === 'analog_stick' && (
          <div className="absolute bottom-12 right-2 bg-slate-950/95 border border-slate-700 rounded-lg p-2 z-40 text-[10px] space-y-1.5 w-40">
            <div className="font-bold text-slate-400">Analog</div>
            <div className="flex justify-between items-center"><span className="text-slate-500">Deadzone</span><span className="text-indigo-400 font-mono">{selectedBtn.deadzone || 0.15}</span></div>
            <input type="range" min="0" max="1" step="0.01" value={selectedBtn.deadzone || 0.15}
              onChange={(e) => h.handleUpdateBtnProperty('deadzone', parseFloat(e.target.value))}
              className="w-full accent-indigo-500 h-1 bg-slate-800 rounded-lg appearance-none cursor-pointer" />
          </div>
        )}

      </div>
    </div>
  );
}
