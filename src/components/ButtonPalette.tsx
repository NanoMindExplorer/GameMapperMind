import React from 'react';
import { X, Layers } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function ButtonPalette({ h }: { h: OverlayWysiwygHook }) {
  if (!h.showPalette) return null;
  
  return (
    <div className="absolute top-24 left-1/2 -translate-x-1/2 z-50 bg-slate-900/95 backdrop-blur-md border border-slate-700/80 rounded-xl p-4 shadow-2xl w-[90%] max-w-[600px] pointer-events-auto transition-all">
      <div className="flex justify-between items-center mb-3 border-b border-slate-800 pb-2">
        <div className="text-[11px] font-bold text-slate-300 uppercase tracking-wider">Gamepad Palette</div>
        <button onClick={() => h.setShowPalette(false)} className="text-slate-400 hover:text-white transition-colors">
          <X className="w-4 h-4" />
        </button>
      </div>

      <div className="space-y-4">
        <div>
          <h4 className="text-[10px] uppercase font-bold text-slate-500 mb-2">Standard Buttons</h4>
          <div className="flex flex-wrap gap-2">
            <button onClick={() => h.handleAddSpecificButton('A', 'BUTTON_A', 96)} className="w-10 h-10 rounded-full bg-slate-800 hover:bg-emerald-600 border border-slate-700 text-slate-200 font-bold shadow transition-colors flex items-center justify-center">A</button>
            <button onClick={() => h.handleAddSpecificButton('B', 'BUTTON_B', 97)} className="w-10 h-10 rounded-full bg-slate-800 hover:bg-rose-600 border border-slate-700 text-slate-200 font-bold shadow transition-colors flex items-center justify-center">B</button>
            <button onClick={() => h.handleAddSpecificButton('X', 'BUTTON_X', 99)} className="w-10 h-10 rounded-full bg-slate-800 hover:bg-blue-600 border border-slate-700 text-slate-200 font-bold shadow transition-colors flex items-center justify-center">X</button>
            <button onClick={() => h.handleAddSpecificButton('Y', 'BUTTON_Y', 100)} className="w-10 h-10 rounded-full bg-slate-800 hover:bg-amber-500 border border-slate-700 text-slate-200 font-bold shadow transition-colors flex items-center justify-center">Y</button>
            <button onClick={() => h.handleAddSpecificButton('L1', 'BUTTON_L1', 101, 70)} className="h-10 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-medium text-[11px] shadow transition-colors flex items-center justify-center">LB / L1</button>
            <button onClick={() => h.handleAddSpecificButton('L2', 'BUTTON_L2', 104, 75)} className="h-10 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-medium text-[11px] shadow transition-colors flex items-center justify-center">LT / L2</button>
            <button onClick={() => h.handleAddSpecificButton('R1', 'BUTTON_R1', 102, 70)} className="h-10 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-medium text-[11px] shadow transition-colors flex items-center justify-center">RB / R1</button>
            <button onClick={() => h.handleAddSpecificButton('R2', 'BUTTON_R2', 105, 75)} className="h-10 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-medium text-[11px] shadow transition-colors flex items-center justify-center">RT / R2</button>
          </div>
        </div>

        <div className="flex gap-6">
          <div>
            <h4 className="text-[10px] uppercase font-bold text-slate-500 mb-2">D-Pad</h4>
            <div className="w-20 h-20 relative bg-slate-900 rounded-full border border-slate-800 flex items-center justify-center">
              <div className="absolute top-1 left-1/2 -translate-x-1/2">
                <button onClick={() => h.handleAddSpecificButton('UP', 'DPAD_UP', 106, 50)} className="w-6 h-6 rounded-t bg-slate-800 hover:bg-indigo-500 text-[10px] text-white flex items-center justify-center">↑</button>
              </div>
              <div className="absolute left-1 top-1/2 -translate-y-1/2">
                <button onClick={() => h.handleAddSpecificButton('LEFT', 'DPAD_LEFT', 108, 50)} className="w-6 h-6 rounded-l bg-slate-800 hover:bg-indigo-500 text-[10px] text-white flex items-center justify-center">←</button>
              </div>
              <div className="absolute right-1 top-1/2 -translate-y-1/2">
                <button onClick={() => h.handleAddSpecificButton('RIGHT', 'DPAD_RIGHT', 109, 50)} className="w-6 h-6 rounded-r bg-slate-800 hover:bg-indigo-500 text-[10px] text-white flex items-center justify-center">→</button>
              </div>
              <div className="absolute bottom-1 left-1/2 -translate-x-1/2">
                <button onClick={() => h.handleAddSpecificButton('DOWN', 'DPAD_DOWN', 107, 50)} className="w-6 h-6 rounded-b bg-slate-800 hover:bg-indigo-500 text-[10px] text-white flex items-center justify-center">↓</button>
              </div>
              <div className="w-5 h-5 bg-slate-700 rounded-sm"></div>
            </div>
          </div>
          
          <div className="flex-1">
            <h4 className="text-[10px] uppercase font-bold text-slate-500 mb-2">Special & Sticks</h4>
            <div className="flex flex-wrap gap-2 mb-2">
              <button onClick={() => h.handleAddSpecificButton('L3', 'BUTTON_L3', 103)} className="w-10 h-10 rounded-full border-2 border-dashed border-slate-600 bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold shadow flex items-center justify-center">L3</button>
              <button onClick={() => h.handleAddSpecificButton('R3', 'BUTTON_R3', 106)} className="w-10 h-10 rounded-full border-2 border-dashed border-slate-600 bg-slate-800 hover:bg-slate-700 text-slate-300 text-[10px] font-bold shadow flex items-center justify-center">R3</button>
              <button onClick={() => h.handleAddSpecificButton('SELECT', 'BUTTON_SELECT', 109, 45)} className="w-12 h-6 rounded-full bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-bold text-[9px] shadow transition-colors flex items-center justify-center">SELECT</button>
              <button onClick={() => h.handleAddSpecificButton('START', 'BUTTON_START', 108, 45)} className="w-12 h-6 rounded-full bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 font-bold text-[9px] shadow transition-colors flex items-center justify-center">START</button>
              <button onClick={() => h.handleAddSpecificButton('M1', 'BUTTON_M1', 0, 45)} className="w-12 h-6 rounded bg-indigo-900/40 hover:bg-indigo-600 border border-indigo-700/50 text-indigo-300 font-bold text-[9px] shadow transition-colors flex items-center justify-center">M1</button>
              <button onClick={() => h.handleAddSpecificButton('M2', 'BUTTON_M2', 0, 45)} className="w-12 h-6 rounded bg-indigo-900/40 hover:bg-indigo-600 border border-indigo-700/50 text-indigo-300 font-bold text-[9px] shadow transition-colors flex items-center justify-center">M2</button>
            </div>
            
            <div className="flex gap-2 mb-3">
              <button onClick={() => h.handleAddSpecificButton('L-Stick', 'L_STICK', 0, 100, 'analog_stick')} className="h-10 px-3 rounded-lg bg-blue-900/60 hover:bg-blue-600 border border-blue-700 text-blue-200 font-medium text-[11px] shadow transition-colors flex items-center gap-1">
                <Layers className="w-3.5 h-3.5" />
                Left Analog
              </button>
              <button onClick={() => h.handleAddSpecificButton('R-Stick', 'R_STICK', 0, 100, 'analog_stick')} className="h-10 px-3 rounded-lg bg-pink-900/60 hover:bg-pink-600 border border-pink-700 text-pink-200 font-medium text-[11px] shadow transition-colors flex items-center gap-1">
                <Layers className="w-3.5 h-3.5" />
                Right Analog
              </button>
            </div>
            
            <div>
              <h4 className="text-[10px] uppercase font-bold text-slate-500 mb-1.5 mt-2">Motion & Swipe Nodes</h4>
              <div className="flex flex-wrap gap-2">
                <button onClick={() => h.handleAddNewButton('swipe', 'UP')} className="h-5 px-2 rounded bg-indigo-900/60 hover:bg-indigo-600 border border-indigo-700 text-indigo-200 text-[9px] shadow transition-colors">Swipe ↑</button>
                <button onClick={() => h.handleAddNewButton('swipe', 'DOWN')} className="h-5 px-2 rounded bg-indigo-900/60 hover:bg-indigo-600 border border-indigo-700 text-indigo-200 text-[9px] shadow transition-colors">Swipe ↓</button>
                <button onClick={() => h.handleAddNewButton('swipe', 'LEFT')} className="h-5 px-2 rounded bg-indigo-900/60 hover:bg-indigo-600 border border-indigo-700 text-indigo-200 text-[9px] shadow transition-colors">Swipe ←</button>
                <button onClick={() => h.handleAddNewButton('swipe', 'RIGHT')} className="h-5 px-2 rounded bg-indigo-900/60 hover:bg-indigo-600 border border-indigo-700 text-indigo-200 text-[9px] shadow transition-colors">Swipe →</button>
                <div className="w-px h-5 bg-slate-700 mx-1"></div>
                <button onClick={() => h.handleAddNewButton('gyro_area')} className="h-5 px-2 rounded bg-purple-900/60 hover:bg-purple-600 border border-purple-700 text-purple-200 text-[9px] shadow transition-colors">Camera Gyro (C)</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
