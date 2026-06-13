/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import { 
  GamepadProfile, GamepadMacro, ShizukuState 
} from './types';
import { INITIAL_PROFILES, INITIAL_MACROS } from './mockData';
import ShizukuPanel from './components/ShizukuPanel';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import MacroEngine from './components/MacroEngine';
import GamepadTester from './components/GamepadTester';
import GameSelector from './components/GameSelector';
import { 
  Terminal, Shield, Settings, Activity, Compass, Cpu, HelpCircle, 
  ChevronRight, Sparkles, BookOpen, Layers
} from 'lucide-react';

export default function App() {
  const [shizukuState, setShizukuState] = React.useState<ShizukuState>({
    status: 'CONNECTED_SHIZUKU',
    daemonRunning: true,
    daemonVersion: 'v2.8.4-Nexion',
    logLines: [
      "[INFO] TouchDaemon v2.8.4-Nexion started of process 9815 (shelld)",
      "[INFO] Hooked into backend socket namespace: @gampad_mapper_ipc",
      "[INFO] Initializing uinput driver injection device...",
      "[SUCCESS] Allocated /dev/uinput: Touch virtual device descriptor created (10 touch slots)",
      "[INFO] Native raw reading listening on /dev/input/event1 (Vortex XP107 DualMode Gamepad)",
      "[INFO] Native raw reading listening on /dev/input/event5 (Vortex Gyroscopic Motion Sensor Unit)",
      "[INFO] Shizuku user process bound securely via AIDL ITouchDaemonControl",
      "[SUCCESS] Client listening loop operational at sub-8ms frequency",
      "[GYRO] Madgwick Sensor Fusion active. 250Hz sample acquisition running...",
      "[INFO] Default profile for Genshin Impact loaded successfully"
    ]
  });

  const [profiles, setProfiles] = React.useState<GamepadProfile[]>(INITIAL_PROFILES);
  const [activeProfileId, setActiveProfileId] = React.useState('genshin');
  const [selectedMainView, setSelectedMainView] = React.useState<'shizuku' | 'overlay' | 'profile' | 'macro' | 'tester'>('shizuku');

  // Query real simulation logs and stats from server
  const fetchStatus = async () => {
    try {
      const res = await fetch('/api/daemon/status');
      const data = await res.json();
      setShizukuState(data);
    } catch (err) {
      console.error('Failed to sync state from backend', err);
    }
  };

  const syncActiveProfileIdOnServer = async (id: string) => {
    try {
      await fetch('/api/profile/active', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileId: id })
      });
      fetchStatus();
    } catch (err) {
      console.error(err);
    }
  };

  React.useEffect(() => {
    fetchStatus();
    // Sync state every 3.5 seconds
    const interval = setInterval(fetchStatus, 3500);
    return () => clearInterval(interval);
  }, []);

  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];

  const handleUpdateProfile = (updatedProfile: GamepadProfile) => {
    setProfiles(prev => prev.map(p => p.id === updatedProfile.id ? updatedProfile : p));
  };

  const handleLogMessage = async (msg: string) => {
    try {
      await fetch('/api/daemon/log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg })
      });
      fetchStatus();
    } catch (err) {
      console.error(err);
    }
  };

  const handleProfileSelect = (id: string) => {
    setActiveProfileId(id);
    syncActiveProfileIdOnServer(id);
  };

  return (
    <div className="min-h-screen bg-[#060608] text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      {/* Visual background atmospheric elements */}
      <div className="absolute inset-x-0 top-0 h-[450px] bg-gradient-to-b from-indigo-950/15 via-transparent to-transparent pointer-events-none" />

      {/* Corporate Brand Header Section */}
      <header className="border-b border-slate-900/80 bg-slate-950/40 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex justify-between items-center">
          <div className="flex items-center gap-3">
            <div className="relative flex items-center justify-center w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-600 border border-indigo-400/20 shadow-lg shadow-indigo-600/10">
              <span className="font-mono text-lg font-bold tracking-tighter text-white">N</span>
              <div className="absolute -inset-1 rounded-xl bg-gradient-to-tr from-indigo-500 to-violet-500 opacity-20 blur-sm pointer-events-none" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-sm font-bold font-sans tracking-wide text-white uppercase sm:text-base">
                  Gamepad Mapper Mind
                </h1>
                <span className="text-[9px] font-mono leading-none font-bold bg-indigo-950/80 text-indigo-400 border border-indigo-900 px-1.5 py-0.5 rounded uppercase">
                  Nexion Core
                </span>
              </div>
              <p className="text-[10px] text-slate-400 tracking-wider">Low-Level Touch Injection & Gyro Fusion</p>
            </div>
          </div>

          {/* Quick HUD State indications */}
          <div className="hidden md:flex items-center gap-6 text-xs text-slate-400 font-mono">
            <div className="flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
              <span>Socket IPC: @gampad_mapper_ipc</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-violet-400 animate-pulse"></span>
              <span>Input Polling: 250Hz</span>
            </div>
          </div>
        </div>
      </header>

      {/* Main Container */}
      <main className="max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8 flex-1 flex flex-col gap-6 relative z-10">
        
        {/* Navigation Toolbar */}
        <div className="flex flex-wrap gap-2 border-b border-slate-900 pb-2">
          <button
            onClick={() => setSelectedMainView('shizuku')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'shizuku' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Shield className="w-3.5 h-3.5" />
            Orchestration Control
          </button>
          <button
            onClick={() => setSelectedMainView('profile')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'profile' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Cpu className="w-3.5 h-3.5" />
            Profile Manager
          </button>
          <button
            onClick={() => setSelectedMainView('overlay')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'overlay' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            WYSIWYG Overlay Canvas
          </button>
          <button
            onClick={() => setSelectedMainView('macro')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'macro' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Activity className="w-3.5 h-3.5" />
            Tactile Playback Macros
          </button>
          <button
            onClick={() => setSelectedMainView('tester')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'tester' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Compass className="w-3.5 h-3.5" />
            Sensor & Input Diagnostics
          </button>
        </div>

        {/* Dynamic Inner views representing our monorepo features */}
        <div className="flex-1 flex flex-col gap-6">
          {selectedMainView === 'shizuku' && (
            <ShizukuPanel 
              shizukuState={shizukuState} 
              setShizukuState={setShizukuState} 
              onLogMessage={handleLogMessage} 
            />
          )}

          {selectedMainView === 'profile' && (
            <GameSelector
              profiles={profiles}
              activeProfileId={activeProfileId}
              onProfileSelect={handleProfileSelect}
              onUpdateProfile={handleUpdateProfile}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'overlay' && (
            <OverlayWysiwyg
              activeProfile={activeProfile}
              onUpdateProfile={handleUpdateProfile}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'macro' && (
            <MacroEngine
              initialMacros={INITIAL_MACROS}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'tester' && (
            <GamepadTester
              onLogMessage={handleLogMessage}
            />
          )}
        </div>

        {/* Informative corporate tech section */}
        <section className="bg-slate-900/30 border border-slate-900/60 rounded-xl p-6 grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Shield className="w-4 h-4 text-indigo-400" />
              1. PHYSICAL TOUCH INJECTION
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Locks directly onto raw kernel buffers utilizing unique device descriptor aliases. Evades memory-scanners easily by varying coordinate distribution speeds.
            </p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Compass className="w-4 h-4 text-emerald-400" />
              2. SENSOR FUSION ENGINE
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Leverages high frequency Madgwick filters to translate gyroscopic coordinates into responsive multi-finger sweeps, matching competitive input lag standards (under 8ms).
            </p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-pink-400" />
              3. AIDL ISOLATION BOUNDS
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Interlocks safely with the Shizuku Binder pipeline, spinning the daemon process within background shelter boundaries so it safely survives foreground client destructions.
            </p>
          </div>
        </section>

      </main>

      {/* Corporate Technical Footer */}
      <footer className="border-t border-slate-900 py-6 bg-slate-950 mt-auto text-center text-[10px] font-mono text-slate-500">
        <div className="max-w-7xl mx-auto px-4 flex flex-col sm:flex-row justify-between items-center gap-3">
          <span>🎮 Gamepad Mapper Mind – Nexion Orchestrator Platform</span>
          <span className="text-indigo-400/80">Author Signature: @author NanoMind Explorer</span>
          <span>© 2026 NanoMind Systems Inc.</span>
        </div>
      </footer>
    </div>
  );
}
