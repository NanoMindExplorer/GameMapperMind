/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';
import { 
  Play, Settings, RotateCcw, Save, Trash2, Eye, EyeOff, Plus, Check, ChevronDown, Move, Maximize2, Layers
} from 'lucide-react';

interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
}

export default function OverlayWysiwyg({ activeProfile, onUpdateProfile, onLogMessage }: OverlayWysiwygProps) {
  const [showConfig, setShowConfig] = React.useState(true);
  const [selectedButtonId, setSelectedButtonId] = React.useState<string | null>(null);
  const [screenshotMode, setScreenshotMode] = React.useState<'genshin' | 'pubg' | 'codm' | 'efootball'>('genshin');
  const [isDragging, setIsDragging] = React.useState(false);

    // Sync opacity local state with profile if provided on load
    React.useEffect(() => {
    if (activeProfile.globalOpacity !== undefined && activeProfile.globalOpacity !== globalNodeOpacity) {
      setGlobalNodeOpacity(activeProfile.globalOpacity);
    }
  }, [activeProfile.id]);

  // Visual Protection & Graphics Quality Engine State
  const [hideGrid, setHideGrid] = React.useState(false);
  const [hideAllNodes, setHideAllNodes] = React.useState(false);
  const [bgDimLevel, setBgDimLevel] = React.useState(0); // 0% Dim = Maximum Raw Graphic Quality (Perfect graphics, No obstruction)
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(activeProfile.globalOpacity ?? 80); // 80% default opacity

  // Update screenshot background to match active profiles
  React.useEffect(() => {
    if (activeProfile.id === 'genshin' || activeProfile.id === 'pubg' || activeProfile.id === 'codm' || activeProfile.id === 'efootball') {
      setScreenshotMode(activeProfile.id as any);
    }
  }, [activeProfile.id]);

  const selectedButton = activeProfile.buttons.find(b => b.id === selectedButtonId);

  // Drag simulation helpers
  const handleDragStart = (e: React.MouseEvent, btnId: string) => {
    e.stopPropagation();
    setSelectedButtonId(btnId);
    setIsDragging(true);
  };

  const handleDragEnd = () => {
    setIsDragging(false);
  };

  const handleDragMove = (e: React.MouseEvent) => {
    if (!isDragging || !selectedButtonId) return;
    
    const container = e.currentTarget.getBoundingClientRect();
    const x = Math.max(0, Math.min(100, ((e.clientX - container.left) / container.width) * 100));
    const y = Math.max(0, Math.min(100, ((e.clientY - container.top) / container.height) * 100));

    const updatedButtons = activeProfile.buttons.map(b => {
      if (b.id === selectedButtonId) {
        return { ...b, x, y };
      }
      return b;
    });
    
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const handleContainerClick = () => {
    setSelectedButtonId(null);
  };

  const handleUpdateBtnProperty = (key: keyof VirtualButton, value: any) => {
    if (!selectedButtonId) return;
    const updatedButtons = activeProfile.buttons.map(b => {
      if (b.id === selectedButtonId) {
        return { ...b, [key]: value };
      }
      return b;
    });
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const handleAddNewButton = (
    type: 'button' | 'analog_stick' | 'gyro_area' | 'swipe',
    swipeDirection?: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT'
  ) => {
    const freshId = `btn_${Date.now().toString().slice(-4)}`;
    
    let label = 'New Tap';
    let mappedKey = 'BUTTON_B';
    let androidEventCode = 97;
    
    if (type === 'analog_stick') {
      label = 'L-Stick';
      mappedKey = 'L_STICK';
      androidEventCode = 0;
    } else if (type === 'gyro_area') {
      label = 'Camera Trigger';
      mappedKey = 'GYRO';
      androidEventCode = 0;
    } else if (type === 'swipe') {
      if (swipeDirection === 'UP') {
        label = 'Swipe Atas (UP)';
        mappedKey = 'R_STICK_UP';
        androidEventCode = 201;
      } else if (swipeDirection === 'DOWN') {
        label = 'Swipe Bawah (DOWN)';
        mappedKey = 'R_STICK_DOWN';
        androidEventCode = 202;
      } else if (swipeDirection === 'LEFT') {
        label = 'Swipe Kiri (LEFT)';
        mappedKey = 'R_STICK_LEFT';
        androidEventCode = 203;
      } else if (swipeDirection === 'RIGHT') {
        label = 'Swipe Kanan (RIGHT)';
        mappedKey = 'R_STICK_RIGHT';
        androidEventCode = 204;
      }
    }

    let newBtn: VirtualButton = {
      id: freshId,
      label,
      type,
      x: 50,
      y: 50,
      width: type === 'button' ? 56 : type === 'analog_stick' ? 120 : type === 'swipe' ? 68 : 200,
      height: type === 'button' ? 56 : type === 'analog_stick' ? 120 : type === 'swipe' ? 68 : 120,
      mappedKey,
      androidEventCode,
      opacity: 0.6
    };
    onUpdateProfile({
      ...activeProfile,
      buttons: [...activeProfile.buttons, newBtn]
    });
    setSelectedButtonId(freshId);
    onLogMessage(`Overlay Canvas: Appended virtual node '${newBtn.label}' to active viewport`);
  };

  const handleRemoveButton = (btnId: string) => {
    const updated = activeProfile.buttons.filter(b => b.id !== btnId);
    onUpdateProfile({ ...activeProfile, buttons: updated });
    setSelectedButtonId(null);
    onLogMessage(`Overlay Canvas: Discarded node ${btnId} layout constraints`);
  };

  // Safe relocation simulating drag directly inside relative bounding boxes
  const relocateButtonOffset = (direction: 'up' | 'down' | 'left' | 'right') => {
    if (!selectedButton) return;
    let { x, y } = selectedButton;
    if (direction === 'up') y = Math.max(0, y - 2);
    if (direction === 'down') y = Math.min(100, y + 2);
    if (direction === 'left') x = Math.max(0, x - 2);
    if (direction === 'right') x = Math.min(100, x + 2);

    handleUpdateBtnProperty('x', x);
    handleUpdateBtnProperty('y', y);
  };

  // Background mock representation
  const getBackgroundUrl = () => {
    if (screenshotMode === 'genshin') return 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'pubg') return 'https://images.unsplash.com/photo-1534423861386-85a16f5d13fd?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'codm') return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'efootball') return 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?auto=format&fit=crop&q=80&w=1200'; // high quality green soccer field
    return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl grid grid-cols-1 lg:grid-cols-12">
      
      {/* Visual Canvas stage Area (Col 9) */}
      <div className="lg:col-span-8 p-6 flex flex-col border-b lg:border-b-0 lg:border-r border-slate-800 bg-slate-950/20">
        
        {/* Panel controls */}
        <div className="flex flex-wrap justify-between items-center gap-3 mb-4">
          <div>
            <h3 className="text-sm font-bold text-slate-200 flex items-center gap-2">
              <Layers className="w-4 h-4 text-indigo-400" />
              WYSIWYG Dynamic Overlay Sandbox
            </h3>
            <p className="text-[11px] text-slate-400">Position triggers corresponding to UI target anchors</p>
          </div>

          <div className="flex items-center gap-2 bg-slate-900 p-1 border border-slate-800 rounded-lg">
            <span className="text-[10px] uppercase font-bold text-slate-400 px-2">Preview Simulator:</span>
            <select
              value={screenshotMode}
              onChange={(e) => setScreenshotMode(e.target.value as any)}
              className="bg-slate-950 text-xs text-slate-300 px-3 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-medium font-sans border-none"
            >
              <option value="genshin">Genshin Sanctuary Hub</option>
              <option value="pubg">Erangel Warzone</option>
              <option value="codm">Nuketown Battlefield</option>
              <option value="efootball">eFootball Pitch Arena</option>
            </select>
          </div>
        </div>

        {/* Dynamic Display Optimizer / Graphics Preservation Ribbon */}
        <div className="bg-slate-900/95 border border-slate-800 rounded-lg p-3.5 mb-4 grid grid-cols-1 md:grid-cols-2 gap-4 items-center">
          {/* Left Block: Graphics dimming & screen protection */}
          <div className="flex flex-wrap items-center gap-4 text-xs">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-indigo-400"></span>
                Filter Redup Layar (Graphics Preserver)
              </span>
              <div className="flex items-center gap-2">
                <span className="text-[9px] text-slate-500 font-mono">Pristine (0%)</span>
                <input
                  type="range"
                  min="0"
                  max="80"
                  step="5"
                  className="w-24 md:w-32 accent-indigo-500 h-1 bg-slate-950 rounded-lg appearance-none cursor-pointer"
                  value={bgDimLevel}
                  onChange={(e) => {
                    const val = parseInt(e.target.value);
                    setBgDimLevel(val);
                    onLogMessage(`GRAPHICS ENGINE: Set screen dimming factor to ${val}%. ${val === 0 ? "Pristine raw game graphics rendering active (100% full-rich color fidelity)." : "Overlay editing contrast optimized."}`);
                  }}
                />
                <span className="text-[10px] text-indigo-400 font-mono font-bold">-{bgDimLevel}% Dim</span>
              </div>
            </div>

            <div className="flex flex-col gap-1 border-l border-slate-800 pl-4">
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                Transparansi Tombol Global
              </span>
              <div className="flex items-center gap-2">
                  <input
                  type="range"
                  min="0"
                  max="100"
                  step="5"
                  className="w-24 md:w-28 accent-emerald-500 h-1 bg-slate-950 rounded-lg appearance-none cursor-pointer"
                  value={globalNodeOpacity}
                  onChange={(e) => {
                    const val = parseInt(e.target.value);
                    setGlobalNodeOpacity(val);
                    onUpdateProfile({ ...activeProfile, globalOpacity: val });
                  }}
                />
                <span className="text-[10px] text-emerald-400 font-mono font-bold">{globalNodeOpacity}% Opacity</span>
              </div>
            </div>
          </div>

          {/* Right Block: Screen obstruction / Visibility Toggles */}
          <div className="flex items-center justify-end gap-2.5">
            <button
              type="button"
              onClick={() => {
                setHideGrid(!hideGrid);
                onLogMessage(`SCREEN CONFIG: ${!hideGrid ? "Disabled grid overlay layer to free up screen obstructions." : "Enabled alignment grid helper."}`);
              }}
              className={`px-3 py-1.5 rounded text-[10px] font-mono font-bold uppercase border cursor-pointer transition-all ${
                hideGrid 
                  ? 'bg-slate-950 text-slate-500 border-slate-850 hover:text-slate-400' 
                  : 'bg-indigo-950/40 text-indigo-300 border-indigo-950'
              }`}
            >
              <span>{hideGrid ? "GRID: TERSEMBUNYI" : "GRID: TAMPIL"}</span>
            </button>

            <button
              type="button"
              onClick={() => {
                setHideAllNodes(!hideAllNodes);
                onLogMessage(`SCREEN CONFIG: ${!hideAllNodes ? "All custom overlay buttons are temporary made 100% invisible to clear screen obstacles." : "Restored custom overlay buttons transparency."}`);
              }}
              className={`px-3 py-1.5 rounded text-[10px] font-mono font-bold uppercase border cursor-pointer transition-all flex items-center gap-1.5 ${
                hideAllNodes 
                  ? 'bg-rose-950 text-rose-300 border-rose-850 shadow animate-pulse' 
                  : 'bg-slate-950 text-slate-350 border-slate-800 hover:text-white hover:bg-slate-900'
              }`}
            >
              {hideAllNodes ? <EyeOff className="w-3.5 h-3.5 text-rose-400" /> : <Eye className="w-3.5 h-3.5 text-emerald-400" />}
              <span>{hideAllNodes ? "OVERLAY: SEMBUNYI" : "OVERLAY: AKTIF"}</span>
            </button>
          </div>
        </div>

        {/* Main absolute aspect ratio lock screen emulator */}
        <div 
          onClick={handleContainerClick}
          onMouseMove={handleDragMove}
          onMouseUp={handleDragEnd}
          onMouseLeave={handleDragEnd}
          // Added touch events for mobile compatibility
          onTouchMove={(e) => {
            if (!isDragging || !selectedButtonId) return;
            const touch = e.touches[0];
            const container = e.currentTarget.getBoundingClientRect();
            const x = Math.max(0, Math.min(100, ((touch.clientX - container.left) / container.width) * 100));
            const y = Math.max(0, Math.min(100, ((touch.clientY - container.top) / container.height) * 100));
            const updatedButtons = activeProfile.buttons.map(b => b.id === selectedButtonId ? { ...b, x, y } : b);
            onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
          }}
          onTouchEnd={handleDragEnd}
          className="relative w-full aspect-[16/9] bg-slate-950 rounded-lg overflow-hidden border border-slate-800 shadow-inner group select-none touch-none"
          style={{
            backgroundImage: `linear-gradient(rgba(0,0,0,${bgDimLevel / 100}), rgba(0,0,0,${bgDimLevel / 100})), url(${getBackgroundUrl()})`,
            backgroundSize: 'cover',
            backgroundPosition: 'center'
          }}
        >
          {/* Snap overlay grid when adjusting mapping nodes */}
          {!hideGrid && (
            <div className="absolute inset-0 pointer-events-none bg-[linear-gradient(rgba(255,255,255,0.015)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.015)_1px,transparent_1px)] bg-[size:24px_24px] opacity-100" />
          )}

          {/* Real simulated active mapping nodes mapped across bounds */}
          {activeProfile.buttons.map((btn) => {
            const isSelected = btn.id === selectedButtonId;
            const isSwipe = btn.type === 'swipe' || (btn.androidEventCode >= 201 && btn.androidEventCode <= 204);
            let btnColor = 'border-emerald-500 bg-emerald-500/15';
            if (btn.type === 'analog_stick') {
              btnColor = 'border-blue-500 bg-blue-500/10';
            } else if (btn.type === 'gyro_area') {
              btnColor = 'border-pink-500 bg-pink-500/10';
            } else if (isSwipe) {
              btnColor = 'border-purple-550 bg-purple-500/20 shadow-[0_0_12px_rgba(168,85,247,0.35)]';
            }
            
            return (
              <div
                key={btn.id}
                onMouseDown={(e) => handleDragStart(e, btn.id)}
                onTouchStart={(e) => {
                  e.stopPropagation();
                  setSelectedButtonId(btn.id);
                  setIsDragging(true);
                }}
                onClick={(e) => { e.stopPropagation(); setSelectedButtonId(btn.id); }}
                className={`absolute rounded-full border-2 cursor-pointer flex flex-col justify-center items-center font-sans tracking-tight ${btnColor} transition-all antialiased select-none group/node`}
                style={{
                  left: `${btn.x}%`,
                  top: `${btn.y}%`,
                  width: `${btn.width}px`,
                  height: `${btn.height}px`,
                  transform: 'translate(-50%, -50%)',
                  opacity: hideAllNodes ? 0 : btn.opacity * (globalNodeOpacity / 100),
                  boxShadow: isSelected ? '0 0 16px rgba(139, 92, 246, 0.8), inset 0 0 8px rgba(139, 92, 246, 0.4)' : 'none',
                  borderColor: isSelected ? '#8B5CF6' : undefined
                }}
              >
                {/* Node details */}
                <span className="text-[10px] font-bold text-white tracking-wide truncate max-w-full px-1 z-10 text-center">
                  {btn.label}
                </span>
                
                {btn.type === 'analog_stick' && (
                  <div className="absolute w-1/3 h-1/3 bg-blue-400/40 rounded-full border border-blue-400"></div>
                )}
                {btn.type === 'gyro_area' && (
                  <div className="border border-dashed border-pink-400/40 absolute inset-2 rounded-full animate-spin" style={{ animationDuration: '20s' }}></div>
                )}
                
                {isSwipe && (
                  <div className="absolute inset-0 flex flex-col justify-center items-center pointer-events-none p-1 overflow-hidden">
                    {btn.androidEventCode === 201 && (
                      <div className="flex flex-col items-center select-none gap-0.5 mt-auto pb-1">
                        <span className="text-purple-300 text-[10px] font-extrabold font-mono animate-bounce">↑</span>
                        <span className="text-[7.5px] text-purple-400 font-mono tracking-tighter">UP</span>
                      </div>
                    )}
                    {btn.androidEventCode === 202 && (
                      <div className="flex flex-col items-center select-none gap-0.5 mb-auto pt-1">
                        <span className="text-[7.5px] text-purple-400 font-mono tracking-tighter">DOWN</span>
                        <span className="text-purple-300 text-[10px] font-extrabold font-mono animate-bounce">↓</span>
                      </div>
                    )}
                    {btn.androidEventCode === 203 && (
                      <div className="flex items-center justify-center select-none gap-1 w-full h-full">
                        <span className="text-purple-300 text-[10px] font-extrabold font-mono animate-pulse">←</span>
                        <span className="text-[7px] text-purple-400 font-mono tracking-tighter">KIRI</span>
                      </div>
                    )}
                    {btn.androidEventCode === 204 && (
                      <div className="flex items-center justify-center select-none gap-1 w-full h-full">
                        <span className="text-[7px] text-purple-400 font-mono tracking-tighter">KANAN</span>
                        <span className="text-purple-300 text-[10px] font-extrabold font-mono animate-pulse">→</span>
                      </div>
                    )}
                  </div>
                )}
                
                {/* Visual coordinate hover flag */}
                <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 hidden group-hover/node:block bg-slate-950/90 text-[8px] font-mono text-indigo-300 px-1 py-0.5 rounded border border-indigo-900 pointer-events-none whitespace-nowrap">
                  X:{Number(btn.x).toFixed(1)}% Y:{Number(btn.y).toFixed(1)}%
                </div>
              </div>
            );
          })}

          {/* Quick HUD guide */}
          <div className="absolute bottom-3 left-4 right-4 flex justify-between text-[10px] font-mono text-slate-400 tracking-wide bg-slate-950/80 p-2 rounded backdrop-blur border border-slate-900 pointer-events-none">
            <span>Orchestration Context Active Node Out: {activeProfile.packageName}</span>
            <span>Sub-frame Latency: &lt;8 ms</span>
          </div>
        </div>

        {/* Append controls underneath visual viewport */}
        <div className="mt-4 flex flex-wrap gap-2.5">
          <button
            onClick={() => handleAddNewButton('button')}
            className="flex items-center gap-1.5 px-3 py-2 bg-emerald-950 text-emerald-400 hover:bg-emerald-900/60 border border-emerald-900/40 text-xs font-semibold rounded-lg transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            Add Virtual Tap Button
          </button>
          <button
            onClick={() => handleAddNewButton('analog_stick')}
            className="flex items-center gap-1.5 px-3 py-2 bg-blue-950 text-blue-400 hover:bg-blue-900/60 border border-blue-900/40 text-xs font-semibold rounded-lg transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            Add Axis Joystick
          </button>
          <button
            onClick={() => handleAddNewButton('gyro_area')}
            className="flex items-center gap-1.5 px-3 py-2 bg-pink-950 text-pink-400 hover:bg-pink-900/60 border border-pink-900/40 text-xs font-semibold rounded-lg transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            Add Gyroscopic Control Zone
          </button>
          
          <div className="flex gap-1">
            <button
              onClick={() => handleAddNewButton('swipe', 'UP')}
              className="flex items-center gap-1.5 px-3 py-2 bg-purple-950 text-purple-300 hover:bg-purple-900/60 border border-purple-900/40 text-[10px] font-semibold rounded-l-lg transition-colors"
              title="Swipe Atas (UP)"
            >
              UP ↑
            </button>
            <button
              onClick={() => handleAddNewButton('swipe', 'DOWN')}
              className="flex items-center gap-1.5 px-3 py-2 bg-purple-950 text-purple-300 hover:bg-purple-900/60 border-y border-purple-900/40 text-[10px] font-semibold transition-colors"
              title="Swipe Bawah (DOWN)"
            >
              DOWN ↓
            </button>
            <button
              onClick={() => handleAddNewButton('swipe', 'LEFT')}
              className="flex items-center gap-1.5 px-3 py-2 bg-purple-950 text-purple-300 hover:bg-purple-900/60 border-y border-l border-purple-900/40 text-[10px] font-semibold transition-colors"
              title="Swipe Kiri (LEFT)"
            >
              LEFT ←
            </button>
            <button
              onClick={() => handleAddNewButton('swipe', 'RIGHT')}
              className="flex items-center gap-1.5 px-3 py-2 bg-purple-950 text-purple-300 hover:bg-purple-900/60 border border-purple-900/40 text-[10px] font-semibold rounded-r-lg transition-colors"
              title="Swipe Kanan (RIGHT)"
            >
              RIGHT →
            </button>
          </div>
        </div>
      </div>

      {/* Controller Parameters (Col 4) */}
      <div className="lg:col-span-4 p-6 bg-slate-950/40 flex flex-col justify-between">
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
                    onClick={() => handleRemoveButton(selectedButton.id)}
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
                      onChange={(e) => handleUpdateBtnProperty('label', e.target.value)}
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
                          handleUpdateBtnProperty('mappedKey', val);
                          // Auto set appropriate event codes if selecting swipe or standard buttons
                          if (val === 'R_STICK_UP' || val === 'SWIPE_UP') {
                            handleUpdateBtnProperty('type', 'swipe');
                            handleUpdateBtnProperty('androidEventCode', 201);
                            handleUpdateBtnProperty('label', 'Swipe Atas (UP)');
                          } else if (val === 'R_STICK_DOWN' || val === 'SWIPE_DOWN') {
                            handleUpdateBtnProperty('type', 'swipe');
                            handleUpdateBtnProperty('androidEventCode', 202);
                            handleUpdateBtnProperty('label', 'Swipe Bawah (DOWN)');
                          } else if (val === 'R_STICK_LEFT' || val === 'SWIPE_LEFT') {
                            handleUpdateBtnProperty('type', 'swipe');
                            handleUpdateBtnProperty('androidEventCode', 203);
                            handleUpdateBtnProperty('label', 'Swipe Kiri (LEFT)');
                          } else if (val === 'R_STICK_RIGHT' || val === 'SWIPE_RIGHT') {
                            handleUpdateBtnProperty('type', 'swipe');
                            handleUpdateBtnProperty('androidEventCode', 204);
                            handleUpdateBtnProperty('label', 'Swipe Kanan (RIGHT)');
                          } else if (val === 'BUTTON_A') {
                            handleUpdateBtnProperty('androidEventCode', 96);
                          } else if (val === 'BUTTON_B') {
                            handleUpdateBtnProperty('androidEventCode', 97);
                          } else if (val === 'BUTTON_X') {
                            handleUpdateBtnProperty('androidEventCode', 99);
                          } else if (val === 'BUTTON_Y') {
                            handleUpdateBtnProperty('androidEventCode', 100);
                          } else if (val === 'BUTTON_L1') {
                            handleUpdateBtnProperty('androidEventCode', 101);
                          } else if (val === 'BUTTON_R1') {
                            handleUpdateBtnProperty('androidEventCode', 102);
                          } else if (val === 'BUTTON_L2') {
                            handleUpdateBtnProperty('androidEventCode', 104);
                          } else if (val === 'BUTTON_R2') {
                            handleUpdateBtnProperty('androidEventCode', 105);
                          }
                          onLogMessage(`Overlay Inspector: Mapped trigger slot to tactile action: ${val}`);
                        }}
                      >
                        <optgroup label="Arah Analog Kanan (R-Stick / R3)">
                          <option value="R_STICK_UP">R_STICK_UP (R-Stick ↑)</option>
                          <option value="R_STICK_DOWN">R_STICK_DOWN (R-Stick ↓)</option>
                          <option value="R_STICK_LEFT">R_STICK_LEFT (R-Stick ←)</option>
                          <option value="R_STICK_RIGHT">R_STICK_RIGHT (R-Stick →)</option>
                        </optgroup>
                        <optgroup label="Swipe Layar 4 Arah (Legacy)">
                          <option value="SWIPE_UP">SWIPE_UP (Geser Atas)</option>
                          <option value="SWIPE_DOWN">SWIPE_DOWN (Geser Bawah)</option>
                          <option value="SWIPE_LEFT">SWIPE_LEFT (Geser Kiri)</option>
                          <option value="SWIPE_RIGHT">SWIPE_RIGHT (Geser Kanan)</option>
                        </optgroup>
                        <optgroup label="Tombol Gamepad Utama">
                          <option value="BUTTON_A">BUTTON_A (A / Cross)</option>
                          <option value="BUTTON_B">BUTTON_B (B / Circle)</option>
                          <option value="BUTTON_X">BUTTON_X (X / Square)</option>
                          <option value="BUTTON_Y">BUTTON_Y (Y / Triangle)</option>
                        </optgroup>
                        <optgroup label="Bahu & Analog Cliks">
                          <option value="BUTTON_L1">BUTTON_L1 (LB Bumper)</option>
                          <option value="BUTTON_R1">BUTTON_R1 (RB Bumper)</option>
                          <option value="BUTTON_L2">BUTTON_L2 (LT Trigger)</option>
                          <option value="BUTTON_R2">BUTTON_R2 (RT Trigger)</option>
                          <option value="BUTTON_L3">BUTTON_L3 (L3 Thumb)</option>
                          <option value="BUTTON_R3">BUTTON_R3 (R3 Thumb)</option>
                        </optgroup>
                        <optgroup label="Gamepad D-pad Navigation">
                          <option value="DPAD_UP">DPAD_UP (Dpad Atas)</option>
                          <option value="DPAD_DOWN">DPAD_DOWN (Dpad Bawah)</option>
                          <option value="DPAD_LEFT">DPAD_LEFT (Dpad Kiri)</option>
                          <option value="DPAD_RIGHT">DPAD_RIGHT (Dpad Kanan)</option>
                        </optgroup>
                        <optgroup label="Sensor & Analogs">
                          <option value="L_STICK">L_STICK (Move)</option>
                          <option value="R_STICK">R_STICK (Camera)</option>
                          <option value="GYRO">GYRO (Motion/Sensor)</option>
                        </optgroup>
                      </select>
                    </div>
                    <div>
                      <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Inject Event (evdev)</label>
                      <input
                        type="number"
                        className="w-full bg-slate-900 text-slate-100 text-[11px] px-2 py-1.5 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800"
                        value={selectedButton.androidEventCode}
                        onChange={(e) => handleUpdateBtnProperty('androidEventCode', parseInt(e.target.value) || 0)}
                      />
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Horizontal X (%)</label>
                      <input
                        type="number"
                        className="w-full bg-slate-900 text-slate-100 text-xs px-2 py-1 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800"
                        value={Number(selectedButton.x).toFixed(1)}
                        onChange={(e) => handleUpdateBtnProperty('x', Math.max(0, Math.min(100, parseFloat(e.target.value) || 0)))}
                      />
                    </div>
                    <div>
                      <label className="block text-[10px] font-semibold text-slate-400 mb-1 font-sans uppercase">Vertical Y (%)</label>
                      <input
                        type="number"
                        className="w-full bg-slate-900 text-slate-100 text-xs px-2 py-1 rounded focus:outline-none focus:border-indigo-500 font-mono border border-slate-800"
                        value={Number(selectedButton.y).toFixed(1)}
                        onChange={(e) => handleUpdateBtnProperty('y', Math.max(0, Math.min(100, parseFloat(e.target.value) || 0)))}
                      />
                    </div>
                  </div>

                  <div>
                    <div className="flex justify-between text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <span>Ukuran (Lebar & Tinggi) / Resize</span>
                      <span>{selectedButton.width}px</span>
                    </div>
                    <input
                      type="range"
                      min="30"
                      max="300"
                      className="w-full accent-indigo-500"
                      value={selectedButton.width}
                      onChange={(e) => {
                        const val = parseInt(e.target.value);
                        handleUpdateBtnProperty('width', val);
                        handleUpdateBtnProperty('height', val);
                      }}
                    />
                  </div>

                  <div>
                    <div className="flex justify-between text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <span>Tactile Node Opacity</span>
                      <span>{Math.round(selectedButton.opacity * 100)}%</span>
                    </div>
                    <input
                      type="range"
                      min="0.1"
                      max="1.0"
                      step="0.05"
                      className="w-full accent-indigo-500"
                      value={selectedButton.opacity}
                      onChange={(e) => handleUpdateBtnProperty('opacity', parseFloat(e.target.value))}
                    />
                  </div>
                </div>
              </div>

              {/* D-Pad position helper offset controllers */}
              <div className="p-3 bg-slate-900 rounded-lg border border-slate-800">
                <span className="block text-[10px] font-bold text-slate-400 uppercase mb-2">Micro-Location Displacement</span>
                <div className="flex justify-center">
                  <div className="grid grid-cols-3 gap-1.5 w-28">
                    <div></div>
                    <button onClick={() => relocateButtonOffset('up')} className="p-1 px-3 bg-slate-950 font-semibold border border-slate-800 text-slate-300 rounded hover:bg-slate-800 transition-colors">↑</button>
                    <div></div>
                    <button onClick={() => relocateButtonOffset('left')} className="p-1 px-3 bg-slate-950 font-semibold border border-slate-800 text-slate-300 rounded hover:bg-slate-800 transition-colors">←</button>
                    <div></div>
                    <button onClick={() => relocateButtonOffset('right')} className="p-1 px-3 bg-slate-950 font-semibold border border-slate-800 text-slate-300 rounded hover:bg-slate-800 transition-colors">→</button>
                    <div></div>
                    <button onClick={() => relocateButtonOffset('down')} className="p-1 px-3 bg-slate-950 font-semibold border border-slate-800 text-slate-300 rounded hover:bg-slate-800 transition-colors">↓</button>
                    <div></div>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="p-6 bg-slate-950/40 rounded-lg border border-dashed border-slate-800 text-center flex flex-col items-center justify-center space-y-2 h-48">
              <Move className="w-8 h-8 text-indigo-500/30" />
              <span className="text-xs text-slate-400">No Overlay Node Selected</span>
              <p className="text-[10px] text-slate-500 leading-relaxed max-w-[180px]">
                Click or drag any node on the left canvas stage to modify calibration metrics.
              </p>
            </div>
          )}
        </div>

        {/* Custom informational HUD badge specifically for eFootball Mobile 2026 */}
        {activeProfile.id === 'efootball' && (
          <div className="mt-4 p-4 rounded-lg bg-emerald-950/15 border border-emerald-500/25 text-slate-350 text-[11px] space-y-2.5">
            <div className="flex items-center gap-1.5 text-[11px] font-bold text-emerald-400">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
              eFootball™ Tactical Controller Map
            </div>
            <div className="space-y-3 leading-relaxed">
              <div>
                <span className="font-bold text-emerald-400 block mb-0.5">⚔️ Saat Menyerang (Dengan Bola):</span>
                <ul className="list-disc list-inside space-y-0.5 pl-1 font-mono text-[9px] text-slate-300">
                  <li><span className="text-slate-400">L-Stick:</span> Gerakan Pemain (Dribble)</li>
                  <li><span className="text-slate-400">A (PS: X):</span> Umpan Pendek / Ground Pass</li>
                  <li><span className="text-slate-400">X (PS: ◻):</span> Tembak / Shoot</li>
                  <li><span className="text-slate-400">Y (PS: △):</span> Umpan Terobosan / Through Ball</li>
                  <li><span className="text-slate-400">B (PS: ◯):</span> Umpan Lambung / Lofted Pass</li>
                  <li><span className="text-slate-400">R1 / RB:</span> Lari Cepat / Dash</li>
                  <li><span className="text-slate-400">R2 / RT:</span> Dash + Lari / Knock-on</li>
                  <li><span className="text-slate-400">L1 / LB:</span> Ganti Kursor Manual</li>
                </ul>
              </div>
              <div>
                <span className="font-bold text-rose-450 block mb-0.5">🛡️ Saat Bertahan (Tanpa Bola):</span>
                <ul className="list-disc list-inside space-y-0.5 pl-1 font-mono text-[9px] text-slate-300">
                  <li><span className="text-slate-400">A (PS: X):</span> Panggil Tekanan Rekan</li>
                  <li><span className="text-slate-400">X (PS: ◻):</span> Tekel / Tackle</li>
                  <li><span className="text-slate-400">Y (PS: △):</span> Kiper Maju / Goalkeeper Rush</li>
                  <li><span className="text-slate-400">B (PS: ◯):</span> Tekanan Dasar / Pressure</li>
                  <li><span className="text-slate-400">R1 / RB:</span> Dash (Kejar Bola)</li>
                  <li><span className="text-slate-400">R2 / RT:</span> Match-up (Bayangi Lawan)</li>
                  <li><span className="text-slate-400">L1 / LB:</span> Ganti Kursor Pemain</li>
                </ul>
              </div>
              <div className="pt-2 border-t border-emerald-500/10 text-[9px] text-slate-400 text-justify">
                💡 <span className="font-semibold text-amber-300">Stunning Shot/Pass:</span> Atur K2er 4-way Swipe di R3 atau kombinasikan Tombol Lari + Tombol Umpan. <span className="text-rose-400 font-bold">Gyro otomatis dimatikan (0.0x)</span> untuk stabilitas bidikan tactile murni.
              </div>
            </div>
          </div>
        )}

        <div className="p-4 bg-slate-950 rounded-lg border border-slate-800 flex items-center justify-between text-xs text-slate-400 mt-6 md:mt-2 bg-gradient-to-r from-slate-950 to-indigo-950/20">
          <span>Active Nodes: {activeProfile.buttons.length}</span>
          <span className="text-[10px] uppercase font-bold text-indigo-400 font-mono tracking-wider">uinput Ready</span>
        </div>
      </div>

    </div>
  );
}
