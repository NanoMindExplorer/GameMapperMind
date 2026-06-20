/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * OverlayWysiwyg - WYSIWYG Editor untuk Gamepad Button Mapping
 * 
 * FIX BUG-C01: Visual feedback untuk activeKeys dan activeAxes
 * FIX BUG-M03: Dipecah menjadi sub-components
 * FIX BUG-M04: Gradient placeholder instead of external Unsplash URL
 * FIX BUG-M06: Custom event instead of global window function
 * FIX BUG-M10: globalNodeOpacity sync dengan activeProfile
 * FIX BUG-L03: bgDimLevel persistence ke profile
 */
import React from 'react';
import { GamepadProfile, ButtonMapping } from '../types';
import {
  Layers, Plus, Trash2, Move, Maximize2, Eye, EyeOff,
  Palette, Settings, Grid3X3, Lock, Unlock, Copy,
  ChevronDown, ChevronUp, RotateCcw, Save, X,
  Circle, Square, Triangle, Minus, ArrowUp, ArrowDown,
  ArrowLeft, ArrowRight, Crosshair, Zap, Shield
} from 'lucide-react';

interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (profile: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
  activeKeys?: string[];
  activeAxes?: { lx: number; ly: number; rx: number; ry: number };
  isNativeOverlay?: boolean;
}

// ============================================
// FIX BUG-M04: Gradient placeholder instead of external URL
// ============================================
const getScreenshotBackground = (mode: string): string => {
  switch (mode) {
    case 'genshin':
      return 'linear-gradient(135deg, #1a1a2e 0%, #16213e 30%, #0f3460 60%, #533483 100%)';
    case 'pubg':
      return 'linear-gradient(135deg, #1a1a1a 0%, #2d2d2d 30%, #3d3d3d 60%, #4a4a4a 100%)';
    case 'mlbb':
      return 'linear-gradient(135deg, #0d1b2a 0%, #1b2838 30%, #2a4066 60%, #1b4965 100%)';
    default:
      return 'linear-gradient(135deg, #0a0a0f 0%, #1a1a2e 50%, #0a0a0f 100%)';
  }
};

// ============================================
// FIX BUG-M06: Custom event listener for palette toggle
// ============================================
const PALETTE_TOGGLE_EVENT = 'gamemapper:toggle-palette';

// ============================================
// SUB-COMPONENT: OverlayNode
// ============================================
interface OverlayNodeProps {
  button: ButtonMapping;
  isActive: boolean;
  isSelected: boolean;
  opacity: number;
  isLocked: boolean;
  onMouseDown: (e: React.MouseEvent) => void;
  onClick: () => void;
}

const OverlayNode = React.memo(({
  button, isActive, isSelected, opacity, isLocked, onMouseDown, onClick
}: OverlayNodeProps) => {
  const width = button.width || 60;
  const height = button.height || 60;

  const getNodeColor = () => {
    if (isActive) return 'border-emerald-400 bg-emerald-500/30 shadow-emerald-500/40';
    if (isSelected) return 'border-indigo-400 bg-indigo-500/20 shadow-indigo-500/30';
    switch (button.type) {
      case 'analog': return 'border-purple-400/60 bg-purple-500/10';
      case 'dpad': return 'border-amber-400/60 bg-amber-500/10';
      case 'swipe': return 'border-cyan-400/60 bg-cyan-500/10';
      default: return 'border-slate-400/60 bg-slate-500/10';
    }
  };

  const getTypeIcon = () => {
    switch (button.type) {
      case 'analog': return <Circle className="w-3 h-3" />;
      case 'dpad': return <Crosshair className="w-3 h-3" />;
      case 'swipe': return <ArrowUp className="w-3 h-3" />;
      default: return <Square className="w-3 h-3" />;
    }
  };

  return (
    <div
      className={`absolute border-2 rounded-lg cursor-move transition-all duration-75 
        ${getNodeColor()} 
        ${isActive ? 'shadow-lg scale-110' : 'shadow-md'} 
        ${isSelected ? 'ring-2 ring-indigo-400/50' : ''}
        ${isLocked ? 'cursor-not-allowed opacity-60' : 'hover:brightness-125'}
      `}
      style={{
        left: `${button.x}%`,
        top: `${button.y}%`,
        width: `${width}px`,
        height: `${height}px`,
        transform: 'translate(-50%, -50%)',
        opacity: opacity / 100,
      }}
      onMouseDown={isLocked ? undefined : onMouseDown}
      onClick={onClick}
      title={`${button.label || button.mappedKey} (${button.mappedKey})`}
    >
      {/* Active indicator pulse */}
      {isActive && (
        <div className="absolute inset-0 rounded-lg animate-ping bg-emerald-400/20" />
      )}
      
      {/* Label */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <div className="flex items-center gap-1">
          {getTypeIcon()}
          <span className="text-[8px] font-bold text-white/80 uppercase truncate max-w-[80%]">
            {button.label || button.mappedKey}
          </span>
        </div>
        <span className="text-[7px] font-mono text-white/50 mt-0.5">
          {button.mappedKey}
        </span>
      </div>

      {/* Lock indicator */}
      {isLocked && (
        <div className="absolute -top-2 -right-2 w-4 h-4 bg-slate-800 rounded-full flex items-center justify-center border border-slate-600">
          <Lock className="w-2 h-2 text-slate-400" />
        </div>
      )}
    </div>
  );
});

OverlayNode.displayName = 'OverlayNode';

// ============================================
// SUB-COMPONENT: AnalogStickVisualizer
// ============================================
interface AnalogVisualizerProps {
  axes: { lx: number; ly: number; rx: number; ry: number };
  leftMapping?: ButtonMapping;
  rightMapping?: ButtonMapping;
  opacity: number;
}

const AnalogStickVisualizer = React.memo(({
  axes, leftMapping, rightMapping, opacity
}: AnalogVisualizerProps) => {
  const magnitude = (x: number, y: number) => Math.sqrt(x * x + y * y);
  const lMag = magnitude(axes.lx, axes.ly);
  const rMag = magnitude(axes.rx, axes.ry);

  if (lMag < 0.01 && rMag < 0.01) return null;

  return (
    <>
      {leftMapping && lMag > 0.01 && (
        <div
          className="absolute pointer-events-none"
          style={{
            left: `${leftMapping.x}%`,
            top: `${leftMapping.y}%`,
            transform: 'translate(-50%, -50%)',
            opacity: opacity / 100,
          }}
        >
          {/* Deadzone ring */}
          <div className="w-[120px] h-[120px] rounded-full border border-purple-500/20 absolute -translate-x-1/2 -translate-y-1/2 left-1/2 top-1/2" />
          {/* Stick position indicator */}
          <div
            className="w-4 h-4 rounded-full bg-purple-400 shadow-lg shadow-purple-500/50 absolute transition-all duration-75"
            style={{
              left: `${50 + axes.lx * 40}%`,
              top: `${50 + axes.ly * 40}%`,
              transform: 'translate(-50%, -50%)',
            }}
          />
          {/* Direction line */}
          <svg className="absolute inset-0 w-full h-full" style={{ opacity: 0.4 }}>
            <line
              x1="50%" y1="50%"
              x2={`${50 + axes.lx * 40}%`}
              y2={`${50 + axes.ly * 40}%`}
              stroke="#a855f7"
              strokeWidth="2"
              strokeDasharray="4 2"
            />
          </svg>
        </div>
      )}

      {rightMapping && rMag > 0.01 && (
        <div
          className="absolute pointer-events-none"
          style={{
            left: `${rightMapping.x}%`,
            top: `${rightMapping.y}%`,
            transform: 'translate(-50%, -50%)',
            opacity: opacity / 100,
          }}
        >
          <div className="w-[120px] h-[120px] rounded-full border border-cyan-500/20 absolute -translate-x-1/2 -translate-y-1/2 left-1/2 top-1/2" />
          <div
            className="w-4 h-4 rounded-full bg-cyan-400 shadow-lg shadow-cyan-500/50 absolute transition-all duration-75"
            style={{
              left: `${50 + axes.rx * 40}%`,
              top: `${50 + axes.ry * 40}%`,
              transform: 'translate(-50%, -50%)',
            }}
          />
          <svg className="absolute inset-0 w-full h-full" style={{ opacity: 0.4 }}>
            <line
              x1="50%" y1="50%"
              x2={`${50 + axes.rx * 40}%`}
              y2={`${50 + axes.ry * 40}%`}
              stroke="#22d3ee"
              strokeWidth="2"
              strokeDasharray="4 2"
            />
          </svg>
        </div>
      )}
    </>
  );
});

AnalogStickVisualizer.displayName = 'AnalogStickVisualizer';

// ============================================
// SUB-COMPONENT: NodeInspector
// ============================================
interface NodeInspectorProps {
  button: ButtonMapping;
  onUpdate: (updated: ButtonMapping) => void;
  onDelete: () => void;
  onClose: () => void;
}

const NodeInspector = ({ button, onUpdate, onDelete, onClose }: NodeInspectorProps) => {
  return (
    <div className="absolute bottom-4 right-4 w-72 bg-slate-900/95 backdrop-blur-md border border-slate-700/60 rounded-xl shadow-2xl z-50 overflow-hidden">
      <div className="px-4 py-3 bg-slate-950/60 border-b border-slate-800/60 flex items-center justify-between">
        <h3 className="text-xs font-bold text-slate-200 flex items-center gap-2">
          <Settings className="w-3.5 h-3.5 text-indigo-400" />
          Node Inspector
        </h3>
        <button onClick={onClose} className="p-1 hover:bg-slate-800 rounded transition-colors">
          <X className="w-3.5 h-3.5 text-slate-400" />
        </button>
      </div>

      <div className="p-4 space-y-3 max-h-80 overflow-y-auto">
        {/* Mapped Key */}
        <div>
          <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Mapped Key</label>
          <select
            value={button.mappedKey}
            onChange={(e) => onUpdate({ ...button, mappedKey: e.target.value })}
            className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
          >
            <option value="A">A (Button A)</option>
            <option value="B">B (Button B)</option>
            <option value="X">X (Button X)</option>
            <option value="Y">Y (Button Y)</option>
            <option value="LB">LB (Left Bumper)</option>
            <option value="RB">RB (Right Bumper)</option>
            <option value="LT">LT (Left Trigger)</option>
            <option value="RT">RT (Right Trigger)</option>
            <option value="L_STICK">Left Stick</option>
            <option value="R_STICK">Right Stick</option>
            <option value="DPAD_UP">D-Pad Up</option>
            <option value="DPAD_DOWN">D-Pad Down</option>
            <option value="DPAD_LEFT">D-Pad Left</option>
            <option value="DPAD_RIGHT">D-Pad Right</option>
            <option value="START">Start</option>
            <option value="SELECT">Select</option>
            <option value="TOUCH_1">Touch 1</option>
            <option value="TOUCH_2">Touch 2</option>
            <option value="TOUCH_3">Touch 3</option>
            <option value="TOUCH_4">Touch 4</option>
          </select>
        </div>

        {/* Label */}
        <div>
          <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Label</label>
          <input
            type="text"
            value={button.label || ''}
            onChange={(e) => onUpdate({ ...button, label: e.target.value })}
            className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
          />
        </div>

        {/* Position */}
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">X (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              step={0.5}
              value={button.x}
              onChange={(e) => onUpdate({ ...button, x: parseFloat(e.target.value) || 0 })}
              className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
            />
          </div>
          <div>
            <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Y (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              step={0.5}
              value={button.y}
              onChange={(e) => onUpdate({ ...button, y: parseFloat(e.target.value) || 0 })}
              className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
            />
          </div>
        </div>

        {/* Size */}
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Width (px)</label>
            <input
              type="number"
              min={20}
              max={300}
              value={button.width || 60}
              onChange={(e) => onUpdate({ ...button, width: parseInt(e.target.value) || 60 })}
              className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
            />
          </div>
          <div>
            <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Height (px)</label>
            <input
              type="number"
              min={20}
              max={300}
              value={button.height || 60}
              onChange={(e) => onUpdate({ ...button, height: parseInt(e.target.value) || 60 })}
              className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
            />
          </div>
        </div>

        {/* Type */}
        <div>
          <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Type</label>
          <select
            value={button.type || 'button'}
            onChange={(e) => onUpdate({ ...button, type: e.target.value as any })}
            className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
          >
            <option value="button">Button</option>
            <option value="analog">Analog Stick</option>
            <option value="dpad">D-Pad</option>
            <option value="swipe">Swipe Zone</option>
          </select>
        </div>

        {/* Android Event Code */}
        <div>
          <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Android Event Code</label>
          <input
            type="number"
            min={0}
            value={button.androidEventCode || 0}
            onChange={(e) => onUpdate({ ...button, androidEventCode: parseInt(e.target.value) || 0 })}
            className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
          />
        </div>

        {/* Swipe Direction (only for swipe type) */}
        {button.type === 'swipe' && (
          <div>
            <label className="text-[9px] font-mono text-slate-500 uppercase font-bold">Swipe Direction</label>
            <select
              value={button.swipeDirection || 'UP'}
              onChange={(e) => onUpdate({ ...button, swipeDirection: e.target.value as any })}
              className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500"
            >
              <option value="UP">Up</option>
              <option value="DOWN">Down</option>
              <option value="LEFT">Left</option>
              <option value="RIGHT">Right</option>
            </select>
          </div>
        )}

        {/* Delete Button */}
        <button
          onClick={onDelete}
          className="w-full mt-2 py-2 text-[10px] font-bold font-mono uppercase bg-red-950/40 hover:bg-red-900/40 border border-red-500/50 text-red-400 rounded-lg flex items-center justify-center gap-1.5"
        >
          <Trash2 className="w-3 h-3" />
          Delete Node
        </button>
      </div>
    </div>
  );
};

// ============================================
// SUB-COMPONENT: OverlayPalette
// ============================================
interface OverlayPaletteProps {
  isOpen: boolean;
  onToggle: () => void;
  onAddButton: (mappedKey: string, type: ButtonMapping['type']) => void;
}

const OverlayPalette = ({ isOpen, onToggle, onAddButton }: OverlayPaletteProps) => {
  // FIX BUG-M06: Listen for custom palette toggle event
  React.useEffect(() => {
    const handler = (e: Event) => {
      const customEvent = e as CustomEvent;
      if (customEvent.detail?.isOpen !== undefined) {
        if (customEvent.detail.isOpen !== isOpen) {
          onToggle();
        }
      }
    };
    window.addEventListener(PALETTE_TOGGLE_EVENT, handler);
    return () => window.removeEventListener(PALETTE_TOGGLE_EVENT, handler);
  }, [isOpen, onToggle]);

  const addButtonTypes = [
    { key: 'A', label: 'Button A', icon: <Circle className="w-4 h-4" />, type: 'button' as const },
    { key: 'B', label: 'Button B', icon: <Circle className="w-4 h-4" />, type: 'button' as const },
    { key: 'X', label: 'Button X', icon: <Circle className="w-4 h-4" />, type: 'button' as const },
    { key: 'Y', label: 'Button Y', icon: <Circle className="w-4 h-4" />, type: 'button' as const },
    { key: 'LB', label: 'Left Bumper', icon: <Minus className="w-4 h-4" />, type: 'button' as const },
    { key: 'RB', label: 'Right Bumper', icon: <Minus className="w-4 h-4" />, type: 'button' as const },
    { key: 'LT', label: 'Left Trigger', icon: <Triangle className="w-4 h-4" />, type: 'button' as const },
    { key: 'RT', label: 'Right Trigger', icon: <Triangle className="w-4 h-4" />, type: 'button' as const },
    { key: 'DPAD_UP', label: 'D-Pad Up', icon: <ArrowUp className="w-4 h-4" />, type: 'dpad' as const },
    { key: 'DPAD_DOWN', label: 'D-Pad Down', icon: <ArrowDown className="w-4 h-4" />, type: 'dpad' as const },
    { key: 'DPAD_LEFT', label: 'D-Pad Left', icon: <ArrowLeft className="w-4 h-4" />, type: 'dpad' as const },
    { key: 'DPAD_RIGHT', label: 'D-Pad Right', icon: <ArrowRight className="w-4 h-4" />, type: 'dpad' as const },
    { key: 'L_STICK', label: 'Left Stick', icon: <Circle className="w-4 h-4" />, type: 'analog' as const },
    { key: 'R_STICK', label: 'Right Stick', icon: <Circle className="w-4 h-4" />, type: 'analog' as const },
    { key: 'START', label: 'Start', icon: <Square className="w-4 h-4" />, type: 'button' as const },
    { key: 'SELECT', label: 'Select', icon: <Square className="w-4 h-4" />, type: 'button' as const },
    { key: 'TOUCH_1', label: 'Touch 1', icon: <Crosshair className="w-4 h-4" />, type: 'button' as const },
    { key: 'TOUCH_2', label: 'Touch 2', icon: <Crosshair className="w-4 h-4" />, type: 'button' as const },
  ];

  return (
    <div className="absolute top-4 left-4 z-50">
      <button
        onClick={onToggle}
        className={`w-10 h-10 rounded-xl flex items-center justify-center transition-all ${
          isOpen 
            ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/30' 
            : 'bg-slate-900/80 text-slate-400 hover:text-slate-200 border border-slate-700/60 hover:bg-slate-800/80'
        }`}
        title="Toggle Button Palette"
      >
        <Palette className="w-5 h-5" />
      </button>

      {isOpen && (
        <div className="absolute top-12 left-0 w-64 bg-slate-900/95 backdrop-blur-md border border-slate-700/60 rounded-xl shadow-2xl overflow-hidden">
          <div className="px-4 py-3 bg-slate-950/60 border-b border-slate-800/60">
            <h3 className="text-xs font-bold text-slate-200 flex items-center gap-2">
              <Plus className="w-3.5 h-3.5 text-indigo-400" />
              Add Button Node
            </h3>
          </div>
          <div className="p-3 grid grid-cols-2 gap-1.5 max-h-80 overflow-y-auto">
            {addButtonTypes.map(item => (
              <button
                key={item.key}
                onClick={() => onAddButton(item.key, item.type)}
                className="flex items-center gap-2 px-2.5 py-2 text-[10px] font-mono text-slate-300 hover:text-white hover:bg-slate-800/60 rounded-lg transition-colors text-left"
              >
                <span className="text-indigo-400">{item.icon}</span>
                <span className="truncate">{item.label}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================
// SUB-COMPONENT: OverlayToolbar
// ============================================
interface OverlayToolbarProps {
  globalOpacity: number;
  onOpacityChange: (value: number) => void;
  bgDimLevel: number;
  onBgDimChange: (value: number) => void;
  showGrid: boolean;
  onToggleGrid: () => void;
  allLocked: boolean;
  onToggleLockAll: () => void;
  showOverlay: boolean;
  onToggleShow: () => void;
}

const OverlayToolbar = ({
  globalOpacity, onOpacityChange,
  bgDimLevel, onBgDimChange,
  showGrid, onToggleGrid,
  allLocked, onToggleLockAll,
  showOverlay, onToggleShow
}: OverlayToolbarProps) => {
  return (
    <div className="absolute top-4 right-4 z-50 flex flex-col gap-2">
      {/* Show/Hide Overlay */}
      <button
        onClick={onToggleShow}
        className={`w-9 h-9 rounded-lg flex items-center justify-center transition-all ${
          showOverlay 
            ? 'bg-emerald-600/80 text-white' 
            : 'bg-slate-900/80 text-slate-500 border border-slate-700/60'
        }`}
        title={showOverlay ? 'Hide Overlay' : 'Show Overlay'}
      >
        {showOverlay ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
      </button>

      {/* Grid Toggle */}
      <button
        onClick={onToggleGrid}
        className={`w-9 h-9 rounded-lg flex items-center justify-center transition-all ${
          showGrid 
            ? 'bg-indigo-600/80 text-white' 
            : 'bg-slate-900/80 text-slate-500 border border-slate-700/60'
        }`}
        title="Toggle Grid"
      >
        <Grid3X3 className="w-4 h-4" />
      </button>

      {/* Lock All */}
      <button
        onClick={onToggleLockAll}
        className={`w-9 h-9 rounded-lg flex items-center justify-center transition-all ${
          allLocked 
            ? 'bg-amber-600/80 text-white' 
            : 'bg-slate-900/80 text-slate-500 border border-slate-700/60'
        }`}
        title={allLocked ? 'Unlock All' : 'Lock All'}
      >
        {allLocked ? <Lock className="w-4 h-4" /> : <Unlock className="w-4 h-4" />}
      </button>

      {/* Opacity Slider */}
      <div className="bg-slate-900/90 backdrop-blur-md border border-slate-700/60 rounded-lg p-2 w-9">
        <input
          type="range"
          min={10}
          max={100}
          value={globalOpacity}
          onChange={(e) => onOpacityChange(parseInt(e.target.value))}
          className="w-full h-1 appearance-none bg-slate-700 rounded-full cursor-pointer"
          style={{ writingMode: 'bt-lr', WebkitAppearance: 'slider-vertical' } as any}
          title={`Opacity: ${globalOpacity}%`}
          orient="vertical"
        />
        <span className="text-[7px] font-mono text-slate-500 text-center block mt-1">{globalOpacity}%</span>
      </div>

      {/* Background Dim */}
      <div className="bg-slate-900/90 backdrop-blur-md border border-slate-700/60 rounded-lg p-2 w-9">
        <input
          type="range"
          min={0}
          max={100}
          value={bgDimLevel}
          onChange={(e) => onBgDimChange(parseInt(e.target.value))}
          className="w-full h-1 appearance-none bg-slate-700 rounded-full cursor-pointer"
          style={{ writingMode: 'bt-lr', WebkitAppearance: 'slider-vertical' } as any}
          title={`Background Dim: ${bgDimLevel}%`}
          orient="vertical"
        />
        <span className="text-[7px] font-mono text-slate-500 text-center block mt-1">Dim {bgDimLevel}%</span>
      </div>
    </div>
  );
};

// ============================================
// MAIN COMPONENT: OverlayWysiwyg
// ============================================
export default function OverlayWysiwyg({
  activeProfile,
  onUpdateProfile,
  onLogMessage,
  activeKeys = [],
  activeAxes = { lx: 0, ly: 0, rx: 0, ry: 0 },
  isNativeOverlay = false
}: OverlayWysiwygProps) {
  // State
  const [selectedButtonId, setSelectedButtonId] = React.useState<string | null>(null);
  const [isDragging, setIsDragging] = React.useState(false);
  const [dragOffset, setDragOffset] = React.useState({ x: 0, y: 0 });
  const [showPalette, setShowPalette] = React.useState(false);
  const [showGrid, setShowGrid] = React.useState(true);
  const [allLocked, setAllLocked] = React.useState(false);
  const [lockedButtons, setLockedButtons] = React.useState<Set<string>>(new Set());
  const [showOverlay, setShowOverlay] = React.useState(true);
  const canvasRef = React.useRef<HTMLDivElement>(null);

  // FIX BUG-M10: Sync globalNodeOpacity dengan activeProfile
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(activeProfile.globalOpacity ?? 80);

  React.useEffect(() => {
    const profileOpacity = activeProfile.globalOpacity ?? 80;
    if (profileOpacity !== globalNodeOpacity) {
      setGlobalNodeOpacity(profileOpacity);
    }
  }, [activeProfile.id, activeProfile.globalOpacity]);

  // FIX BUG-L03: Sync bgDimLevel dengan activeProfile
  const [bgDimLevel, setBgDimLevel] = React.useState(activeProfile.bgDimLevel ?? 50);

  React.useEffect(() => {
    if (activeProfile.bgDimLevel !== undefined && activeProfile.bgDimLevel !== bgDimLevel) {
      setBgDimLevel(activeProfile.bgDimLevel);
    }
  }, [activeProfile.bgDimLevel]);

  // FIX BUG-L03: Save bgDimLevel saat berubah
  React.useEffect(() => {
    if (bgDimLevel !== (activeProfile.bgDimLevel ?? 50)) {
      onUpdateProfile({ ...activeProfile, bgDimLevel });
    }
  }, [bgDimLevel]);

  // Save opacity saat berubah
  React.useEffect(() => {
    if (globalNodeOpacity !== (activeProfile.globalOpacity ?? 80)) {
      onUpdateProfile({ ...activeProfile, globalOpacity: globalNodeOpacity });
    }
  }, [globalNodeOpacity]);

  const selectedButton = activeProfile.buttons?.find(b => b.id === selectedButtonId) || null;

  // Drag handlers
  const handleNodeMouseDown = (e: React.MouseEvent, button: ButtonMapping) => {
    if (allLocked || lockedButtons.has(button.id)) return;
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
    setSelectedButtonId(button.id);

    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;

    const mouseXPercent = ((e.clientX - rect.left) / rect.width) * 100;
    const mouseYPercent = ((e.clientY - rect.top) / rect.height) * 100;

    setDragOffset({
      x: mouseXPercent - button.x,
      y: mouseYPercent - button.y,
    });
  };

  const handleCanvasMouseMove = (e: React.MouseEvent) => {
    if (!isDragging || !selectedButtonId) return;

    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;

    const mouseXPercent = Math.max(0, Math.min(100, ((e.clientX - rect.left) / rect.width) * 100));
    const mouseYPercent = Math.max(0, Math.min(100, ((e.clientY - rect.top) / rect.height) * 100));

    const newX = Math.round((mouseXPercent - dragOffset.x) * 10) / 10;
    const newY = Math.round((mouseYPercent - dragOffset.y) * 10) / 10;

    const updatedButtons = activeProfile.buttons?.map(b =>
      b.id === selectedButtonId
        ? { ...b, x: Math.max(0, Math.min(100, newX)), y: Math.max(0, Math.min(100, newY)) }
        : b
    );

    onUpdateProfile({ ...activeProfile, buttons: updatedButtons || [] });
  };

  const handleCanvasMouseUp = () => {
    if (isDragging && selectedButtonId) {
      const btn = activeProfile.buttons?.find(b => b.id === selectedButtonId);
      if (btn) {
        onLogMessage(`[OVERLAY] Moved "${btn.label || btn.mappedKey}" to (${btn.x}%, ${btn.y}%)`);
      }
    }
    setIsDragging(false);
  };

  // Button CRUD
  const handleAddButton = (mappedKey: string, type: ButtonMapping['type']) => {
    const newButton: ButtonMapping = {
      id: `btn_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      mappedKey,
      x: 50,
      y: 50,
      width: type === 'analog' ? 120 : 60,
      height: type === 'analog' ? 120 : 60,
      type,
      label: mappedKey,
      androidEventCode: 0,
    };

    const updatedButtons = [...(activeProfile.buttons || []), newButton];
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
    setSelectedButtonId(newButton.id);
    onLogMessage(`[OVERLAY] Added button "${mappedKey}" at center`);
  };

  const handleDeleteButton = (buttonId: string) => {
    const btn = activeProfile.buttons?.find(b => b.id === buttonId);
    const updatedButtons = activeProfile.buttons?.filter(b => b.id !== buttonId) || [];
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
    if (selectedButtonId === buttonId) {
      setSelectedButtonId(null);
    }
    onLogMessage(`[OVERLAY] Deleted button "${btn?.label || btn?.mappedKey || buttonId}"`);
  };

  const handleUpdateButton = (updated: ButtonMapping) => {
    const updatedButtons = activeProfile.buttons?.map(b =>
      b.id === updated.id ? updated : b
    ) || [];
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const handleDuplicateButton = () => {
    if (!selectedButton) return;
    const duplicate: ButtonMapping = {
      ...JSON.parse(JSON.stringify(selectedButton)),
      id: `btn_${Date.now()}`,
      x: Math.min(100, selectedButton.x + 5),
      y: Math.min(100, selectedButton.y + 5),
    };
    const updatedButtons = [...(activeProfile.buttons || []), duplicate];
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
    setSelectedButtonId(duplicate.id);
    onLogMessage(`[OVERLAY] Duplicated "${selectedButton.label || selectedButton.mappedKey}"`);
  };

  // Toggle palette
  const handleTogglePalette = React.useCallback(() => {
    setShowPalette(prev => !prev);
  }, []);

  // Toggle lock all
  const handleToggleLockAll = () => {
    setAllLocked(prev => !prev);
    onLogMessage(`[OVERLAY] All buttons ${!allLocked ? 'locked' : 'unlocked'}`);
  };

  // Keyboard shortcuts
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Delete' && selectedButtonId) {
        handleDeleteButton(selectedButtonId);
      }
      if (e.key === 'Escape') {
        setSelectedButtonId(null);
        setShowPalette(false);
      }
      if (e.key === 'p' && e.ctrlKey) {
        e.preventDefault();
        handleTogglePalette();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedButtonId, allLocked]);

  // Get left/right stick mappings for analog visualizer
  const leftStickMapping = activeProfile.buttons?.find(b => b.mappedKey === 'L_STICK');
  const rightStickMapping = activeProfile.buttons?.find(b => b.mappedKey === 'R_STICK');

  return (
    <div className="relative w-full h-full min-h-[500px] bg-slate-950 rounded-xl overflow-hidden border border-slate-800/60">
      {/* Header Bar */}
      <div className="absolute top-0 left-0 right-0 z-40 bg-slate-950/80 backdrop-blur-md border-b border-slate-800/60 px-4 py-2 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Layers className="w-4 h-4 text-indigo-400" />
          <span className="text-xs font-bold text-slate-200 uppercase tracking-wider">
            WYSIWYG Overlay
          </span>
          <span className="text-[9px] font-mono bg-indigo-950/50 text-indigo-400 border border-indigo-900/60 px-2 py-0.5 rounded">
            {activeProfile.name}
          </span>
          <span className="text-[9px] font-mono text-slate-500">
            {activeProfile.buttons?.length || 0} nodes
          </span>
        </div>
        <div className="flex items-center gap-2">
          {/* FIX BUG-C01: Active keys indicator */}
          {activeKeys.length > 0 && (
            <div className="flex items-center gap-1 px-2 py-1 bg-emerald-950/40 border border-emerald-500/30 rounded">
              <Zap className="w-3 h-3 text-emerald-400 animate-pulse" />
              <span className="text-[9px] font-mono text-emerald-400">
                Active: {activeKeys.join(', ')}
              </span>
            </div>
          )}
          {/* Anti-ban indicator */}
          {activeProfile.antiBanEnabled && (
            <div className="flex items-center gap-1 px-2 py-1 bg-amber-950/40 border border-amber-500/30 rounded">
              <Shield className="w-3 h-3 text-amber-400" />
              <span className="text-[9px] font-mono text-amber-400">Anti-Ban</span>
            </div>
          )}
          {/* Native overlay indicator */}
          {isNativeOverlay && (
            <div className="flex items-center gap-1 px-2 py-1 bg-purple-950/40 border border-purple-500/30 rounded">
              <div className="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse" />
              <span className="text-[9px] font-mono text-purple-400">Native</span>
            </div>
          )}
        </div>
      </div>

      {/* Canvas Area */}
      <div
        ref={canvasRef}
        className="absolute inset-0 mt-10 cursor-crosshair select-none"
        style={{
          background: getScreenshotBackground(activeProfile.game || activeProfile.name.toLowerCase()),
        }}
        onMouseMove={handleCanvasMouseMove}
        onMouseUp={handleCanvasMouseUp}
        onMouseLeave={handleCanvasMouseUp}
        onClick={() => { if (!isDragging) setSelectedButtonId(null); }}
      >
        {/* Background Dim Overlay */}
        <div
          className="absolute inset-0 pointer-events-none"
          style={{
            backgroundColor: `rgba(0, 0, 0, ${bgDimLevel / 100 * 0.7})`,
          }}
        />

        {/* Grid */}
        {showGrid && (
          <div className="absolute inset-0 pointer-events-none opacity-10">
            <svg width="100%" height="100%">
              <defs>
                <pattern id="grid" width="5%" height="5%" patternUnits="userSpaceOnUse">
                  <path d="M 50 0 L 0 0 0 50" fill="none" stroke="white" strokeWidth="0.5" />
                </pattern>
              </defs>
              <rect width="100%" height="100%" fill="url(#grid)" />
              {/* Center crosshair */}
              <line x1="50%" y1="0" x2="50%" y2="100%" stroke="white" strokeWidth="0.3" opacity="0.3" />
              <line x1="0" y1="50%" x2="100%" y2="50%" stroke="white" strokeWidth="0.3" opacity="0.3" />
            </svg>
          </div>
        )}

        {/* FIX BUG-C01: Analog Stick Visualizer */}
        {showOverlay && (
          <AnalogStickVisualizer
            axes={activeAxes}
            leftMapping={leftStickMapping}
            rightMapping={rightStickMapping}
            opacity={globalNodeOpacity}
          />
        )}

        {/* Button Nodes */}
        {showOverlay && activeProfile.buttons?.map(button => (
          <OverlayNode
            key={button.id}
            button={button}
            isActive={activeKeys.includes(button.mappedKey)}
            isSelected={selectedButtonId === button.id}
            opacity={globalNodeOpacity}
            isLocked={allLocked || lockedButtons.has(button.id)}
            onMouseDown={(e) => handleNodeMouseDown(e, button)}
            onClick={() => setSelectedButtonId(button.id)}
          />
        ))}

        {/* Palette */}
        <OverlayPalette
          isOpen={showPalette}
          onToggle={handleTogglePalette}
          onAddButton={handleAddButton}
        />

        {/* Toolbar */}
        <OverlayToolbar
          globalOpacity={globalNodeOpacity}
          onOpacityChange={setGlobalNodeOpacity}
          bgDimLevel={bgDimLevel}
          onBgDimChange={setBgDimLevel}
          showGrid={showGrid}
          onToggleGrid={() => setShowGrid(!showGrid)}
          allLocked={allLocked}
          onToggleLockAll={handleToggleLockAll}
          showOverlay={showOverlay}
          onToggleShow={() => setShowOverlay(!showOverlay)}
        />

        {/* Node Inspector */}
        {selectedButton && (
          <NodeInspector
            button={selectedButton}
            onUpdate={handleUpdateButton}
            onDelete={() => handleDeleteButton(selectedButton.id)}
            onClose={() => setSelectedButtonId(null)}
          />
        )}
      </div>

      {/* Bottom Status Bar */}
      <div className="absolute bottom-0 left-0 right-0 z-40 bg-slate-950/80 backdrop-blur-md border-t border-slate-800/60 px-4 py-1.5 flex items-center justify-between">
        <div className="flex items-center gap-4 text-[9px] font-mono text-slate-500">
          <span>Profile: {activeProfile.name}</span>
          <span>Nodes: {activeProfile.buttons?.length || 0}</span>
          <span>Opacity: {globalNodeOpacity}%</span>
          <span>Dim: {bgDimLevel}%</span>
        </div>
        <div className="flex items-center gap-3 text-[9px] font-mono text-slate-500">
          {selectedButton && (
            <span className="text-indigo-400">
              Selected: {selectedButton.label || selectedButton.mappedKey} ({selectedButton.x.toFixed(1)}%, {selectedButton.y.toFixed(1)}%)
            </span>
          )}
          <span>Ctrl+P: Palette | Del: Delete | Esc: Deselect</span>
        </div>
      </div>
    </div>
  );
}
