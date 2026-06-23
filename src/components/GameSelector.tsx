/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React, { useRef } from 'react';
import { GamepadProfile } from '../types';
import { Target, Settings, Sliders, Box, HardDrive, Cpu, AlertTriangle, Play, Flame, Plus, Trash2, Edit2, Check, X, ShieldAlert, Download, Upload } from 'lucide-react';

interface GameSelectorProps {
  profiles: GamepadProfile[];
  activeProfileId: string;
  onProfileSelect: (id: string) => void;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onCreateProfile: (profile: GamepadProfile) => void;
  onDeleteProfile: (id: string) => void;
  onLogMessage: (msg: string) => void;
}

export default function GameSelector({ profiles, activeProfileId, onProfileSelect, onUpdateProfile, onCreateProfile, onDeleteProfile, onLogMessage }: GameSelectorProps) {
  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];
  const [activeSubTab, setActiveSubTab] = React.useState<'parameters' | 'hardware'>('parameters');
  const [isEditingMeta, setIsEditingMeta] = React.useState(false);
  const [editMetaValues, setEditMetaValues] = React.useState({ name: '', packageName: '', description: '' });
  const fileInputRef = useRef<HTMLInputElement>(null);

  const updateProfileValue = (key: keyof GamepadProfile, value: any) => {
    const updated = { ...activeProfile, [key]: value };
    onUpdateProfile(updated);
    if (!isEditingMeta) {
      onLogMessage(`Profile Engine: Modifying [${activeProfile.name}] attribute -> ${key} = ${value}`);
    }
  };

  const handleCreateNew = () => {
    const newId = `custom_${Date.now()}`;
    const newProfile: GamepadProfile = {
      id: newId,
      name: 'New Custom Profile',
      packageName: 'com.example.app',
      description: 'A new user-defined layout profile.',
      gyroSensitivity: 1.0,
      deadzone: 0.1,
      smoothing: 0.2,
      isCustom: true,
      buttons: [],
      antiBanEnabled: false
    };
    onCreateProfile(newProfile);
    onLogMessage(`Profile Engine: Created new blank profile [${newId}]`);
  };

  const handleDelete = () => {
    if (confirm("Are you sure you want to delete this profile?")) {
      onDeleteProfile(activeProfile.id);
      onLogMessage(`Profile Engine: Deleted profile [${activeProfile.name}]`);
    }
  };

  const handleExportProfile = () => {
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(activeProfile, null, 2));
    const downloadAnchorNode = document.createElement('a');
    downloadAnchorNode.setAttribute("href",     dataStr);
    downloadAnchorNode.setAttribute("download", `${activeProfile.name.replace(/\s+/g, '_')}_profile.json`);
    document.body.appendChild(downloadAnchorNode);
    downloadAnchorNode.click();
    downloadAnchorNode.remove();
    onLogMessage(`Profile Engine: Exported profile [${activeProfile.name}] to JSON`);
  };

  const handleImportProfile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const importedData = JSON.parse(e.target?.result as string);
        if (importedData && typeof importedData === 'object' && importedData.id && typeof importedData.name === 'string') {
          // generate a new id to prevent conflicts
          const importedProfile: GamepadProfile = {
            ...importedData,
            id: `custom_imported_${Date.now()}`,
            name: `${importedData.name} (Imported)`,
            isCustom: true
          };
          onCreateProfile(importedProfile);
          onLogMessage(`Profile Engine: Successfully imported profile [${importedProfile.name}]`);
        } else {
          onLogMessage("Profile Engine: Failed. Invalid profile JSON format.");
        }
      } catch (err) {
         onLogMessage("Profile Engine: Failed to parse JSON file.");
      }
    };
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const startEditingMetaData = () => {
    setEditMetaValues({ 
      name: activeProfile.name, 
      packageName: activeProfile.packageName, 
      description: activeProfile.description 
    });
    setIsEditingMeta(true);
  };

  const saveMetaData = () => {
    const updated = { ...activeProfile, ...editMetaValues };
    onUpdateProfile(updated);
    setIsEditingMeta(false);
    onLogMessage(`Profile Engine: Updated metadata for [${updated.name}]`);
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
          <div className="flex items-center justify-between">
             <span className="block text-[10px] font-mono font-bold text-slate-500 uppercase">CHOOSE ACTIVE PROFILE</span>
             <div className="flex items-center gap-1.5">
               <button
                 onClick={() => fileInputRef.current?.click()}
                 className="p-1 hover:bg-slate-800 text-slate-400 hover:text-slate-200 rounded"
                 title="Import JSON Profile"
               >
                 <Upload className="w-3.5 h-3.5" />
               </button>
               <input
                 type="file"
                 ref={fileInputRef}
                 className="hidden"
                 accept=".json"
                 onChange={handleImportProfile}
               />
               <button
                 onClick={handleExportProfile}
                 className="p-1 hover:bg-slate-800 text-slate-400 hover:text-slate-200 rounded mr-1"
                 title="Export Active Profile as JSON"
               >
                 <Download className="w-3.5 h-3.5" />
               </button>
               <button
                 onClick={handleCreateNew}
                 className="flex items-center gap-1.5 px-2.5 py-1 bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-400 border border-indigo-500/30 rounded text-[10px] font-bold uppercase transition-colors"
               >
                 <Plus className="w-3 h-3" />
                 New Profile
               </button>
             </div>
          </div>
          <div className="space-y-2 max-h-[400px] overflow-y-auto pr-1">
            {profiles.map((p) => {
              const Selected = p.id === activeProfileId;
              return (
                <div
                  key={p.id}
                  onClick={() => !Selected && onProfileSelect(p.id)}
                  className={`p-3.5 rounded-lg border cursor-pointer transition-all flex flex-col gap-3 ${
                    Selected 
                      ? 'bg-gradient-to-r from-slate-900 to-indigo-950/20 border-indigo-500 text-slate-100 shadow-lg cursor-default' 
                      : 'bg-slate-950/45 border-slate-850 text-slate-400 hover:text-slate-200 hover:bg-slate-950/60'
                  }`}
                >
                  <div className="flex items-start gap-3 w-full">
                    <div className="mt-1 bg-slate-950 p-1.5 rounded-lg border border-slate-800">
                      <Target className={`w-4 h-4 ${Selected ? 'text-indigo-400 animate-spin-slow' : 'text-slate-500'}`} />
                    </div>
                    {Selected && isEditingMeta ? (
                      <div className="flex-1 space-y-2">
                        <input 
                          type="text" 
                          value={editMetaValues.name} 
                          onChange={(e) => setEditMetaValues({...editMetaValues, name: e.target.value})} 
                          className="w-full bg-slate-950 border border-slate-800 rounded px-2 py-1 text-xs text-slate-200 outline-none focus:border-indigo-500"
                          placeholder="Profile Name"
                        />
                        <input 
                          type="text" 
                          value={editMetaValues.packageName} 
                          onChange={(e) => setEditMetaValues({...editMetaValues, packageName: e.target.value})} 
                          className="w-full bg-slate-950 border border-slate-800 rounded px-2 py-1 text-[10px] font-mono text-slate-300 outline-none focus:border-indigo-500"
                          placeholder="com.package.name"
                        />
                        <textarea
                          value={editMetaValues.description}
                          onChange={(e) => setEditMetaValues({...editMetaValues, description: e.target.value})}
                          className="w-full bg-slate-950 border border-slate-800 rounded px-2 py-1 text-[10px] text-slate-400 outline-none focus:border-indigo-500 min-h-[40px] resize-none"
                          placeholder="Description"
                        />
                        <div className="flex gap-2 justify-end">
                          <button onClick={() => setIsEditingMeta(false)} className="px-2 py-1 text-[9px] font-bold uppercase rounded bg-slate-800 text-slate-400 hover:text-slate-200">Cancel</button>
                          <button onClick={(e) => { e.stopPropagation(); saveMetaData(); }} className="px-2 py-1 text-[9px] font-bold uppercase rounded bg-indigo-500 text-white flex gap-1 items-center hover:bg-indigo-400"><Check className="w-3 h-3" /> Save</button>
                        </div>
                      </div>
                    ) : (
                      <div className="space-y-0.5 truncate flex-1">
                        <div className="text-xs font-bold text-slate-200">{p.name}</div>
                        <div className="text-[10px] font-mono text-slate-500 truncate">{p.packageName}</div>
                        <p className="text-[9px] text-slate-400 line-clamp-1 mt-1 leading-normal">{p.description}</p>
                      </div>
                    )}
                    {Selected && !isEditingMeta && (
                      <div className="flex flex-col gap-1 items-end shrink-0">
                        <button onClick={(e) => { e.stopPropagation(); startEditingMetaData(); }} className="p-1.5 text-slate-400 hover:text-indigo-400 hover:bg-slate-900 rounded"><Edit2 className="w-3.5 h-3.5" /></button>
                        {p.isCustom && (
                          <button onClick={(e) => { e.stopPropagation(); handleDelete(); }} className="p-1.5 text-slate-500 hover:text-rose-400 hover:bg-rose-950/30 rounded"><Trash2 className="w-3.5 h-3.5" /></button>
                        )}
                      </div>
                    )}
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

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="flex justify-between items-start text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <span>Exponential Smoothing Damping</span>
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
                    <span className="block text-[8px] text-slate-500 mt-0.5 leading-normal">High frequency jitter attenuation performance. Higher keeps signals static.</span>
                  </div>

                    <div className="flex flex-col">
                    <div className="flex flex-col gap-1 text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                      <div className="flex justify-between">
                        <span className="flex items-center gap-1"><ShieldAlert className="w-3.5 h-3.5 text-emerald-400"/> Anti-Ban Touch Randomizer</span>
                        <button 
                          onClick={() => updateProfileValue('antiBanEnabled', !activeProfile.antiBanEnabled)}
                          className={`w-8 h-4 rounded-full relative transition-colors ${activeProfile.antiBanEnabled ? 'bg-emerald-500' : 'bg-slate-700'}`}
                        >
                          <span className={`absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform ${activeProfile.antiBanEnabled ? 'translate-x-4' : 'translate-x-0'}`}></span>
                        </button>
                      </div>
                    </div>
                    <span className="block text-[8px] text-slate-500 mt-1 leading-normal text-start">Humanizes synthetic touch events by slightly randomizing coordinate impacts across a ~4px radial radius. Strongly recommended for competitive shooters.</span>
                  </div>

                  {activeProfile.antiBanEnabled && (
                    <div className="space-y-3 mt-3 p-2 bg-slate-900 rounded border border-slate-700">
                      <div>
                        <div className="flex justify-between items-start text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                          <span>Input Source (Evades Check)</span>
                        </div>
                        <select
                          className="w-full bg-slate-800 text-white text-[11px] p-1.5 rounded"
                          value={activeProfile.buttons?.[0]?.inputSource || 'MOUSE'}
                          onChange={(e) => {
                            // Update first button as prototype or global
                            const updated = [...activeProfile.buttons];
                            updated.forEach(b => b.inputSource = e.target.value as any);
                            updateProfileValue('buttons', updated);
                          }}
                        >
                          <option value="TOUCHSCREEN">TOUCHSCREEN (High Risk)</option>
                          <option value="MOUSE">MOUSE (Recommended)</option>
                          <option value="STYLUS">STYLUS</option>
                          <option value="GAMEPAD">GAMEPAD</option>
                        </select>
                      </div>
                      <div>
                        <div className="flex justify-between items-start text-[10px] text-slate-400 mb-1 uppercase font-semibold">
                          <span>Tool Type</span>
                        </div>
                        <select
                          className="w-full bg-slate-800 text-white text-[11px] p-1.5 rounded"
                          value={activeProfile.buttons?.[0]?.toolType || 'FINGER'}
                          onChange={(e) => {
                            const updated = [...activeProfile.buttons];
                            updated.forEach(b => b.toolType = e.target.value as any);
                            updateProfileValue('buttons', updated);
                          }}
                        >
                          <option value="FINGER">FINGER</option>
                          <option value="STYLUS">STYLUS</option>
                        </select>
                      </div>
                    </div>
                  )}
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
