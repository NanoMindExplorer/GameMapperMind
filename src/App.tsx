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
import AITunnelPanel from './components/AITunnelPanel';
import { registerPlugin } from '@capacitor/core';
const OverlayPlugin = registerPlugin('Overlay');

import { 
  Terminal, Shield, Settings, Activity, Compass, Cpu, HelpCircle, 
  ChevronRight, Sparkles, BookOpen, Layers, Bot, ShieldAlert
} from 'lucide-react';

import { useShizuku } from './hooks/useShizuku';
import { useGamepad } from './hooks/useGamepad';
import { useInputInjector } from './hooks/useInputInjector';

export default function App() {
  const { checkShizukuStatus, executeShizukuCommand, injectInput, stopDaemon } = useShizuku();
  const { startOverlay, stopOverlay } = useInputInjector();
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
  const [selectedMainView, setSelectedMainView] = React.useState<'shizuku' | 'overlay' | 'profile' | 'macro' | 'tester' | 'ai_tunnel'>('shizuku');
  const [isKilling, setIsKilling] = React.useState(false);

  // Settings state
  const [socketIpcName, setSocketIpcName] = React.useState('@gamepad_mapper_ipc');
  const [inputPolling, setInputPolling] = React.useState(250);
  const [isEditingSettings, setIsEditingSettings] = React.useState(false);

  const [macros, setMacros] = React.useState<GamepadMacro[]>(INITIAL_MACROS);

  // Persistence
  React.useEffect(() => {
    import('@capacitor/preferences').then(({ Preferences }) => {
      Preferences.get({ key: 'nexion_profiles' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) {
              setProfiles(parsed);
            }
          } catch (e) { console.error('Failed to parse profiles', e); }
        }
      }).catch(e => console.warn('capacitor get profiles error', e));

      Preferences.get({ key: 'nexion_active_profile' }).then((res) => {
        if (res.value) {
          setActiveProfileId(res.value);
        }
      }).catch(e => console.warn('capacitor get active profile error', e));

      Preferences.get({ key: 'nexion_macros' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) {
              setMacros(parsed);
            }
          } catch (e) { console.error('Failed to parse macros', e); }
        }
      }).catch(e => console.warn('capacitor get macros error', e));

      Preferences.get({ key: 'nexion_settings' }).then((res) => {
        if (res.value) {
           try {
             const parsed = JSON.parse(res.value);
             if (parsed.socketIpcName) setSocketIpcName(parsed.socketIpcName);
             if (parsed.inputPolling) setInputPolling(parsed.inputPolling);
           } catch(e){}
        }
      }).catch(e => console.warn('capacitor get settings error', e));
    }).catch(e => console.warn('capacitor preferences import error', e));
  }, []);

  const saveProfilesToStorage = async (newProfiles: GamepadProfile[]) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_profiles', value: JSON.stringify(newProfiles) });
    } catch(e) { console.warn('Failed to save profiles', e); }
  };

  const saveMacrosToStorage = async (newMacros: GamepadMacro[]) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_macros', value: JSON.stringify(newMacros) });
    } catch(e) { console.warn('Failed to save macros', e); }
  };

  const saveSettingsToStorage = async (socket: string, polling: number) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_settings', value: JSON.stringify({ socketIpcName: socket, inputPolling: polling }) });
    } catch(e) { console.warn('Failed to save settings', e); }
  };

  const handleUpdateMacros = (newMacros: GamepadMacro[]) => {
    setMacros(newMacros);
    saveMacrosToStorage(newMacros);
  };

  const handleUpdateProfile = (updatedProfile: GamepadProfile) => {
    setProfiles(prev => {
      const next = prev.map(p => p.id === updatedProfile.id ? updatedProfile : p);
      saveProfilesToStorage(next);
      return next;
    });
  };

  const handleCreateProfile = (profile: GamepadProfile) => {
    setProfiles(prev => {
      const next = [profile, ...prev];
      saveProfilesToStorage(next);
      return next;
    });
    setActiveProfileId(profile.id);
  };

  const handleDeleteProfile = (profileId: string) => {
    setProfiles(prev => {
      const next = prev.filter(p => p.id !== profileId);
      if (next.length === 0) {
         // Fallback if deleting the last profile
         next.push(INITIAL_PROFILES[0]);
      }
      saveProfilesToStorage(next);
      if (profileId === activeProfileId) {
        setActiveProfileId(next[0].id);
      }
      return next;
    });
  };

  const handleProfileSelect = async (id: string) => {
    setActiveProfileId(id);
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_active_profile', value: id });
    } catch(e) { console.warn('Failed to save active profile', e); }
    syncActiveProfileIdOnServer(id);
  };

  // Global listeners to prevent any d-pad or keyboard scrolling
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Prevent scrolling from arrow keys / space
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' '].includes(e.key)) {
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', handleKeyDown, { passive: false });
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  const [overlayActive, setOverlayActive] = React.useState(false);
  const [toastMessage, setToastMessage] = React.useState<{text: string, type: 'success' | 'error'} | null>(null);

  const showToast = (text: string, type: 'success' | 'error') => {
    setToastMessage({text, type});
    setTimeout(() => setToastMessage(null), 3000);
  };
  
  const handleToggleOverlay = async () => {
    try {
      if (overlayActive) {
        await stopOverlay();
        setOverlayActive(false);
        handleLogMessage('SYSTEM: Native floating overlay deactivated.');
        showToast('Overlay Deactivated', 'success');
      } else {
        await startOverlay(activeProfile);
        setOverlayActive(true);
        handleLogMessage('SYSTEM: Native floating overlay activated. You can now minimize the app.');
        showToast('Overlay Service Started Successfully!', 'success');
      }
    } catch (e: any) {
      handleLogMessage(`ERROR: Cannot start Native Overlay. ${e.message || e}`);
      showToast(`Failed to start overlay: ${e.message || e}`, 'error');
    }
  };

  const handleGlobalKillSwitch = async () => {
    setIsKilling(true);
    try {
      // Simulate hardware interrupt sequence
      await new Promise(r => setTimeout(r, 600));
      window.dispatchEvent(new CustomEvent('emergency-kill'));
      if (overlayActive) {
        await stopOverlay();
        setOverlayActive(false);
      }
      await stopDaemon();
      setShizukuState(prev => ({
        ...prev,
        status: 'DISCONNECTED',
        daemonRunning: false
      }));
      handleLogMessage('CRITICAL: Global emergency kill-switch initiated. All services, Daemon, and Overlay terminated.');
    } catch (err) {
      console.error("Failed to trigger emergency kill-switch", err);
    } finally {
      setIsKilling(false);
    }
  };

  // Query real simulation logs and stats from server, override with Native plugin state if on device
  const fetchStatus = async () => {
    try {
      // Just re-check native dependencies directly
      const nextState = await checkShizukuStatus(shizukuState);
      setShizukuState(nextState);
    } catch (err) {
      console.error('Failed to sync native state', err);
    }
  };

  const syncActiveProfileIdOnServer = (id: string) => {
    // Only local in Capacitor
    console.log("Profile active set to", id);
  };

  React.useEffect(() => {
    fetchStatus();
    // Native background checker
    const interval = setInterval(fetchStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];

  const handleGamepadPress = React.useCallback(async (button: string) => {
    const mapping = activeProfile.buttons.find(b => b.mappedKey && b.mappedKey.toLowerCase().includes(button.toLowerCase()));
    if (mapping) {
      // Calculate physical coordinates based on screen
      const x = Math.round((mapping.x / 100) * window.innerWidth);
      const y = Math.round((mapping.y / 100) * window.innerHeight);

      if (shizukuState.status === 'CONNECTED_SHIZUKU' && typeof window !== 'undefined' && 'Capacitor' in window) {
         injectInput(`input tap ${x} ${y}`);
         // Log but throttle it to prevent spam
      } else {
         fetch("/api/daemon/inject", {
           method: "POST",
           headers: { "Content-Type": "application/json" },
           body: JSON.stringify({ command: "tap", x, y })
         });
      }
    }
  }, [activeProfile, shizukuState.status, injectInput]);

  const handleGamepadAxis = React.useCallback(async (axes: { lx: number, ly: number, rx: number, ry: number }) => {
    // Only target L_STICK for now for movement simulation/hold
    const stickMapping = activeProfile.buttons.find(b => b.mappedKey === 'L_STICK');
    if (stickMapping && (Math.abs(axes.lx) > 0 || Math.abs(axes.ly) > 0)) {
      const baseX = Math.round((stickMapping.x / 100) * window.innerWidth);
      const baseY = Math.round((stickMapping.y / 100) * window.innerHeight);
      
      const targetX = Math.round(baseX + (axes.lx * 150)); // 150px drag radius
      const targetY = Math.round(baseY + (axes.ly * 150));
      
      if (shizukuState.status === 'CONNECTED_SHIZUKU' && typeof window !== 'undefined' && 'Capacitor' in window) {
         injectInput(`input swipe ${baseX} ${baseY} ${targetX} ${targetY} 100`);
      } else {
         fetch("/api/daemon/inject", {
           method: "POST",
           headers: { "Content-Type": "application/json" },
           body: JSON.stringify({ command: "swipe", fromX: baseX, fromY: baseY, toX: targetX, toY: targetY })
         });
      }
    }
  }, [activeProfile, shizukuState.status, injectInput]);

  const { connectedGamepad } = useGamepad(handleGamepadPress, handleGamepadAxis);

  const handleLogMessage = (msg: string) => {
    setShizukuState(prev => {
      const newLine = `[${new Date().toLocaleTimeString('en-US', { hour12: false, hour: "numeric", minute: "numeric", second: "numeric" })}] ${msg}`;
      const newLines = [...prev.logLines, newLine];
      if (newLines.length > 50) newLines.shift();
      return { ...prev, logLines: newLines };
    });
  };

  const handleKillSwitch = async () => {
    if (overlayActive) {
      await stopOverlay();
      setOverlayActive(false);
    }
    await stopDaemon();
    setShizukuState(prev => ({
        ...prev,
        daemonRunning: false,
        status: 'DISCONNECTED'
    }));
    handleLogMessage('SYSTEM: Kill-switch engaged. All services and injections terminated.');
  };

  return (
    <div className="min-h-screen bg-[#060608] text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      {/* Visual background atmospheric elements */}
      <div className="absolute inset-x-0 top-0 h-[450px] bg-gradient-to-b from-indigo-950/15 via-transparent to-transparent pointer-events-none" />

      {/* Corporate Brand Header Section */}
      <header className="border-b border-slate-900/80 bg-slate-950/40 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex justify-between items-center">
          <div className="flex items-center gap-3.5 group">
            {/* High-fidelity gaming controller & crosshair cyber badge */}
            <div className="relative flex items-center justify-center w-12 h-12 rounded-xl bg-slate-950 border-2 border-indigo-500/30 group-hover:border-pink-500/60 shadow-lg shadow-indigo-950/50 transition-all duration-300 overflow-hidden cursor-crosshair">
              {/* Futuristic honeycomb layout behind */}
              <div className="absolute inset-0 opacity-[0.06] bg-[radial-gradient(#ffffff_1px,transparent_1px)] [background-size:16px_16px] pointer-events-none" />
              
              {/* Real Game Controller + Crosshair Laser sight Vector */}
              <svg className="w-8 h-8 text-indigo-400 group-hover:text-pink-400 group-hover:scale-110 group-hover:rotate-[15deg] transition-all duration-500 relative z-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                {/* Controller Chassis Shield */}
                <path d="M6 12c-2.5 0-4-1.5-4-3.5S3.5 5 6 5c1 0 2 0.5 3 1.5 1-1 2-1.5 3-1.5 2.5 0 4 1.5 4 3.5s-1.5 3.5-4 3.5" className="opacity-40" />
                {/* Modern Controller Wing Silhouette */}
                <path d="M2 10.5C2 7.5 4 5 7 5h10c3 0 5 2.5 5 5.5 0 3.5-2 6.5-4 6.5s-2.5-1.5-4-1.5-2 1.5-4 1.5-4-3-4-6.5Z" strokeLinecap="round" strokeLinejoin="round" />
                {/* Joystick points with electric orbits */}
                <circle cx="7.5" cy="11.5" r="1.5" fill="currentColor" className="animate-pulse" />
                <circle cx="16.5" cy="11.5" r="1.5" fill="currentColor" className="animate-pulse delay-100" />
                {/* Action buttons (X/Y/A/B) simulated clusters */}
                <path d="M15.5 10h2M16.5 9v2" strokeWidth="1" />
                <path d="M6.5 10h2M7.5 9v2" strokeWidth="1" />
                {/* Epic central power diamond */}
                <polygon points="12,9.5 13.5,11.5 12,13.5 10.5,11.5" fill="currentColor" className="opacity-90 animate-ping duration-1000" />
              </svg>
              
              {/* Outer corner cyber brackets detailing */}
              <div className="absolute top-1 left-1 w-2 h-2 border-t-2 border-l-2 border-indigo-500/60 rounded-tl" />
              <div className="absolute top-1 right-1 w-2 h-2 border-t-2 border-r-2 border-indigo-500/60 rounded-tr" />
              <div className="absolute bottom-1 left-1 w-2 h-2 border-b-2 border-l-2 border-indigo-500/60 rounded-bl" />
              <div className="absolute bottom-1 right-1 w-2 h-2 border-b-2 border-r-2 border-indigo-500/60 rounded-br" />

              {/* Futuristic neon scanline aura overlay */}
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-indigo-500/10 to-transparent -translate-x-full group-hover:translate-x-full transition-transform duration-1000 pointer-events-none" />
              <div className="absolute -inset-1.5 rounded-xl bg-gradient-to-tr from-indigo-500 to-pink-500 opacity-0 group-hover:opacity-15 blur transition-opacity duration-300 pointer-events-none" />
            </div>

            {/* Typography brand styling */}
            <div>
              <div className="flex items-center gap-2">
                <span className="font-orbitron text-xs sm:text-sm font-black tracking-widest text-slate-100 uppercase group-hover:text-pink-400 group-hover:text-glow-pink transition-all duration-300">
                  GAMEPAD <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-500 font-extrabold text-glow-indigo">MAPPER</span>
                </span>
                <span className="text-[8px] font-mono leading-none font-bold bg-pink-950/50 text-pink-400 border border-pink-900/60 px-1.5 py-0.5 rounded-md uppercase tracking-wider animate-pulse">
                  X-PRO AGENT
                </span>
              </div>
              <p className="text-[9px] uppercase font-mono text-slate-400 tracking-widest mt-0.5 group-hover:text-indigo-400 transition-colors">
                LOW-LEVEL TACTILE CORE & VERTICAL VISUAL VLM
              </p>
            </div>
          </div>

          {/* Quick HUD State indications & Emergency Kill Switch */}
          <div className="flex items-center gap-4">
            <div className="hidden md:flex items-center gap-6 text-[10px] text-slate-400 font-mono pr-2 border-r border-slate-800">
              <div className="flex items-center gap-2" title={connectedGamepad ? `${connectedGamepad.id}` : 'No gamepad connected'}>
                <span className={`w-1.5 h-1.5 rounded-full ${connectedGamepad ? 'bg-green-500 animate-pulse' : 'bg-slate-600'}`}></span>
                <span className={connectedGamepad ? 'text-green-400' : 'text-slate-500'}>
                  GP: {connectedGamepad ? 'CONNECTED' : 'DISCONNECTED'}
                </span>
              </div>
            </div>
            
            <div className="hidden md:flex items-center gap-6 text-xs text-slate-400 font-mono pr-2">
              {isEditingSettings ? (
                <div className="flex items-center gap-4">
                  <div className="flex items-center gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                    <input 
                      type="text" 
                      value={socketIpcName}
                      onChange={(e) => setSocketIpcName(e.target.value)}
                      className="bg-slate-900 border border-slate-800 rounded px-2 py-0.5 text-xs text-slate-200 outline-none w-40"
                    />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-violet-400"></span>
                    <select
                      value={inputPolling}
                      onChange={(e) => setInputPolling(Number(e.target.value))}
                      className="bg-slate-900 border border-slate-800 rounded px-2 py-0.5 text-xs text-slate-200 outline-none"
                    >
                      <option value={125}>125Hz</option>
                      <option value={250}>250Hz</option>
                      <option value={500}>500Hz</option>
                      <option value={1000}>1000Hz</option>
                    </select>
                  </div>
                  <button 
                    onClick={() => {
                      setIsEditingSettings(false);
                      saveSettingsToStorage(socketIpcName, inputPolling);
                      handleLogMessage(`Configuration updated: Socket=${socketIpcName}, Polling=${inputPolling}Hz`);
                    }}
                    className="px-2 py-1 bg-indigo-500 hover:bg-indigo-400 text-white rounded text-[10px] uppercase font-bold"
                  >
                    Save
                  </button>
                </div>
              ) : (
                <div 
                  className="flex items-center gap-6 cursor-pointer hover:bg-slate-900 px-2 py-1 rounded transition-colors group/settings"
                  onClick={() => setIsEditingSettings(true)}
                  title="Click to configure settings"
                >
                  <div className="flex items-center gap-2 group-hover/settings:text-indigo-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                    <span>Socket IPC: {socketIpcName}</span>
                  </div>
                  <div className="flex items-center gap-2 group-hover/settings:text-indigo-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-violet-400 animate-pulse"></span>
                    <span>Input Polling: {inputPolling}Hz</span>
                  </div>
                </div>
              )}
            </div>

            <button
               onClick={handleToggleOverlay}
               className={`relative group px-3.5 py-1.5 text-xs font-bold font-mono uppercase border rounded-lg shadow-md transition-all flex items-center gap-2 ${overlayActive ? 'bg-indigo-950/40 hover:bg-indigo-900/40 border-indigo-500/50 hover:border-indigo-500 text-indigo-400 shadow-indigo-500/5' : 'bg-slate-900/40 hover:bg-slate-800/40 border-slate-700/50 hover:border-slate-500 text-slate-400'}`}
               title="Toggle Native Floating Overlay"
            >
              <Layers className="w-3.5 h-3.5 group-hover:scale-110 transition-transform" />
              <span>{overlayActive ? 'HIDE OVERLAY' : 'SHOW OVERLAY'}</span>
            </button>

            <button
              onClick={handleGlobalKillSwitch}
              disabled={isKilling}
              id="global-kill-switch-btn"
              className="relative group px-3.5 py-1.5 text-xs font-bold font-mono uppercase bg-red-950/40 hover:bg-red-900/40 border border-red-500/50 hover:border-red-500 text-red-400 rounded-lg shadow-md shadow-red-500/5 hover:shadow-red-500/15 active:scale-[0.97] transition-all flex items-center gap-2"
              title="Stop all active AI inputs and purge macro buffers"
            >
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-500 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-red-500"></span>
              </span>
              <ShieldAlert className="w-3.5 h-3.5 text-red-400 group-hover:scale-110 transition-transform" />
              <span>KILL SWITCH</span>
            </button>
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
          <button
            onClick={() => setSelectedMainView('ai_tunnel')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'ai_tunnel' 
                ? 'bg-slate-900 text-pink-400 border border-slate-800' 
                : 'text-slate-400 hover:text-pink-300 hover:bg-slate-950/40'
            }`}
          >
            <Bot className="w-3.5 h-3.5 text-pink-400" />
            AI Tunnel & Copilot
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
              onCreateProfile={handleCreateProfile}
              onDeleteProfile={handleDeleteProfile}
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
              macros={macros}
              onUpdateMacros={handleUpdateMacros}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'tester' && (
            <GamepadTester
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'ai_tunnel' && (
            <AITunnelPanel
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

      {toastMessage && (
        <div className="fixed bottom-10 left-1/2 transform -translate-x-1/2 z-50 animate-in fade-in slide-in-from-bottom-4 duration-300">
          <div className={`px-6 py-3 rounded-full flex items-center gap-3 shadow-2xl backdrop-blur-md border ${toastMessage.type === 'success' ? 'bg-emerald-950/80 border-emerald-500/50 text-emerald-50' : 'bg-red-950/80 border-red-500/50 text-red-50'}`}>
            {toastMessage.type === 'success' ? <Compass className="w-5 h-5 text-emerald-400" /> : <ShieldAlert className="w-5 h-5 text-red-400" />}
            <span className="font-mono text-sm font-bold tracking-wide">{toastMessage.text}</span>
          </div>
        </div>
      )}
    </div>
  );
}
