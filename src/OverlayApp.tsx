import React, { useState } from 'react';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import { GamepadProfile } from './types';
import { useInputInjector } from './hooks/useInputInjector';
import TouchInjection from './plugins/TouchInjection';

interface ToastMessage {
  type: 'success' | 'error' | 'info';
  text: string;
}

interface OverlayAppProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (profile: GamepadProfile) => void;
  onLogMessage?: (msg: string) => void;
}

export default function OverlayApp({ activeProfile, onUpdateProfile, onLogMessage }: OverlayAppProps) {
  const { startOverlay, stopOverlay } = useInputInjector();

  const [overlayActive, setOverlayActive] = useState(false);
  const [isMacroRecording, setIsMacroRecording] = useState(false);
  const [toastMessage, setToastMessage] = useState<ToastMessage | null>(null);

  const showToast = (type: ToastMessage['type'], text: string) => {
    setToastMessage({ type, text });
    setTimeout(() => setToastMessage(null), 3200);
    if (onLogMessage) onLogMessage(text);
  };

  // ==================== OVERLAY ====================
  const handleStartOverlay = async () => {
    try {
      const success = await startOverlay(activeProfile, 'canvas');
      if (success) {
        setOverlayActive(true);
        showToast('success', 'Overlay started');
      } else {
        showToast('error', 'Failed to start overlay');
      }
    } catch (error: any) {
      showToast('error', `Overlay error: ${error?.message || error}`);
    }
  };

  const handleStopOverlay = async () => {
    try {
      const success = await stopOverlay();
      if (success) {
        setOverlayActive(false);
        showToast('success', 'Overlay stopped');
      }
    } catch (error: any) {
      showToast('error', `Stop overlay error: ${error?.message || error}`);
    }
  };

  // ==================== MACRO RECORDING ====================
  const handleToggleMacroRecording = async () => {
    if (!activeProfile) {
      showToast('error', 'No active profile selected');
      return;
    }

    try {
      if (!isMacroRecording) {
        if ((TouchInjection as any).startMacroRecording) {
          await (TouchInjection as any).startMacroRecording(activeProfile.id);
        }
        setIsMacroRecording(true);
        showToast('info', 'Macro recording started');
      } else {
        if ((TouchInjection as any).stopMacroRecording) {
          await (TouchInjection as any).stopMacroRecording();
        }
        setIsMacroRecording(false);
        showToast('success', 'Macro recording stopped');
      }
    } catch (error: any) {
      setIsMacroRecording(false);
      showToast('error', `Macro error: ${error?.message || error}`);
    }
  };

  return (
    <div className="flex flex-col h-full bg-slate-950 text-slate-200">
      {/* Top Control Bar */}
      <div className="flex items-center justify-between px-4 py-3 bg-slate-900 border-b border-slate-800">
        <div className="flex items-center gap-3">
          <span className="font-semibold">Overlay Mode</span>
          {overlayActive && <span className="px-2.5 py-0.5 text-xs bg-emerald-600 rounded-full">Active</span>}
          {isMacroRecording && <span className="px-2.5 py-0.5 text-xs bg-red-600 rounded-full animate-pulse">Recording Macro</span>}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleToggleMacroRecording}
            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${isMacroRecording ? 'bg-red-600 hover:bg-red-500' : 'bg-pink-600 hover:bg-pink-500'}`}
          >
            {isMacroRecording ? 'Stop Recording' : 'Record Macro'}
          </button>

          {!overlayActive ? (
            <button onClick={handleStartOverlay} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 rounded-lg text-sm font-semibold">
              Start Overlay
            </button>
          ) : (
            <button onClick={handleStopOverlay} className="px-4 py-2 bg-rose-600 hover:bg-rose-500 rounded-lg text-sm font-semibold">
              Stop Overlay
            </button>
          )}
        </div>
      </div>

      {/* Main WYSIWYG */}
      <div className="flex-1 overflow-hidden">
        <OverlayWysiwyg
          activeProfile={activeProfile}
          onUpdateProfile={onUpdateProfile}
          onLogMessage={(msg: string) => showToast('info', msg)}
          activeKeys={[]}
          activeAxes={{ lx: 0, ly: 0, rx: 0, ry: 0 }}
          isNativeOverlay={true}
        />
      </div>

      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50">
          <div className={`px-5 py-2.5 rounded-full text-sm font-medium shadow-xl ${
            toastMessage.type === 'success' ? 'bg-emerald-600 text-white' :
            toastMessage.type === 'error' ? 'bg-red-600 text-white' : 'bg-slate-700 text-slate-200'
          }`}>
            {toastMessage.text}
          </div>
        </div>
      )}
    </div>
  );
}