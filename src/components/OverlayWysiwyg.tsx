import React from 'react';
import { GamepadProfile } from '../types';
import { useOverlayWysiwyg } from '../hooks/useOverlayWysiwyg';
import ProfileToolbar from './ProfileToolbar';
import ScreenshotBackground from './ScreenshotBackground';
import OverlayCanvas from './OverlayCanvas';
import ButtonPalette from './ButtonPalette';
import ButtonPropertyPanel from './ButtonPropertyPanel';

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

  return (
    <div className="flex flex-col h-full bg-slate-950 font-sans text-slate-200 overflow-hidden">
      <ProfileToolbar h={h} />
      
      <div className="flex-1 relative min-h-0 container mx-auto px-4 max-w-7xl overflow-hidden">
        <ScreenshotBackground h={h}>
          <OverlayCanvas h={h} />
          <ButtonPropertyPanel h={h} />
        </ScreenshotBackground>
        <ButtonPalette h={h} />

        {/* Floating Action Button to toggle Palette (Nexion Hub) */}
        {!h.isNativeOverlay && (
          <div 
            className={`absolute z-50 shadow-[0_0_15px_rgba(99,102,241,0.6)] cursor-pointer pointer-events-auto flex flex-col items-center select-none touch-none ${h.showPalette ? 'scale-110' : 'opacity-70 flex hover:opacity-100'}`}
            style={{
              left: `${h.nexionPos.x}%`,
              top: `${h.nexionPos.y}%`,
              transform: 'translate(-50%, -50%)',
              transition: h.isDraggingNexion ? 'none' : 'transform 0.2s',
            }}
            onMouseDown={(e) => {
              h.setIsDraggingNexion(true);
              h.nexionDragHasMoved.current = false;
            }}
            onTouchStart={(e) => {
              h.setIsDraggingNexion(true);
              h.nexionDragHasMoved.current = false;
            }}
            onClick={(e) => {
              if (h.nexionDragHasMoved.current) return;
              h.setShowPalette(!h.showPalette);
            }}
          >
             <div className={`w-12 h-12 ${h.showPalette ? 'bg-indigo-600' : 'bg-slate-900/80'} rounded-full border-2 ${h.showPalette ? 'border-indigo-300' : 'border-indigo-500'} flex items-center justify-center backdrop-blur shadow-xl overflow-hidden hover:bg-indigo-500 transition-colors`}>
               <img src="/icon.svg" alt="Nexion" className={`w-7 h-7 ${h.showPalette ? 'opacity-100' : 'opacity-80'}`} />
             </div>
             {!h.showPalette && <div className="text-[9px] font-bold tracking-widest text-indigo-300 mt-2 drop-shadow-md text-center bg-slate-900/80 px-2.5 py-0.5 rounded-full border border-indigo-500/40">NEXION</div>}
          </div>
        )}
      </div>
    </div>
  );
}
