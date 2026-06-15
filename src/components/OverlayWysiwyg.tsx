/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';
import { Layers, X, Move, Eye, EyeOff } from 'lucide-react';

interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
  activeKeys?: string[];
  activeAxes?: { lx: number; ly: number; rx: number; ry: number };
  isFloatingMode?: boolean;        // ← NEW PROP
}

export default function OverlayWysiwyg({
  activeProfile,
  onUpdateProfile,
  onLogMessage,
  activeKeys = [],
  activeAxes = { lx: 0, ly: 0, rx: 0, ry: 0 },
  isFloatingMode = false,          // default false (untuk editor)
}: OverlayWysiwygProps) {

  const [selectedButtonId, setSelectedButtonId] = React.useState<string | null>(null);
  const [showPalette, setShowPalette] = React.useState(false);
  const [isEditMode, setIsEditMode] = React.useState(false); // controlled by native

  const [hideAllNodes, setHideAllNodes] = React.useState(false);
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(activeProfile.globalOpacity ?? 80);

  // ==================== GLOBAL BRIDGE UNTUK NATIVE ====================
  React.useEffect(() => {
    // Dipanggil oleh FloatingOverlayService.java
    (window as any).updateOverlayConfig = (config: GamepadProfile) => {
      // Update profile dari native
      onUpdateProfile(config);
    };

    (window as any).setOverlayEditMode = (editMode: boolean) => {
      setIsEditMode(editMode);
      setShowPalette(editMode); // buka palette otomatis saat edit mode
      onLogMessage(`Overlay: ${editMode ? 'Edit Mode ON' : 'Play Mode'}`);
    };

    return () => {
      delete (window as any).updateOverlayConfig;
      delete (window as any).setOverlayEditMode;
    };
  }, [onUpdateProfile, onLogMessage]);

  const selectedButton = activeProfile.buttons.find(b => b.id === selectedButtonId);

  // ==================== DRAG HANDLER (tetap aktif di floating) ====================
  const handleDragMove = (e: React.MouseEvent | React.TouchEvent) => {
    if (!isEditMode || !selectedButtonId) return;

    // ... (logic drag kamu tetap sama)
    const container = (e.currentTarget as HTMLElement).getBoundingClientRect();
    let clientX: number, clientY: number;

    if ('touches' in e) {
      clientX = (e as React.TouchEvent).touches[0].clientX;
      clientY = (e as React.TouchEvent).touches[0].clientY;
    } else {
      clientX = (e as React.MouseEvent).clientX;
      clientY = (e as React.MouseEvent).clientY;
    }

    const x = Math.max(0, Math.min(100, ((clientX - container.left) / container.width) * 100));
    const y = Math.max(0, Math.min(100, ((clientY - container.top) / container.height) * 100));

    const updatedButtons = activeProfile.buttons.map(b =>
      b.id === selectedButtonId ? { ...b, x, y } : b
    );
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  // ==================== RENDER ====================
  return (
    <div className={`w-full h-full relative select-none touch-none ${isFloatingMode ? 'bg-transparent' : 'bg-slate-900'}`}>

      {/* MAIN CANVAS */}
      <div
        onClick={() => setSelectedButtonId(null)}
        onMouseMove={handleDragMove}
        onTouchMove={handleDragMove}
        onMouseUp={() => setSelectedButtonId(null)}
        onTouchEnd={() => setSelectedButtonId(null)}
        className="relative w-full h-full overflow-hidden"
        style={{ background: isFloatingMode ? 'transparent' : undefined }}
      >
        {/* Virtual Buttons */}
        {activeProfile.buttons.map((btn) => {
          const isSelected = btn.id === selectedButtonId;
          const isSwipe = btn.type === 'swipe' || (btn.androidEventCode >= 201 && btn.androidEventCode <= 204);
          const isButtonActive = btn.mappedKey ? activeKeys.includes(btn.mappedKey) : false;

          let btnColor = 'border-indigo-400 text-white';

          if (btn.type === 'analog_stick' && btn.mappedKey === 'R_STICK') btnColor = 'border-pink-400 text-white';
          else if (btn.type === 'gyro_area') btnColor = 'border-pink-500 text-white';
          else if (isSwipe) btnColor = 'border-purple-400 text-white';

          return (
            <div
              key={btn.id}
              onMouseDown={(e) => { if (isEditMode) setSelectedButtonId(btn.id); }}
              onTouchStart={(e) => { if (isEditMode) setSelectedButtonId(btn.id); }}
              className={`absolute flex items-center justify-center font-bold tracking-tight transition-all pointer-events-auto
                ${btn.width > 80 ? 'rounded-[40%]' : 'rounded-full'} border-[2.5px] ${btnColor}`}
              style={{
                left: `${btn.x}%`,
                top: `${btn.y}%`,
                width: `${btn.width}px`,
                height: `${btn.height}px`,
                transform: 'translate(-50%, -50%)',
                opacity: hideAllNodes ? 0 : btn.opacity * (globalNodeOpacity / 100),
                backgroundColor: isButtonActive ? 'rgba(255,255,255,0.2)' : 'rgba(30,41,59,0.4)',
                boxShadow: isButtonActive ? '0 0 20px rgba(129, 140, 248, 0.7)' : undefined,
              }}
            >
              <span className="text-center text-sm drop-shadow-md">{btn.label}</span>

              {/* Analog Stick Dot */}
              {btn.type === 'analog_stick' && (
                <div
                  className="absolute w-1/3 h-1/3 bg-white/60 rounded-full border border-white"
                  style={{
                    transform: `translate(${btn.mappedKey === 'L_STICK' 
                      ? activeAxes.lx * (btn.width / 3.5) 
                      : activeAxes.rx * (btn.width / 3.5)}px, 
                      ${btn.mappedKey === 'L_STICK' 
                      ? activeAxes.ly * (btn.height / 3.5) 
                      : activeAxes.ry * (btn.height / 3.5)}px)`,
                  }}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Floating Palette Button (hanya muncul di Edit Mode) */}
      {isEditMode && (
        <div className="absolute top-6 right-6 z-50">
          {/* Tombol palette kamu bisa disederhanakan atau tetap full */}
        </div>
      )}

    </div>
  );
}
