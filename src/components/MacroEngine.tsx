/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */
import React from 'react';
import { GamepadMacro, MacroStep } from '../types';
import TouchInjection from '../plugins/TouchInjection';
import { 
  Play, Square, Plus, Trash2, Edit3, Save, X, Clock, 
  Zap, AlertTriangle, RotateCcw, ListOrdered, ChevronDown, ChevronUp,
  Copy, Download, Upload, GripVertical, Pause, Timer, Layers, Wand2
} from 'lucide-react';

interface MacroEngineProps {
  macros: GamepadMacro[];
  onUpdateMacros: (macros: GamepadMacro[]) => void;
  onLogMessage: (msg: string) => void;
}

// FIX BUG-C02: Generate default empty macro jika tidak ada macro
const createEmptyMacro = (): GamepadMacro => ({
  id: `macro_${Date.now()}`,
  name: 'New Macro',
  description: '',
  steps: [],
  loopCount: 1,
  loopDelay: 100,
  isEnabled: true,
  createdAt: Date.now(),
  updatedAt: Date.now(),
});

export default function MacroEngine({ macros, onUpdateMacros, onLogMessage }: MacroEngineProps) {
  // FIX BUG-C02: Guard untuk selectedMacroId - fallback ke empty string jika tidak ada
  const [selectedMacroId, setSelectedMacroId] = React.useState<string>(
    macros.length > 0 ? macros[0].id : ''
  );
  
  const [isPlaying, setIsPlaying] = React.useState(false);
  const [isPaused, setIsPaused] = React.useState(false);
  const [currentStepIndex, setCurrentStepIndex] = React.useState(-1);
  const [currentLoop, setCurrentLoop] = React.useState(0);
  const [isEditing, setIsEditing] = React.useState(false);
  const [editingMacro, setEditingMacro] = React.useState<GamepadMacro | null>(null);
  const [showStepEditor, setShowStepEditor] = React.useState(false);
  const [editingStepIndex, setEditingStepIndex] = React.useState(-1);
  const [playbackSpeed, setPlaybackSpeed] = React.useState(1);
  const [showAdvanced, setShowAdvanced] = React.useState(false);
  const [jitterEnabled, setJitterEnabled] = React.useState(true);
  const [jitterAmount, setJitterAmount] = React.useState(5);
  
  const playbackRef = React.useRef<{
    isCancelled: boolean;
    isPaused: boolean;
  }>({ isCancelled: false, isPaused: false });

  // FIX BUG-C02: Auto-select first macro jika selectedMacroId tidak valid
  React.useEffect(() => {
    if (macros.length > 0 && !macros.find(m => m.id === selectedMacroId)) {
      setSelectedMacroId(macros[0].id);
    }
  }, [macros, selectedMacroId]);

  const selectedMacro = macros.find(m => m.id === selectedMacroId) || null;

  const handleCreateMacro = () => {
    const newMacro = createEmptyMacro();
    const updated = [newMacro, ...macros];
    onUpdateMacros(updated);
    setSelectedMacroId(newMacro.id);
    setIsEditing(true);
    setEditingMacro({ ...newMacro });
    onLogMessage(`[MACRO] Created new macro: "${newMacro.name}"`);
  };

  const handleDeleteMacro = (macroId: string) => {
    const macro = macros.find(m => m.id === macroId);
    if (!macro) return;
    
    if (window.confirm(`Are you sure you want to delete macro "${macro.name}"?`)) {
      const updated = macros.filter(m => m.id !== macroId);
      onUpdateMacros(updated);
      
      if (selectedMacroId === macroId) {
        setSelectedMacroId(updated.length > 0 ? updated[0].id : '');
      }
      onLogMessage(`[MACRO] Deleted macro: "${macro.name}"`);
    }
  };

  const handleStartEdit = () => {
    if (!selectedMacro) return;
    setEditingMacro({ ...selectedMacro });
    setIsEditing(true);
  };

  const handleCancelEdit = () => {
    setIsEditing(false);
    setEditingMacro(null);
    setShowStepEditor(false);
  };

  const handleSaveEdit = () => {
    if (!editingMacro) return;
    
    const updated = macros.map(m => 
      m.id === editingMacro.id 
        ? { ...editingMacro, updatedAt: Date.now() }
        : m
    );
    onUpdateMacros(updated);
    setIsEditing(false);
    setEditingMacro(null);
    onLogMessage(`[MACRO] Saved macro: "${editingMacro.name}" (${editingMacro.steps.length} steps)`);
  };

  const handleAddStep = () => {
    if (!editingMacro) return;
    
    const newStep: MacroStep = {
      id: `step_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      type: 'tap',
      x: 50,
      y: 50,
      duration: 100,
      delay: 200,
      description: '',
    };
    
    setEditingMacro({
      ...editingMacro,
      steps: [...editingMacro.steps, newStep],
    });
    setEditingStepIndex(editingMacro.steps.length);
    setShowStepEditor(true);
  };

  const handleDeleteStep = (stepIndex: number) => {
    if (!editingMacro) return;
    
    const updatedSteps = editingMacro.steps.filter((_, i) => i !== stepIndex);
    setEditingMacro({ ...editingMacro, steps: updatedSteps });
    setShowStepEditor(false);
    setEditingStepIndex(-1);
  };

  const handleUpdateStep = (stepIndex: number, updatedStep: MacroStep) => {
    if (!editingMacro) return;
    
    const updatedSteps = editingMacro.steps.map((s, i) => 
      i === stepIndex ? updatedStep : s
    );
    setEditingMacro({ ...editingMacro, steps: updatedSteps });
  };

  const handleDuplicateMacro = (macro: GamepadMacro) => {
    const duplicated: GamepadMacro = {
      ...macro,
      id: `macro_${Date.now()}`,
      name: `${macro.name} (Copy)`,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    const updated = [duplicated, ...macros];
    onUpdateMacros(updated);
    setSelectedMacroId(duplicated.id);
    onLogMessage(`[MACRO] Duplicated macro: "${macro.name}" → "${duplicated.name}"`);
  };

  const handleExportMacro = (macro: GamepadMacro) => {
    try {
      const data = JSON.stringify(macro, null, 2);
      const blob = new Blob([data], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${macro.name.replace(/[^a-z0-9]/gi, '_')}.macro.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      onLogMessage(`[MACRO] Exported macro: "${macro.name}"`);
    } catch (err) {
      onLogMessage(`[MACRO] Export failed: ${err}`);
    }
  };

  const handleImportMacro = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      
      const reader = new FileReader();
      reader.onload = (ev) => {
        try {
          const imported = JSON.parse(ev.target?.result as string);
          
          // FIX BUG-M09: Validasi schema yang lebih ketat
          if (!imported || typeof imported !== 'object') {
            throw new Error('Invalid macro format');
          }
          if (!imported.name || typeof imported.name !== 'string') {
            throw new Error('Missing or invalid macro name');
          }
          if (!Array.isArray(imported.steps)) {
            throw new Error('Missing or invalid steps array');
          }
          
          for (const step of imported.steps) {
            if (!step.type || !['tap', 'swipe', 'hold', 'wait'].includes(step.type)) {
              throw new Error(`Invalid step type: ${step.type}`);
            }
            if (typeof step.x !== 'number' || typeof step.y !== 'number') {
              throw new Error('Step missing valid x/y coordinates');
            }
          }
          
          const newMacro: GamepadMacro = {
            id: `macro_${Date.now()}`,
            name: imported.name,
            description: imported.description || '',
            steps: imported.steps,
            loopCount: imported.loopCount || 1,
            loopDelay: imported.loopDelay || 100,
            isEnabled: imported.isEnabled !== false,
            createdAt: Date.now(),
            updatedAt: Date.now(),
          };
          
          const updated = [newMacro, ...macros];
          onUpdateMacros(updated);
          setSelectedMacroId(newMacro.id);
          onLogMessage(`[MACRO] Imported macro: "${newMacro.name}" (${newMacro.steps.length} steps)`);
        } catch (err: any) {
          onLogMessage(`[MACRO] Import failed: ${err.message}`);
        }
      };
      reader.readAsText(file);
    };
    input.click();
  };

  // FIX BUG-L04: Tambahkan jitter randomization untuk humanize playback
  const applyJitter = (value: number, amount: number): number => {
    if (!jitterEnabled) return value;
    const jitter = (Math.random() - 0.5) * 2 * amount;
    return Math.max(0, Math.min(100, value + jitter));
  };

  const applyTimingJitter = (duration: number): number => {
    if (!jitterEnabled) return duration;
    const jitter = (Math.random() - 0.5) * 0.2 * duration;
    return Math.max(10, duration + jitter);
  };

  const handlePlay = async () => {
    if (!selectedMacro || selectedMacro.steps.length === 0) {
      onLogMessage('[MACRO] Cannot play: No steps defined');
      return;
    }

    setIsPlaying(true);
    setIsPaused(false);
    playbackRef.current = { isCancelled: false, isPaused: false };

    onLogMessage(`[MACRO] Starting playback: "${selectedMacro.name}" (${selectedMacro.steps.length} steps, ${selectedMacro.loopCount} loops)`);

    try {
      for (let loop = 0; loop < selectedMacro.loopCount; loop++) {
        if (playbackRef.current.isCancelled) break;
        
        setCurrentLoop(loop + 1);

        for (let i = 0; i < selectedMacro.steps.length; i++) {
          if (playbackRef.current.isCancelled) break;
          
          while (playbackRef.current.isPaused && !playbackRef.current.isCancelled) {
            await new Promise(r => setTimeout(r, 100));
          }
          
          setCurrentStepIndex(i);
          const step = selectedMacro.steps[i];

          const jitteredX = applyJitter(step.x, jitterAmount);
          const jitteredY = applyJitter(step.y, jitterAmount);
          
          const screenW = window.innerWidth;
          const screenH = window.innerHeight;
          const x = Math.round((jitteredX / 100) * screenW);
          const y = Math.round((jitteredY / 100) * screenH);

          switch (step.type) {
            case 'tap': {
              const duration = applyTimingJitter(step.duration || 50);
              await TouchInjection.touchDown({ pointerId: 0, x, y });
              await new Promise(r => setTimeout(r, duration));
              await TouchInjection.touchUp({ pointerId: 0 });
              break;
            }
            case 'hold': {
              const duration = applyTimingJitter(step.duration || 500);
              await TouchInjection.touchDown({ pointerId: 0, x, y });
              await new Promise(r => setTimeout(r, duration));
              await TouchInjection.touchUp({ pointerId: 0 });
              break;
            }
            case 'swipe': {
              const endX = Math.round(((step.endX || step.x + 10) / 100) * screenW);
              const endY = Math.round(((step.endY || step.y + 10) / 100) * screenH);
              const duration = applyTimingJitter(step.duration || 300);
              const steps = 10;
              
              await TouchInjection.touchDown({ pointerId: 0, x, y });
              for (let s = 1; s <= steps; s++) {
                if (playbackRef.current.isCancelled) break;
                const interpX = x + (endX - x) * (s / steps);
                const interpY = y + (endY - y) * (s / steps);
                await TouchInjection.touchMove({ pointerId: 0, x: Math.round(interpX), y: Math.round(interpY) });
                await new Promise(r => setTimeout(r, duration / steps));
              }
              await TouchInjection.touchUp({ pointerId: 0 });
              break;
            }
            case 'wait': {
              const delay = applyTimingJitter(step.delay || 500);
              await new Promise(r => setTimeout(r, delay));
              break;
            }
          }

          if (step.delay && step.type !== 'wait') {
            const delay = applyTimingJitter(step.delay);
            await new Promise(r => setTimeout(r, delay / playbackSpeed));
          }
        }

        if (loop < selectedMacro.loopCount - 1 && selectedMacro.loopDelay) {
          const delay = applyTimingJitter(selectedMacro.loopDelay);
          await new Promise(r => setTimeout(r, delay / playbackSpeed));
        }
      }

      onLogMessage(`[MACRO] Playback completed: "${selectedMacro.name}"`);
    } catch (err) {
      onLogMessage(`[MACRO] Playback error: ${err}`);
    } finally {
      setIsPlaying(false);
      setIsPaused(false);
      setCurrentStepIndex(-1);
      setCurrentLoop(0);
    }
  };

  const handleStop = () => {
    playbackRef.current.isCancelled = true;
    playbackRef.current.isPaused = false;
    setIsPlaying(false);
    setIsPaused(false);
    setCurrentStepIndex(-1);
    setCurrentLoop(0);
    onLogMessage('[MACRO] Playback stopped');
  };

  const handlePause = () => {
    playbackRef.current.isPaused = !playbackRef.current.isPaused;
    setIsPaused(playbackRef.current.isPaused);
    onLogMessage(`[MACRO] Playback ${playbackRef.current.isPaused ? 'paused' : 'resumed'}`);
  };

  return (
    <div className="bg-slate-900/40 border border-slate-900/60 rounded-xl overflow-hidden flex flex-col h-full">
      {/* Header */}
      <div className="px-6 py-4 border-b border-slate-900/60 flex items-center justify-between bg-slate-950/40">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-indigo-950/50 border border-indigo-500/30 flex items-center justify-center">
            <Zap className="w-5 h-5 text-indigo-400" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-slate-100 uppercase tracking-wider">Macro Playback Engine</h2>
            <p className="text-[10px] text-slate-500 font-mono">TACTILE SEQUENCE AUTOMATION</p>
          </div>
        </div>
        
        <div className="flex items-center gap-2">
          <button
            onClick={handleImportMacro}
            className="px-3 py-1.5 text-[10px] font-bold uppercase bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg flex items-center gap-1.5 transition-colors"
          >
            <Upload className="w-3 h-3" />
            Import
          </button>
          <button
            onClick={handleCreateMacro}
            className="px-3 py-1.5 text-[10px] font-bold uppercase bg-indigo-600 hover:bg-indigo-500 rounded-lg flex items-center gap-1.5 transition-colors"
          >
            <Plus className="w-3 h-3" />
            New Macro
          </button>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Macro List Sidebar */}
        <div className="w-72 border-r border-slate-900/60 flex flex-col bg-slate-950/20">
          <div className="px-4 py-3 border-b border-slate-900/60">
            <h3 className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
              Macros ({macros.length})
            </h3>
          </div>
          
          <div className="flex-1 overflow-y-auto">
            {macros.length === 0 ? (
              <div className="p-6 text-center">
                <Layers className="w-12 h-12 text-slate-700 mx-auto mb-3" />
                <p className="text-xs text-slate-500">No macros yet</p>
                <p className="text-[10px] text-slate-600 mt-1">Create your first macro to get started</p>
              </div>
            ) : (
              macros.map(macro => (
                <div
                  key={macro.id}
                  onClick={() => !isEditing && setSelectedMacroId(macro.id)}
                  className={`px-4 py-3 border-b border-slate-900/40 cursor-pointer transition-colors ${
                    selectedMacroId === macro.id 
                      ? 'bg-indigo-950/30 border-l-2 border-l-indigo-500' 
                      : 'hover:bg-slate-900/40'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className={`w-1.5 h-1.5 rounded-full ${macro.isEnabled ? 'bg-green-500' : 'bg-slate-600'}`} />
                        <span className="text-xs font-semibold text-slate-200 truncate">{macro.name}</span>
                      </div>
                      <div className="flex items-center gap-3 mt-1 text-[10px] text-slate-500">
                        <span className="flex items-center gap-1">
                          <Clock className="w-2.5 h-2.5" />
                          {macro.steps.length} steps
                        </span>
                        <span className="flex items-center gap-1">
                          <RotateCcw className="w-2.5 h-2.5" />
                          {macro.loopCount}x
                        </span>
                      </div>
                    </div>
                    
                    <div className="flex items-center gap-1 ml-2">
                      <button
                        onClick={(e) => { e.stopPropagation(); handleDuplicateMacro(macro); }}
                        className="p-1 hover:bg-slate-800 rounded transition-colors"
                        title="Duplicate"
                      >
                        <Copy className="w-3 h-3 text-slate-500" />
                      </button>
                      <button
                        onClick={(e) => { e.stopPropagation(); handleExportMacro(macro); }}
                        className="p-1 hover:bg-slate-800 rounded transition-colors"
                        title="Export"
                      >
                        <Download className="w-3 h-3 text-slate-500" />
                      </button>
                      <button
                        onClick={(e) => { e.stopPropagation(); handleDeleteMacro(macro.id); }}
                        className="p-1 hover:bg-red-900/40 rounded transition-colors"
                        title="Delete"
                      >
                        <Trash2 className="w-3 h-3 text-red-500" />
                      </button>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Macro Detail / Editor */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {!selectedMacro ? (
            <div className="flex-1 flex items-center justify-center">
              <div className="text-center">
                <Wand2 className="w-16 h-16 text-slate-700 mx-auto mb-4" />
                <p className="text-sm text-slate-500">Select a macro to view or edit</p>
                <p className="text-xs text-slate-600 mt-1">Or create a new one to get started</p>
              </div>
            </div>
          ) : isEditing && editingMacro ? (
            /* Edit Mode */
            <div className="flex-1 flex flex-col overflow-hidden">
              <div className="px-6 py-4 border-b border-slate-900/60 flex items-center justify-between bg-slate-950/30">
                <div className="flex items-center gap-3 flex-1">
                  <input
                    type="text"
                    value={editingMacro.name}
                    onChange={(e) => setEditingMacro({ ...editingMacro, name: e.target.value })}
                    className="bg-slate-800 border border-slate-700 rounded px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-indigo-500 w-64"
                    placeholder="Macro name"
                  />
                  <input
                    type="text"
                    value={editingMacro.description || ''}
                    onChange={(e) => setEditingMacro({ ...editingMacro, description: e.target.value })}
                    className="bg-slate-800 border border-slate-700 rounded px-3 py-1.5 text-xs text-slate-300 outline-none focus:border-indigo-500 flex-1"
                    placeholder="Description (optional)"
                  />
                </div>
                <div className="flex items-center gap-2 ml-4">
                  <button
                    onClick={handleCancelEdit}
                    className="px-3 py-1.5 text-[10px] font-bold uppercase bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg flex items-center gap-1.5 transition-colors"
                  >
                    <X className="w-3 h-3" />
                    Cancel
                  </button>
                  <button
                    onClick={handleSaveEdit}
                    className="px-3 py-1.5 text-[10px] font-bold uppercase bg-emerald-600 hover:bg-emerald-500 rounded-lg flex items-center gap-1.5 transition-colors"
                  >
                    <Save className="w-3 h-3" />
                    Save
                  </button>
                </div>
              </div>

              {/* Steps Editor */}
              <div className="flex-1 overflow-y-auto p-6">
                <div className="flex items-center justify-between mb-4">
                  <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                    <Layers className="w-4 h-4 text-indigo-400" />
                    Steps ({editingMacro.steps.length})
                  </h3>
                  <button
                    onClick={handleAddStep}
                    className="px-3 py-1.5 text-[10px] font-bold uppercase bg-indigo-600 hover:bg-indigo-500 rounded-lg flex items-center gap-1.5 transition-colors"
                  >
                    <Plus className="w-3 h-3" />
                    Add Step
                  </button>
                </div>

                {editingMacro.steps.length === 0 ? (
                  <div className="text-center py-12">
                    <Timer className="w-12 h-12 text-slate-700 mx-auto mb-3" />
                    <p className="text-xs text-slate-500">No steps yet</p>
                    <p className="text-[10px] text-slate-600 mt-1">Add steps to define your macro sequence</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {editingMacro.steps.map((step, index) => (
                      <div
                        key={step.id}
                        className={`bg-slate-800/40 border rounded-lg p-3 flex items-center gap-3 ${
                          showStepEditor && editingStepIndex === index
                            ? 'border-indigo-500/50'
                            : 'border-slate-700/50'
                        }`}
                      >
                        <div className="flex items-center gap-2">
                          <GripVertical className="w-4 h-4 text-slate-600 cursor-grab" />
                          <span className="text-[10px] font-mono text-slate-500 w-6">#{index + 1}</span>
                        </div>
                        
                        <div className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
                          step.type === 'tap' ? 'bg-blue-900/40 text-blue-400' :
                          step.type === 'hold' ? 'bg-purple-900/40 text-purple-400' :
                          step.type === 'swipe' ? 'bg-emerald-900/40 text-emerald-400' :
                          'bg-amber-900/40 text-amber-400'
                        }`}>
                          {step.type}
                        </div>
                        
                        <div className="flex-1 text-xs text-slate-300">
                          ({step.x.toFixed(1)}%, {step.y.toFixed(1)}%)
                          {step.type === 'swipe' && ` → (${(step.endX || 0).toFixed(1)}%, ${(step.endY || 0).toFixed(1)}%)`}
                          {step.type !== 'wait' && ` • ${step.duration || 50}ms`}
                          {step.delay && step.type !== 'wait' && ` • delay ${step.delay}ms`}
                          {step.description && ` • "${step.description}"`}
                        </div>
                        
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => { setEditingStepIndex(index); setShowStepEditor(!showStepEditor || editingStepIndex !== index); }}
                            className="p-1.5 hover:bg-slate-700 rounded transition-colors"
                          >
                            <Edit3 className="w-3.5 h-3.5 text-slate-400" />
                          </button>
                          <button
                            onClick={() => handleDeleteStep(index)}
                            className="p-1.5 hover:bg-red-900/40 rounded transition-colors"
                          >
                            <Trash2 className="w-3.5 h-3.5 text-red-500" />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}

                {/* Step Detail Editor */}
                {showStepEditor && editingStepIndex >= 0 && editingMacro.steps[editingStepIndex] && (
                  <StepEditor
                    step={editingMacro.steps[editingStepIndex]}
                    onUpdate={(updated) => handleUpdateStep(editingStepIndex, updated)}
                    onClose={() => { setShowStepEditor(false); setEditingStepIndex(-1); }}
                  />
                )}

                {/* Loop Settings */}
                <div className="mt-6 pt-6 border-t border-slate-800">
                  <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider mb-4 flex items-center gap-2">
                    <RotateCcw className="w-4 h-4 text-indigo-400" />
                    Loop Settings
                  </h3>
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase font-bold">Loop Count</label>
                      <input
                        type="number"
                        min={1}
                        value={editingMacro.loopCount}
                        onChange={(e) => setEditingMacro({ ...editingMacro, loopCount: Math.max(1, parseInt(e.target.value) || 1) })}
                        className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-indigo-500"
                      />
                    </div>
                    <div>
                      <label className="text-[10px] text-slate-500 uppercase font-bold">Loop Delay (ms)</label>
                      <input
                        type="number"
                        min={0}
                        value={editingMacro.loopDelay}
                        onChange={(e) => setEditingMacro({ ...editingMacro, loopDelay: Math.max(0, parseInt(e.target.value) || 0) })}
                        className="w-full mt-1 bg-slate-800 border border-slate-700 rounded px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-indigo-500"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            /* View Mode */
            <div className="flex-1 flex flex-col overflow-hidden">
              {/* Playback Controls */}
              <div className="px-6 py-4 border-b border-slate-900/60 flex items-center justify-between bg-slate-950/30">
                <div className="flex items-center gap-4">
                  <h3 className="text-sm font-bold text-slate-100">{selectedMacro.name}</h3>
                  {selectedMacro.description && (
                    <span className="text-xs text-slate-500">{selectedMacro.description}</span>
                  )}
                </div>
                
                <div className="flex items-center gap-2">
                  <div className="flex items-center gap-2 mr-4">
                    <span className="text-[10px] text-slate-500 uppercase font-bold">Speed:</span>
                    <select
                      value={playbackSpeed}
                      onChange={(e) => setPlaybackSpeed(parseFloat(e.target.value))}
                      className="bg-slate-800 border border-slate-700 rounded px-2 py-1 text-[10px] text-slate-200 outline-none"
                    >
                      <option value={0.25}>0.25x</option>
                      <option value={0.5}>0.5x</option>
                      <option value={1}>1x</option>
                      <option value={2}>2x</option>
                      <option value={4}>4x</option>
                    </select>
                  </div>
                  
                  {!isPlaying ? (
                    <button
                      onClick={handlePlay}
                      className="px-4 py-2 text-xs font-bold uppercase bg-emerald-600 hover:bg-emerald-500 rounded-lg flex items-center gap-2 transition-colors"
                    >
                      <Play className="w-4 h-4" />
                      Play
                    </button>
                  ) : (
                    <>
                      <button
                        onClick={handlePause}
                        className="px-3 py-2 text-xs font-bold uppercase bg-amber-600 hover:bg-amber-500 rounded-lg flex items-center gap-2 transition-colors"
                      >
                        {isPaused ? <Play className="w-4 h-4" /> : <Pause className="w-4 h-4" />}
                        {isPaused ? 'Resume' : 'Pause'}
                      </button>
                      <button
                        onClick={handleStop}
                        className="px-3 py-2 text-xs font-bold uppercase bg-red-600 hover:bg-red-500 rounded-lg flex items-center gap-2 transition-colors"
                      >
                        <Square className="w-4 h-4" />
                        Stop
                      </button>
                    </>
                  )}
                  
                  <button
                    onClick={handleStartEdit}
                    className="px-3 py-2 text-xs font-bold uppercase bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg flex items-center gap-2 transition-colors"
                  >
                    <Edit3 className="w-4 h-4" />
                    Edit
                  </button>
                </div>
              </div>

              {/* Playback Status */}
              {isPlaying && (
                <div className="px-6 py-3 bg-indigo-950/30 border-b border-indigo-900/30 flex items-center gap-4">
                  <div className="flex items-center gap-2">
                    <div className="w-2 h-2 rounded-full bg-indigo-500 animate-pulse" />
                    <span className="text-[10px] font-mono text-indigo-400 uppercase">Playing</span>
                  </div>
                  <span className="text-[10px] text-slate-400">
                    Loop {currentLoop}/{selectedMacro.loopCount} • Step {currentStepIndex + 1}/{selectedMacro.steps.length}
                  </span>
                  {isPaused && (
                    <span className="text-[10px] text-amber-400 font-bold uppercase">⏸ Paused</span>
                  )}
                </div>
              )}

              {/* Steps List */}
              <div className="flex-1 overflow-y-auto p-6">
                <div className="flex items-center justify-between mb-4">
                  <h3 className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                    <Layers className="w-4 h-4 text-indigo-400" />
                    Steps ({selectedMacro.steps.length})
                  </h3>
                  
                  <button
                    onClick={() => setShowAdvanced(!showAdvanced)}
                    className="flex items-center gap-1.5 text-[10px] text-slate-400 hover:text-slate-200 transition-colors"
                  >
                    <Zap className="w-3 h-3" />
                    Advanced Settings
                    <ChevronDown className={`w-3 h-3 transition-transform ${showAdvanced ? 'rotate-180' : ''}`} />
                  </button>
                </div>

                {/* Advanced Settings */}
                {showAdvanced && (
                  <div className="mb-6 p-4 bg-slate-800/30 border border-slate-700/50 rounded-lg">
                    <h4 className="text-[10px] font-bold text-slate-300 uppercase mb-3">Humanization</h4>
                    <div className="grid grid-cols-2 gap-4">
                      <div className="flex items-center justify-between">
                        <span className="text-xs text-slate-400">Enable Jitter</span>
                        <button
                          onClick={() => setJitterEnabled(!jitterEnabled)}
                          className={`w-10 h-5 rounded-full transition-colors ${jitterEnabled ? 'bg-indigo-600' : 'bg-slate-700'}`}
                        >
                          <div className={`w-4 h-4 rounded-full bg-white transition-transform ${jitterEnabled ? 'translate-x-5' : 'translate-x-0.5'}`} />
                        </button>
                      </div>
                      <div>
                        <span className="text-xs text-slate-400">Jitter Amount: {jitterAmount}px</span>
                        <input
                          type="range"
                          min={1}
                          max={20}
                          value={jitterAmount}
                          onChange={(e) => setJitterAmount(parseInt(e.target.value))}
                          className="w-full mt-1"
                          disabled={!jitterEnabled}
                        />
                      </div>
                    </div>
                  </div>
                )}

                {selectedMacro.steps.length === 0 ? (
                  <div className="text-center py-12">
                    <Timer className="w-12 h-12 text-slate-700 mx-auto mb-3" />
                    <p className="text-xs text-slate-500">No steps defined</p>
                    <p className="text-[10px] text-slate-600 mt-1">Edit this macro to add steps</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {selectedMacro.steps.map((step, index) => (
                      <div
                        key={step.id}
                        className={`bg-slate-800/40 border rounded-lg p-3 flex items-center gap-3 transition-colors ${
                          isPlaying && currentStepIndex === index
                            ? 'border-indigo-500 bg-indigo-950/20'
                            : 'border-slate-700/50'
                        }`}
                      >
                        <div className="flex items-center gap-2">
                          <span className="text-[10px] font-mono text-slate-500 w-6">#{index + 1}</span>
                          {isPlaying && currentStepIndex === index && (
                            <div className="w-2 h-2 rounded-full bg-indigo-500 animate-pulse" />
                          )}
                        </div>
                        
                        <div className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
                          step.type === 'tap' ? 'bg-blue-900/40 text-blue-400' :
                          step.type === 'hold' ? 'bg-purple-900/40 text-purple-400' :
                          step.type === 'swipe' ? 'bg-emerald-900/40 text-emerald-400' :
                          'bg-amber-900/40 text-amber-400'
                        }`}>
                          {step.type}
                        </div>
                        
                        <div className="flex-1 text-xs text-slate-300">
                          ({step.x.toFixed(1)}%, {step.y.toFixed(1)}%)
                          {step.type === 'swipe' && ` → (${(step.endX || 0).toFixed(1)}%, ${(step.endY || 0).toFixed(1)}%)`}
                          {step.type !== 'wait' && ` • ${step.duration || 50}ms`}
                          {step.delay && step.type !== 'wait' && ` • delay ${step.delay}ms`}
                          {step.description && ` • "${step.description}"`}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ==========================================
// Step Editor Sub-component
// ==========================================
interface StepEditorProps {
  step: MacroStep;
  onUpdate: (step: MacroStep) => void;
  onClose: () => void;
}

function StepEditor({ step, onUpdate, onClose }: StepEditorProps) {
  const [localStep, setLocalStep] = React.useState<MacroStep>({ ...step });

  React.useEffect(() => {
    setLocalStep({ ...step });
  }, [step]);

  const handleSave = () => {
    onUpdate(localStep);
    onClose();
  };

  return (
    <div className="mt-4 p-4 bg-slate-800/60 border border-indigo-500/30 rounded-lg">
      <div className="flex items-center justify-between mb-4">
        <h4 className="text-xs font-bold text-slate-200 uppercase">Step Editor</h4>
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="px-2 py-1 text-[10px] bg-slate-700 hover:bg-slate-600 rounded"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            className="px-2 py-1 text-[10px] bg-indigo-600 hover:bg-indigo-500 rounded"
          >
            Apply
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="text-[10px] text-slate-500 uppercase font-bold">Type</label>
          <select
            value={localStep.type}
            onChange={(e) => setLocalStep({ ...localStep, type: e.target.value as any })}
            className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
          >
            <option value="tap">Tap</option>
            <option value="hold">Hold</option>
            <option value="swipe">Swipe</option>
            <option value="wait">Wait</option>
          </select>
        </div>

        <div>
          <label className="text-[10px] text-slate-500 uppercase font-bold">Description</label>
          <input
            type="text"
            value={localStep.description || ''}
            onChange={(e) => setLocalStep({ ...localStep, description: e.target.value })}
            className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
            placeholder="Optional description"
          />
        </div>

        {localStep.type !== 'wait' && (
          <>
            <div>
              <label className="text-[10px] text-slate-500 uppercase font-bold">X Position (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step={0.1}
                value={localStep.x}
                onChange={(e) => setLocalStep({ ...localStep, x: parseFloat(e.target.value) || 0 })}
                className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
              />
            </div>
            <div>
              <label className="text-[10px] text-slate-500 uppercase font-bold">Y Position (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step={0.1}
                value={localStep.y}
                onChange={(e) => setLocalStep({ ...localStep, y: parseFloat(e.target.value) || 0 })}
                className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
              />
            </div>
          </>
        )}

        {localStep.type === 'swipe' && (
          <>
            <div>
              <label className="text-[10px] text-slate-500 uppercase font-bold">End X (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step={0.1}
                value={localStep.endX || 0}
                onChange={(e) => setLocalStep({ ...localStep, endX: parseFloat(e.target.value) || 0 })}
                className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
              />
            </div>
            <div>
              <label className="text-[10px] text-slate-500 uppercase font-bold">End Y (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step={0.1}
                value={localStep.endY || 0}
                onChange={(e) => setLocalStep({ ...localStep, endY: parseFloat(e.target.value) || 0 })}
                className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
              />
            </div>
          </>
        )}

        {localStep.type !== 'wait' && (
          <div>
            <label className="text-[10px] text-slate-500 uppercase font-bold">Duration (ms)</label>
            <input
              type="number"
              min={10}
              value={localStep.duration || 50}
              onChange={(e) => setLocalStep({ ...localStep, duration: parseInt(e.target.value) || 50 })}
              className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
            />
          </div>
        )}

        <div>
          <label className="text-[10px] text-slate-500 uppercase font-bold">Delay After (ms)</label>
          <input
            type="number"
            min={0}
            value={localStep.delay || 0}
            onChange={(e) => setLocalStep({ ...localStep, delay: parseInt(e.target.value) || 0 })}
            className="w-full mt-1 bg-slate-900 border border-slate-700 rounded px-2 py-1.5 text-xs text-slate-200 outline-none"
          />
        </div>
      </div>
    </div>
  );
}
