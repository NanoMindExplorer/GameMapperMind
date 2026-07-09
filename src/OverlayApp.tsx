import React, { useState } from 'react';
import OverlayWysiwyg from './OverlayWysiwyg';
import { GamepadProfile } from '../types';
import { useInputInjector } from '../hooks/useInputInjector';
import TouchInjection from '../plugins/TouchInjection';

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

  // ==================== TOAST ====================
  const showToast = (type: ToastMessage['type'], text: string) => {
    setToastMessage({ type, text });
    setTimeout(() => setToastMessage(null), 3000);
    
    // Kirim juga ke parent jika ada
    if (onLogMessage) {
      onLogMessage(text);
    }
  };

  // ==================== OVERLAY CONTROL ====================
  const handleStartOverlay = async () => {
    try {
      const success = await startOverlay(activeProfile, 'canvas');
      if (success) {
        setOverlayActive(true);
        showToast('success', 'Overlay started successfully');
      } else {
        showToast('error', 'Failed to start overlay');
      }
    } catch (error: any) {
      showToast('error', `Overlay error: ${error.message || error}`);
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
      showToast('error', `Stop overlay error: ${error.message || error}`);
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
        // Start recording
        if (TouchInjection.startMacroRecording) {
          await TouchInjection.startMacroRecording(activeProfile.id);
        }
        setIsMacroRecording(true);
        showToast('info', 'Macro recording started');
      } else {
        // Stop recording
        if (TouchInjection.stopMacroRecording) {
          await TouchInjection.stopMacroRecording();
        }
        setIsMacroRecording(false);
        showToast('success', 'Macro recording stopped and saved');
      }
    } catch (error: any) {
      setIsMacroRecording(false);
      showToast('error', `Macro error: ${error.message || error}`);
    }
  };

  // ==================== RENDER ====================
  return (
    <div className="flex flex-col h-full bg-slate-950 text-slate-200">
      {/* Top Control Bar */}
      <div className="flex items-center justify-between px-4 py-3 bg-slate-900 border-b border-slate-800">
        <div className="flex items-center gap-3">
          <span className="font-semibold">Overlay Mode</span>
          {overlayActive && (
            <span className="px-2 py-0.5 text-xs bg-emerald-600 rounded">Active</span>
          )}
          {isMacroRecording && (
            <span className="px-2 py-0.5 text-xs bg-red-600 rounded animate-pulse">Recording Macro</span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleToggleMacroRecording}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
              isMacroRecording 
                ? 'bg-red-600 hover:bg-red-500' 
                : 'bg-pink-600 hover:bg-pink-500'
            }`}
          >
            {isMacroRecording ? 'Stop Macro Recording' : 'Record Macro'}
          </button>

          {!overlayActive ? (
            <button
              onClick={handleStartOverlay}
              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 rounded-lg text-sm font-medium"
            >
              Start Overlay
            </button>
          ) : (
            <button
              onClick={handleStopOverlay}
              className="px-4 py-2 bg-rose-600 hover:bg-rose-500 rounded-lg text-sm font-medium"
            >
              Stop Overlay
            </button>
          )}
        </div>
      </div>

      {/* Main WYSIWYG Area */}
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

      {/* Toast */}
      {toastMessage && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50">
          <div className={`px-5 py-2.5 rounded-full text-sm font-medium shadow-xl ${
            toastMessage.type === 'success' ? 'bg-emerald-600' :
            toastMessage.type === 'error' ? 'bg-red-600' : 'bg-slate-700'
          }`}>
            {toastMessage.text}
          </div>
        </div>
      )}
    </div>
  );
}
