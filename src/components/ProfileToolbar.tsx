import React from 'react';
import { Settings, Save, Trash2, Eye, EyeOff, Check } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function ProfileToolbar({ h }: { h: OverlayWysiwygHook }) {
  const [saved, setSaved] = React.useState(false);
  
  if (h.isNativeOverlay) return null;
  
  const handleSave = () => {
    h.onUpdateProfile(h.activeProfile);
    h.onLogMessage('Profile saved successfully to storage.');
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };
  
  return (
    <div className="flex flex-wrap justify-between items-center gap-3 mb-4">
      <div className="flex items-center gap-3 bg-slate-900 border border-slate-800 p-1.5 rounded-lg">
        <h3 className="text-sm font-bold text-indigo-400 ml-2">WYSIWYG Overlay Editor</h3>
        <div className="w-px h-5 bg-slate-800 mx-1"></div>
        <div className="flex items-center gap-2 px-2 text-xs">
          <span className="text-slate-400">Profile:</span>
          <span className="font-semibold text-slate-200">{h.activeProfile?.name || 'Unsaved'}</span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <button
          onClick={() => h.setHideGrid(!h.hideGrid)}
          className={`px-3 py-1.5 flex items-center gap-2 text-xs font-semibold rounded-md border transition-colors ${
            h.hideGrid 
              ? 'bg-indigo-900/40 text-indigo-300 border-indigo-500/50' 
              : 'bg-slate-900 text-slate-400 border-slate-700 hover:border-slate-500 hover:text-slate-200'
          }`}
        >
          {h.hideGrid ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          Grid
        </button>

        <button
          onClick={() => h.setHideAllNodes(!h.hideAllNodes)}
          className={`px-3 py-1.5 flex items-center gap-2 text-xs font-semibold rounded-md border transition-colors ${
            h.hideAllNodes 
              ? 'bg-rose-900/40 text-rose-300 border-rose-500/50' 
              : 'bg-slate-900 text-slate-400 border-slate-700 hover:border-slate-500 hover:text-slate-200'
          }`}
        >
          {h.hideAllNodes ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          Nodes
        </button>

        <button 
          onClick={handleSave}
          className={`px-4 py-1.5 ${saved ? 'bg-emerald-500' : 'bg-emerald-600 hover:bg-emerald-500'} text-white flex items-center gap-2 text-xs font-bold rounded-md shadow transition-all`}
        >
          {saved ? <Check className="w-3.5 h-3.5" /> : <Save className="w-3.5 h-3.5" />}
          {saved ? 'Saved!' : 'Save'}
        </button>
      </div>
    </div>
  );
}
