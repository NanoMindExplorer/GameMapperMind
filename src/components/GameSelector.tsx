/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { GamepadProfile } from '../types';
import { Target, Settings, Sliders, Box, HardDrive, Cpu, AlertTriangle, Play, Flame } from 'lucide-react';

interface GameSelectorProps {
  profiles: GamepadProfile[];
  activeProfileId: string;
  onProfileSelect: (id: string) => void;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
}

export default function GameSelector({ profiles, activeProfileId, onProfileSelect, onUpdateProfile, onLogMessage }: GameSelectorProps) {
  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];
  const [activeSubTab, setActiveSubTab] = React.useState<'parameters' | 'hardware'>('parameters');

  const updateProfileValue = (key: keyof GamepadProfile, value: any) => {
    const updated = { ...activeProfile, [key]: value };
    onUpdateProfile(updated);
    onLogMessage(`Profile Engine: Modifying [${activeProfile.name}] attribute -> ${key} = ${value}`);
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl">
      <div className="bg-slate-950 px-6 py-4 border-b border-slate-800 flex flex-wrap justify-between items-center gap-4">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-pink-500/10 text-pink-400 rounded-lg border border-pink-500/20">
            <Flame className="w-5 h-5 animate-pulse" />
          </div>
          <div>
            <h2 className="text-base font-bold font-sans tracking-tight text-slate-100 flex items-center gap-2">
              Intelligent Profile Manager
            </h2>
            <p className="text-xs text-slate-400">Context-Aware Real-time Applet Profile Dispatcher</p>
          </div>
        </div>
      </div>

      <div className="p-6 grid grid-cols-1 md:grid-cols-12 gap-6">
        {/* Profiles Selector Panel */}
        <div className="md:col-span-5 space-y-4">
          <span className="block text-[10px] font-mono font-bold text-slate-500 uppercase">CHOOSE ACTIVE PROFILE</span>
          <div className="space-y-2 max-h-[300px] overflow-y-auto pr-1">
            {profiles.map((p) => {
              const Selected = p.id === activeProfileId;
              return (
                <div
                  key={p.id}
                  onClick={() => onProfileSelect(p.id)}
                  className={`p-3.5 rounded-lg border cursor-pointer transition-all flex items-start gap-3 ${
                    Selected 
                      ? 'bg-gradient-to-r from-slate-900 to-indigo-950/20 border-indigo-500 text-slate-100 shadow-lg' 
                      : 'bg-slate-950/45 border-slate-850 text-slate-400 hover:text-slate-200 hover:bg-slate-950/60'
                  }`}
                >
                  <div className="mt-1 bg-slate-950 p-1.5 rounded-lg border border-slate-800">
                    <Target className={`w-4 h-4 ${Selected ? 'text-indigo-400 animate-spin-slow' : 'text-slate-500'}`} />
                  </div>
                  <div className="space-y-0.5 truncate flex-1">
                    <div className="text-xs font-bold text-slate-200">{p.name}</div>
                    <div className="text-[10px] font-mono text-slate-500 truncate">{p.packageName}</div>
                    <p className="text-[9px] text-slate-400 line-clamp-1 mt-1 leading-normal">{p.description}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Global calibration triggers */}
        <div className="md:col-span-7 flex flex-col justify-between">
          <div className="space-y-4">
            <div className="flex border-b border-slate-800">
              <button
                onClick={() => setActiveSubTab('parameters')}
                className={`py-2 px-4 text-xs font-semibold flex items-center gap-1.5 transition-colors border-b ${
                  activeSubTab === 'parameters' ? 'border-indigo-500 text-indigo-400' : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                <Sliders className="w-3.5 h-3.5" />
                Damping Parameters
              </button>
              <button
                onClick={() => setActiveSubTab('hardware')}
                className={`py-2 px-4 text-xs font-semibold flex items-center gap-1.5 transition-colors border-b ${
                  activeSubTab === 'hardware' ? 'border-indigo-500 text-indigo-400' : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                <HardDrive className="w-3.5 h-3.5" />
                Raw Kernel Handles
              </button>
            </div>

            {activeSubTab === 'parameters' ? (
              <div className="space-y-4 pt-1">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="flex justify-between text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <span>Gyro Integration Gain</span>
                      <span className={activeProfile.gyroSensitivity === 0 ? "text-rose-400 font-bold" : "text-indigo-400"}>
                        {activeProfile.gyroSensitivity === 0 ? "OFF (DISABLED)" : `${activeProfile.gyroSensitivity.toFixed(2)}x`}
                      </span>
                    </div>
                    <input
                      type="range"
                      min="0.0"
                      max="4.0"
                      step="0.05"
                      className="w-full accent-indigo-500"
                      value={activeProfile.gyroSensitivity}
                      onChange={(e) => updateProfileValue('gyroSensitivity', parseFloat(e.target.value))}
                    />
                    <span className="block text-[8px] text-slate-500 mt-0.5 leading-normal">Scalar coefficient multiplier mapped onto touch simulation canvas bounds.</span>
                  </div>

                  <div>
                    <div className="flex justify-between text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <span>Stick Deadzone Axis</span>
                      <span>{Math.round(activeProfile.deadzone * 100)}%</span>
                    </div>
                    <input
                      type="range"
                      min="0.02"
                      max="0.25"
                      step="0.01"
                      className="w-full accent-indigo-500"
                      value={activeProfile.deadzone}
                      onChange={(e) => updateProfileValue('deadzone', parseFloat(e.target.value))}
                    />
                    <span className="block text-[8px] text-slate-500 mt-0.5 leading-normal">Defines internal joystick dead-angles before virtual coordinate displacement triggers.</span>
                  </div>
                </div>

                <div>
                  <div className="flex justify-between text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                    <span>Exponential Jitter Filter Damping</span>
                    <span>{(activeProfile.smoothing * 100).toFixed(0)}%</span>
                  </div>
                  <input
                    type="range"
                    min="0.05"
                    max="0.95"
                    step="0.05"
                    className="w-full accent-indigo-500"
                    value={activeProfile.smoothing}
                    onChange={(e) => updateProfileValue('smoothing', parseFloat(e.target.value))}
                  />
                  <span className="block text-[8px] text-slate-500 mt-0.5 leading-normal">Exponential smoothing factor representing high frequency jitter attenuation performance. Higher keeps signals static.</span>
                </div>
              </div>
            ) : (
              <div className="space-y-3.5 pt-1 text-slate-350">
                <div className="p-3 bg-slate-950/60 rounded border border-slate-850 space-y-2">
                  <div className="flex items-center gap-1.5 text-[11px] font-bold text-slate-300">
                    <Cpu className="w-3.5 h-3.5 text-indigo-400" />
                    udev / Kernel Virtual Event injection parameters
                  </div>
                  <p className="text-[10px] text-slate-400 leading-normal text-justify">
                    Nexion uses raw memory address layouts inside Shizuku's Isolated boundaries. The mapped device listens strictly on the evdev channel to fetch controller triggers before passing offsets into the <code className="font-mono text-indigo-400 bg-slate-900 px-1 rounded">/dev/uinput</code> touch emulation stack.
                  </p>
                </div>

                <div className="p-2.5 bg-rose-950/20 border border-rose-900/30 text-[10px] text-rose-300 leading-normal flex items-start gap-2 rounded">
                  <AlertTriangle className="w-4 h-4 text-rose-500 shrink-0 mt-0.5" />
                  <span>Always ensure double touch profiles are disabled in your OS interface Settings, since double touch injection triggers could trigger hardware level anti-cloning flag systems.</span>
                </div>
              </div>
            )}
          </div>

          <div className="p-3.5 bg-slate-950 border border-slate-850 flex items-center justify-between text-xs text-slate-400 mt-6 rounded">
            <div>
              <span className="block text-[9px] text-slate-500 font-sans uppercase font-mono font-bold">Foreground Target Hooked</span>
              <span className="text-slate-300 font-bold font-mono">{activeProfile.packageName}</span>
            </div>
            <span className="text-[10px] bg-indigo-950/60 text-indigo-400 font-mono px-2 py-0.5 rounded border border-indigo-900">
              SYSTEM READY
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
