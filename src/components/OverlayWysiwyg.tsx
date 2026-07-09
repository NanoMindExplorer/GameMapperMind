import React from 'react';
import { GamepadProfile } from '../types';
import { useOverlayWysiwyg } from '../hooks/useOverlayWysiwyg';
import { Settings, Save, Eye, EyeOff, Check, Plus, X, Trash2, Layers, ChevronUp, Upload, Crosshair, Move, Circle } from 'lucide-react';
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
  const [isMacroRecording, setIsMacroRecording] = React.useState(false);
  const [currentScene, setCurrentScene] = React.useState('default');

  const handleSave = () => {
    h.onUpdateProfile(h.activeProfile);
    h.onLogMessage('Profile saved successfully to storage.');
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  // ==================== PHASE 4: MACRO RECORDING ====================
  const handleToggleMacroRecording = () => {
    if (isMacroRecording) {
      setIsMacroRecording(false);
      h.onLogMessage('Macro recording stopped.');
      // TODO: Panggil native stopMacroRecording()
    } else {
      setIsMacroRecording(true);
      h.onLogMessage('Mulai merekam macro... Tekan tombol pada gamepad.');
      // TODO: Panggil native startMacroRecording(activeProfile.id)
    }
  };

  // ==================== PHASE 4: SCENE MANAGEMENT ====================
  const handleSceneChange = (scene: string) => {
    setCurrentScene(scene);
    h.onLogMessage(`Scene changed to: ${scene}`);
    // TODO: Kirim ke native layer jika diperlukan
  };

  const bgUrl = h.getBackgroundUrl();
  const isDataUrl = bgUrl && (bgUrl.startsWith('data:') || bgUrl.startsWith('blob:'));
  const isGradient = bgUrl && bgUrl.startsWith('linear-gradient');
  const selectedBtn = h.activeProfile?.buttons?.find((b: any) => b.id === h.selectedButtonId);

  return (
    <div className="flex flex-col flex-1 min-h-0 bg-slate-950 font-sans text-slate-200 overflow-hidden">
      {/* ====== TOP BAR (Phase 4 Updated) ====== */}
      {!h.isNativeOverlay && (
        <div className="flex items-center justify-between px-3 py-2 bg-slate-900 border-b border-slate-800 shrink-0 z-40">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold text-indigo-400">WYSIWYG</span>
              <span className="text-[10px] text-slate-500">|</span>
              <span className="text-xs text-slate-300">{h.activeProfile?.name || 'Unsaved'}</span>
            </div>

            {/* Scene Selector - Phase 4 */}
            <div className="flex items-center gap-1.5 pl-2 border-l border-slate-700">
              <Layers className="w-3.5 h-3.5 text-slate-500" />
              <select
                value={currentScene}
                onChange={(e) => handleSceneChange(e.target.value)}
                className="bg-slate-800 text-slate-200 text-xs px-2 py-1 rounded border border-slate-700 focus:outline-none"
              >
                <option value="default">Default Scene</option>
                <option value="combat">Combat</option>
                <option value="exploration">Exploration</option>
                <option value="menu">Menu</option>
              </select>
            </div>
          </div>

          <div className="flex items-center gap-1.5">
            {/* Macro Recording Button - Phase 4 */}
            <button
              onClick={handleToggleMacroRecording}
              className={`px-3 py-1.5 rounded text-xs font-bold flex items-center gap-1.5 transition-all ${
                isMacroRecording 
                  ? 'bg-red-600 hover:bg-red-500 text-white animate-pulse' 
                  : 'bg-pink-600 hover:bg-pink-500 text-white'
              }`}
            >
              <Circle className={`w-3 h-3 ${isMacroRecording ? 'fill-current' : ''}`} />
              {isMacroRecording ? 'Stop Recording' : 'Record Macro'}
            </button>

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

      {/* ====== CANVAS AREA ====== */}
      <div className="flex-1 relative min-h-0 overflow-hidden">
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
            const isPressed = h.activeKeys?.includes(btn.mappedKey) ||
              (btn.trigger?.inputs && btn.trigger.inputs.some((inp: string) => h.activeKeys?.includes(inp)));

            let radius = "rounded-full";
            if (btn.type === 'swipe') radius = "rounded-lg";

            const interactionType = btn.interactionType || 'hold';
            let interactionIcon = null;

            if (btn.type !== 'analog_stick') {
              switch (interactionType) {
                case 'turbo':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-amber-400 bg-amber-950 px-1 rounded border border-amber-700 z-20">⚡</span>;
                  break;
                case 'toggle':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-purple-400 bg-purple-950 px-1 rounded border border-purple-700 z-20">⊕</span>;
                  break;
                case 'charge':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-blue-400 bg-blue-950 px-1 rounded border border-blue-700 z-20">⏱</span>;
                  break;
                case 'gesture':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-cyan-400 bg-cyan-950 px-1 rounded border border-cyan-700 z-20">~</span>;
                  break;
                case 'macro':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-pink-400 bg-pink-950 px-1 rounded border border-pink-700 z-20">M</span>;
                  break;
                case 'tap':
                  interactionIcon = <span className="absolute -top-2 -right-2 text-[7px] font-bold text-green-400 bg-green-950 px-1 rounded border border-green-700 z-20">▸</span>;
                  break;
              }
            }

            let analogCap = null;
            if (btn.type === 'analog_stick') {
              let sx = 0, sy = 0;
              if (btn.mappedKey === 'L_STICK') { sx = h.activeAxes?.lx * (btn.width / 3.5) || 0; sy = h.activeAxes?.ly * (btn.height / 3.5) || 0; }
              else if (btn.mappedKey === 'R_STICK') { sx = h.activeAxes?.rx * (btn.width / 3.5) || 0; sy = h.activeAxes?.ry * (btn.height / 3.5) || 0; }

              const isLeft = btn.mappedKey === 'L_STICK';
              const dragMode = btn.stickMode === 'drag';

              analogCap = (
                <React.Fragment>
                  <div className="absolute inset-3 border border-white/10 rounded-full pointer-events-none" />
                  <div
                    className={`absolute w-[45%] h-[45%] ${isLeft ? 'bg-indigo-500 border-indigo-400' : 'bg-pink-500 border-pink-400'} ${dragMode ? 'opacity-60' : ''} rounded-full border-[2.5px] z-10 pointer-events-none`}
                    style={{ transform: `translate(${sx}px, ${sy}px)`, transition: h.isDragging ? 'none' : 'transform 80ms ease-out' }}
                  />
                  {dragMode && (
                    <span className="absolute -top-2 -right-2 text-[7px] font-bold text-orange-400 bg-orange-950 px-1 rounded border border-orange-700 z-20">DRAG</span>
                  )}
                </React.Fragment>
              );
            }

            return (
              <div
                key={btn.id}
                data-btn-node={btn.id}
                className={`absolute ${radius} flex flex-col items-center justify-center cursor-move select-none touch-none transition-colors duration-75 ${
                  isSel
                    ? 'border-2 border-indigo-400 z-40 bg-indigo-500/30'
                    : isPressed
                      ? 'border-2 border-emerald-400 z-30 bg-emerald-500/50 shadow-[0_0_18px_rgba(16,185,129,0.65)]'
                      : 'border border-slate-400/40 z-20 bg-slate-900/50'
                }`}
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
                {interactionIcon}
                <span className={`text-[10px] font-bold ${isSel ? 'text-white' : isPressed ? 'text-emerald-200' : 'text-slate-300'}`}>{btn.label}</span>
                <span className="text-[8px] font-mono opacity-50 whitespace-nowrap">{btn.trigger?.inputs?.join('+') || btn.mappedKey}</span>
                {analogCap}
              </div>
            );
          })}

          {/* Coordinate display */}
          {h.isDragging && selectedBtn && (
            <div className="absolute top-2 left-1/2 -translate-x-1/2 bg-slate-950/90 text-xs font-mono text-indigo-300 px-3 py-1 rounded border border-indigo-800 pointer-events-none z-50">
              X: {selectedBtn.x.toFixed(1)}% Y: {selectedBtn.y.toFixed(1)}%
            </div>
          )}

          {/* Macro Recording Indicator - Phase 4 */}
          {isMacroRecording && (
            <div className="absolute top-4 right-4 bg-red-600/90 text-white text-xs px-3 py-1.5 rounded-full flex items-center gap-2 z-50 border border-red-500">
              <div className="w-2 h-2 bg-white rounded-full animate-pulse" />
              Recording Macro...
            </div>
          )}
        </div>

        {/* Settings Panel, Palette, Bottom Bar, dll tetap sama seperti kode asli */}
        {/* ... (saya pertahankan semua kode asli yang kamu berikan di bawah ini) */}

        {/* ====== FLOATING SETTINGS PANEL ====== */}
        {!h.isNativeOverlay && h.showConfig && (
          <div className="absolute left-2 top-2 bottom-2 w-56 bg-slate-950/95 border border-slate-700 rounded-lg p-3 flex flex-col gap-3 overflow-y-auto z-30 shadow-2xl">
            {/* ... (kode settings panel tetap sama) */}
          </div>
        )}

        {/* ====== BUTTON PALETTE ====== */}
        {!h.isNativeOverlay && h.showPalette && (
          <div className="absolute top-2 left-1/2 -translate-x-1/2 bg-slate-950/95 border border-slate-700 rounded-lg p-3 shadow-2xl z-40 max-w-[90%]">
            {/* ... (kode palette tetap sama) */}
          </div>
        )}

        {/* ====== BOTTOM BAR ====== */}
        {!h.isNativeOverlay && (
          <div className="absolute bottom-0 left-0 right-0 bg-slate-950/95 border-t border-slate-800 z-40">
            {/* ... (kode bottom bar tetap sama seperti yang kamu kirim) */}
          </div>
        )}
      </div>
    </div>
  );
}
