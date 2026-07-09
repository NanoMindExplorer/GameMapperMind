import React, { useState, useEffect } from 'react';
import { GamepadProfile, GamepadMacro } from './types';
import { INITIAL_PROFILES, INITIAL_MACROS } from './defaults';
import ShizukuPanel from './components/ShizukuPanel';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import MacroEngine from './components/MacroEngine';
import GamepadTester from './components/GamepadTester';
import GameSelector from './components/GameSelector';
import CreditsPanel from './components/CreditsPanel';
import InstalledGamesPanel from './components/InstalledGamesPanel';
import { 
  Terminal, Shield, Settings, Activity, Compass, Cpu, HelpCircle, 
  ChevronRight, Sparkles, BookOpen, Layers, Bot, ShieldAlert, Heart, 
  AlertTriangle, Gamepad2 
} from 'lucide-react';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { ScreenOrientation } from '@capacitor/screen-orientation';
import { useShizuku } from './hooks/useShizuku';
import { useGamepadLoop } from './hooks/useGamepadLoop';
import { useInputInjector } from './hooks/useInputInjector';
import TouchInjection from './plugins/TouchInjection';

interface ToastMessage {
  type: 'success' | 'error' | 'info';
  text: string;
}

export default function App() {
  const { 
    checkShizukuStatus, 
    executeShizukuCommand, 
    injectInput, 
    stopDaemon, 
    startDaemon 
  } = useShizuku();

  const { 
    startOverlay, 
    stopOverlay, 
    overlayActive, 
    setOverlayActive 
  } = useInputInjector();

  // ==================== STATE ====================
  const [profiles, setProfiles] = useState<GamepadProfile[]>(INITIAL_PROFILES);
  const [macros, setMacros] = useState<GamepadMacro[]>(INITIAL_MACROS);
  const [activeProfileId, setActiveProfileId] = useState<string>('default');
  const [currentScene, setCurrentScene] = useState<string>('default');
  const [selectedMainView, setSelectedMainView] = useState<'shizuku' | 'profile' | 'overlay' | 'macro' | 'tester' | 'games' | 'credits'>('shizuku');

  const [shizukuState, setShizukuState] = useState<any>(null);
  const [activeKeys, setActiveKeys] = useState<string[]>([]);
  const [activeAxes, setActiveAxes] = useState({ lx: 0, ly: 0, rx: 0, ry: 0 });

  const [isMacroRecording, setIsMacroRecording] = useState(false);
  const [toastMessage, setToastMessage] = useState<ToastMessage | null>(null);
  const [recoveryDialogOpen, setRecoveryDialogOpen] = useState(false);
  const [recoveryFailedCount, setRecoveryFailedCount] = useState(0);

  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];

  // ==================== TOAST & FEEDBACK ====================
  const showToast = (type: ToastMessage['type'], text: string) => {
    setToastMessage({ type, text });
    setTimeout(() => setToastMessage(null), 3500);
  };

  // ==================== MACRO RECORDING (Phase 5) ====================
  const handleToggleMacroRecording = async () => {
    if (!activeProfile) {
      showToast('error', 'Pilih profile terlebih dahulu');
      return;
    }

    try {
      if (!isMacroRecording) {
        // Mulai recording
        setIsMacroRecording(true);
        showToast('info', 'Mulai merekam macro...');

        // Panggil ke native layer
        await TouchInjection.startMacroRecording?.(activeProfile.id);
      } else {
        // Stop recording
        setIsMacroRecording(false);
        showToast('success', 'Macro recording selesai');

        await TouchInjection.stopMacroRecording?.();
      }
    } catch (error: any) {
      setIsMacroRecording(false);
      showToast('error', `Macro error: ${error.message || error}`);
    }
  };

  // ==================== PROFILE & SCENE ====================
  const handleProfileSelect = (profileId: string) => {
    setActiveProfileId(profileId);
    showToast('success', `Profile changed to ${profileId}`);
  };

  const handleSceneChange = (scene: string) => {
    setCurrentScene(scene);
    showToast('info', `Scene changed to: ${scene}`);
    // TODO: Kirim ke native jika diperlukan
  };

  // ==================== SHIZUKU & OVERLAY ====================
  const handleStartOverlay = async () => {
    try {
      await startOverlay();
      setOverlayActive(true);
      showToast('success', 'Overlay started');
    } catch (error: any) {
      showToast('error', `Failed to start overlay: ${error.message}`);
    }
  };

  const handleStopOverlay = async () => {
    try {
      await stopOverlay();
      setOverlayActive(false);
      showToast('success', 'Overlay stopped');
    } catch (error: any) {
      showToast('error', `Failed to stop overlay: ${error.message}`);
    }
  };

  // ==================== ERROR RECOVERY ====================
  const handleRecovery = async () => {
    try {
      await TouchInjection.bindService?.();
      showToast('success', 'Service re-bound successfully');
      setRecoveryDialogOpen(false);
      setRecoveryFailedCount(0);
    } catch (error) {
      setRecoveryFailedCount(prev => prev + 1);
      showToast('error', 'Failed to re-bind service');
    }
  };

  // ==================== RENDER ====================
  return (
    <div className="min-h-screen bg-slate-950 text-slate-200 flex flex-col">
      {/* Header */}
      <header className="border-b border-slate-800 bg-slate-900 px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Gamepad2 className="w-6 h-6 text-emerald-400" />
          <div>
            <h1 className="font-bold text-xl tracking-tight">GameMapperMind</h1>
            <p className="text-[10px] text-slate-500 -mt-1">v2.4.0 • Phase 5</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {isMacroRecording && (
            <div className="flex items-center gap-2 bg-red-600/90 px-3 py-1 rounded-full text-sm">
              <div className="w-2 h-2 bg-white rounded-full animate-pulse" />
              Recording Macro
            </div>
          )}
          <button
            onClick={handleToggleMacroRecording}
            className={`px-4 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 transition-all ${
              isMacroRecording 
                ? 'bg-red-600 hover:bg-red-500' 
                : 'bg-pink-600 hover:bg-pink-500'
            }`}
          >
            {isMacroRecording ? 'Stop Recording' : 'Record Macro'}
          </button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <div className="w-56 border-r border-slate-800 bg-slate-900 p-3 flex flex-col gap-1">
          <button onClick={() => setSelectedMainView('shizuku')} className={`...`}>Shizuku</button>
          <button onClick={() => setSelectedMainView('profile')} className={`...`}>Profiles</button>
          <button onClick={() => setSelectedMainView('overlay')} className={`...`}>Mapping Editor</button>
          <button onClick={() => setSelectedMainView('macro')} className={`...`}>Macro Engine</button>
          <button onClick={() => setSelectedMainView('tester')} className={`...`}>Gamepad Tester</button>
          <button onClick={() => setSelectedMainView('games')} className={`...`}>Installed Games</button>
          <button onClick={() => setSelectedMainView('credits')} className={`...`}>Credits</button>
        </div>

        {/* Main Content */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {selectedMainView === 'shizuku' && (
            <ShizukuPanel 
              shizukuState={shizukuState} 
              setShizukuState={setShizukuState} 
              onLogMessage={showToast} 
            />
          )}

          {selectedMainView === 'profile' && (
            <GameSelector 
              profiles={profiles} 
              activeProfileId={activeProfileId} 
              onProfileSelect={handleProfileSelect}
              onUpdateProfile={(p) => {
                setProfiles(prev => prev.map(x => x.id === p.id ? p : x));
              }}
              onCreateProfile={(p) => setProfiles(prev => [...prev, p])}
              onDeleteProfile={(id) => setProfiles(prev => prev.filter(x => x.id !== id))}
              onLogMessage={showToast}
            />
          )}

          {selectedMainView === 'overlay' && activeProfile && (
            <OverlayWysiwyg 
              activeProfile={activeProfile} 
              onUpdateProfile={(updated) => {
                setProfiles(prev => prev.map(p => p.id === updated.id ? updated : p));
              }}
              onLogMessage={showToast}
              activeKeys={activeKeys}
              activeAxes={activeAxes}
            />
          )}

          {selectedMainView === 'macro' && (
            <MacroEngine 
              macros={macros} 
              onUpdateMacros={setMacros} 
              onLogMessage={showToast} 
            />
          )}

          {selectedMainView === 'tester' && (
            <GamepadTester onLogMessage={showToast} />
          )}

          {selectedMainView === 'games' && (
            <InstalledGamesPanel 
              onLogMessage={showToast} 
              profiles={profiles} 
              onProfileSelect={handleProfileSelect}
            />
          )}

          {selectedMainView === 'credits' && (
            <CreditsPanel onLogMessage={showToast} />
          )}
        </div>
      </div>

      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50">
          <div className={`px-6 py-3 rounded-full text-sm font-medium shadow-xl flex items-center gap-2 ${
            toastMessage.type === 'success' ? 'bg-emerald-600 text-white' :
            toastMessage.type === 'error' ? 'bg-red-600 text-white' :
            'bg-slate-700 text-slate-200'
          }`}>
            {toastMessage.text}
          </div>
        </div>
      )}
    </div>
  );
}
