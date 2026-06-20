/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * GameSelector - Profile Manager untuk Gamepad Mapping
 * 
 * FIX BUG-M01: Replace confirm() dengan custom modal dialog
 * FIX BUG-M02: Gunakan Capacitor Filesystem untuk export, fallback ke Blob download
 * FIX BUG-M09: Tambah strict schema validation untuk profile import
 */
import React from 'react';
import { GamepadProfile } from '../types';
import {
  Plus, Trash2, Download, Upload, Copy, Save,
  AlertTriangle, CheckCircle, FileJson, Gamepad2,
  Edit3, Search, X, ChevronDown, ChevronUp,
  Shield, Zap, Clock, Layers
} from 'lucide-react';

interface GameSelectorProps {
  profiles: GamepadProfile[];
  activeProfileId: string;
  onProfileSelect: (id: string) => void;
  onUpdateProfile: (profile: GamepadProfile) => void;
  onCreateProfile: (profile: GamepadProfile) => void;
  onDeleteProfile: (id: string) => void;
  onLogMessage: (msg: string) => void;
}

// ============================================
// FIX BUG-M09: Schema validation
// ============================================
interface ProfileValidationResult {
  valid: boolean;
  errors: string[];
}

function validateProfileSchema(data: any): ProfileValidationResult {
  const errors: string[] = [];

  if (!data || typeof data !== 'object') {
    return { valid: false, errors: ['Data must be an object'] };
  }

  if (!data.id || typeof data.id !== 'string') {
    errors.push('Missing or invalid "id" (must be string)');
  }

  if (!data.name || typeof data.name !== 'string') {
    errors.push('Missing or invalid "name" (must be string)');
  }

  if (data.packageName !== undefined && typeof data.packageName !== 'string') {
    errors.push('Invalid "packageName" (must be string if present)');
  }

  if (data.deadzone !== undefined && (typeof data.deadzone !== 'number' || data.deadzone < 0 || data.deadzone > 1)) {
    errors.push('Invalid "deadzone" (must be number between 0 and 1)');
  }

  if (data.smoothing !== undefined && (typeof data.smoothing !== 'number' || data.smoothing < 0 || data.smoothing > 1)) {
    errors.push('Invalid "smoothing" (must be number between 0 and 1)');
  }

  if (data.globalOpacity !== undefined && (typeof data.globalOpacity !== 'number' || data.globalOpacity < 0 || data.globalOpacity > 100)) {
    errors.push('Invalid "globalOpacity" (must be number between 0 and 100)');
  }

  if (data.bgDimLevel !== undefined && (typeof data.bgDimLevel !== 'number' || data.bgDimLevel < 0 || data.bgDimLevel > 100)) {
    errors.push('Invalid "bgDimLevel" (must be number between 0 and 100)');
  }

  if (data.antiBanEnabled !== undefined && typeof data.antiBanEnabled !== 'boolean') {
    errors.push('Invalid "antiBanEnabled" (must be boolean)');
  }

  if (data.buttons !== undefined) {
    if (!Array.isArray(data.buttons)) {
      errors.push('Invalid "buttons" (must be array)');
    } else {
      data.buttons.forEach((btn: any, index: number) => {
        if (!btn.mappedKey || typeof btn.mappedKey !== 'string') {
          errors.push(`Button[${index}]: missing or invalid "mappedKey"`);
        }
        if (btn.x !== undefined && typeof btn.x !== 'number') {
          errors.push(`Button[${index}]: invalid "x" (must be number)`);
        }
        if (btn.y !== undefined && typeof btn.y !== 'number') {
          errors.push(`Button[${index}]: invalid "y" (must be number)`);
        }
        if (btn.type !== undefined && !['button', 'analog', 'dpad', 'swipe', 'hold'].includes(btn.type)) {
          errors.push(`Button[${index}]: invalid "type" (must be button/analog/dpad/swipe/hold)`);
        }
      });
    }
  }

  return { valid: errors.length === 0, errors };
}

// ============================================
// SUB-COMPONENT: ConfirmDialog (FIX BUG-M01)
// ============================================
interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'warning' | 'info';
  onConfirm: () => void;
  onCancel: () => void;
}

const ConfirmDialog = ({
  isOpen, title, message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
  onConfirm, onCancel
}: ConfirmDialogProps) => {
  if (!isOpen) return null;

  const variantStyles = {
    danger: {
      bg: 'bg-red-600 hover:bg-red-500',
      icon: <AlertTriangle className="w-6 h-6 text-red-400" />,
      border: 'border-red-500/30',
    },
    warning: {
      bg: 'bg-amber-600 hover:bg-amber-500',
      icon: <AlertTriangle className="w-6 h-6 text-amber-400" />,
      border: 'border-amber-500/30',
    },
    info: {
      bg: 'bg-indigo-600 hover:bg-indigo-500',
      icon: <CheckCircle className="w-6 h-6 text-indigo-400" />,
      border: 'border-indigo-500/30',
    },
  };

  const style = variantStyles[variant];

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className={`bg-slate-900 border ${style.border} rounded-xl p-6 max-w-md w-full mx-4 shadow-2xl`}>
        <div className="flex items-center gap-3 mb-4">
          {style.icon}
          <h3 className="text-lg font-bold text-slate-100">{title}</h3>
        </div>
        <p className="text-sm text-slate-300 mb-6 leading-relaxed">{message}</p>
        <div className="flex items-center justify-end gap-3">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-xs font-bold font-mono uppercase bg-slate-800/60 hover:bg-slate-800 border border-slate-700 text-slate-300 rounded-lg transition-colors"
          >
            {cancelLabel}
          </button>
          <button
            onClick={onConfirm}
            className={`px-4 py-2 text-xs font-bold font-mono uppercase ${style.bg} text-white rounded-lg transition-colors`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================
// SUB-COMPONENT: ProfileCard
// ============================================
interface ProfileCardProps {
  profile: GamepadProfile;
  isActive: boolean;
  isEditing: boolean;
  editName: string;
  onSelect: () => void;
  onStartRename: () => void;
  onSaveRename: () => void;
  onCancelRename: () => void;
  onEditNameChange: (name: string) => void;
  onDuplicate: () => void;
  onExport: () => void;
  onDelete: () => void;
}

const ProfileCard = React.memo(({
  profile, isActive, isEditing, editName,
  onSelect, onStartRename, onSaveRename, onCancelRename, onEditNameChange,
  onDuplicate, onExport, onDelete
}: ProfileCardProps) => {
  return (
    <div
      className={`bg-slate-950/40 border rounded-lg p-4 transition-all cursor-pointer ${
        isActive
          ? 'border-indigo-500/50 bg-indigo-950/20 shadow-lg shadow-indigo-500/5'
          : 'border-slate-800/60 hover:border-slate-700/60'
      }`}
      onClick={onSelect}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`w-3 h-3 rounded-full ${isActive ? 'bg-indigo-500 animate-pulse' : 'bg-slate-700'}`} />
          {isEditing ? (
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={editName}
                onChange={(e) => onEditNameChange(e.target.value)}
                onClick={(e) => e.stopPropagation()}
                className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-sm text-slate-200 outline-none focus:border-indigo-500"
                autoFocus
              />
              <button
                onClick={(e) => { e.stopPropagation(); onSaveRename(); }}
                className="p-1 text-emerald-400 hover:text-emerald-300"
              >
                <Save className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); onCancelRename(); }}
                className="p-1 text-slate-500 hover:text-slate-300"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          ) : (
            <div>
              <h3 className="text-sm font-bold text-slate-100">{profile.name}</h3>
              {profile.packageName && (
                <p className="text-[9px] font-mono text-slate-500 mt-0.5">{profile.packageName}</p>
              )}
            </div>
          )}
        </div>
        <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button
            onClick={onStartRename}
            className="p-1.5 text-slate-500 hover:text-indigo-400 rounded transition-colors"
            title="Rename"
          >
            <Edit3 className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={onDuplicate}
            className="p-1.5 text-slate-500 hover:text-purple-400 rounded transition-colors"
            title="Duplicate"
          >
            <Copy className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={onExport}
            className="p-1.5 text-slate-500 hover:text-emerald-400 rounded transition-colors"
            title="Export"
          >
            <Download className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={onDelete}
            className="p-1.5 text-slate-500 hover:text-red-400 rounded transition-colors"
            title="Delete"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
      <div className="flex items-center gap-4 mt-3 text-[9px] font-mono text-slate-500">
        <span className="flex items-center gap-1">
          <Layers className="w-2.5 h-2.5" />
          {profile.buttons?.length || 0} buttons
        </span>
        <span>DZ: {((profile.deadzone || 0.15) * 100).toFixed(0)}%</span>
        <span>SM: {((profile.smoothing || 0.5) * 100).toFixed(0)}%</span>
        <span className={profile.antiBanEnabled ? 'text-emerald-500' : 'text-red-500'}>
          <Shield className="w-2.5 h-2.5 inline mr-0.5" />
          {profile.antiBanEnabled ? 'ON' : 'OFF'}
        </span>
      </div>
    </div>
  );
});

ProfileCard.displayName = 'ProfileCard';

// ============================================
// MAIN COMPONENT: GameSelector
// ============================================
export default function GameSelector({
  profiles,
  activeProfileId,
  onProfileSelect,
  onUpdateProfile,
  onCreateProfile,
  onDeleteProfile,
  onLogMessage
}: GameSelectorProps) {
  const [isCreating, setIsCreating] = React.useState(false);
  const [newProfileName, setNewProfileName] = React.useState('');
  const [newProfilePackage, setNewProfilePackage] = React.useState('');
  const [newProfileGame, setNewProfileGame] = React.useState('');
  const [editingProfileId, setEditingProfileId] = React.useState<string | null>(null);
  const [editingName, setEditingName] = React.useState('');
  const [importError, setImportError] = React.useState<string | null>(null);
  const [importSuccess, setImportSuccess] = React.useState<string | null>(null);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [sortBy, setSortBy] = React.useState<'name' | 'recent' | 'buttons'>('recent');
  const [showSortMenu, setShowSortMenu] = React.useState(false);
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  // FIX BUG-M01: Custom confirmation dialog state
  const [confirmDialog, setConfirmDialog] = React.useState<{
    isOpen: boolean;
    title: string;
    message: string;
    variant: 'danger' | 'warning' | 'info';
    onConfirm: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
    variant: 'danger',
    onConfirm: () => {},
  });

  // Filter and sort profiles
  const filteredProfiles = React.useMemo(() => {
    let result = [...profiles];

    // Filter by search
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      result = result.filter(p =>
        p.name.toLowerCase().includes(query) ||
        (p.packageName && p.packageName.toLowerCase().includes(query)) ||
        (p.game && p.game.toLowerCase().includes(query))
      );
    }

    // Sort
    switch (sortBy) {
      case 'name':
        result.sort((a, b) => a.name.localeCompare(b.name));
        break;
      case 'recent':
        result.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
        break;
      case 'buttons':
        result.sort((a, b) => (b.buttons?.length || 0) - (a.buttons?.length || 0));
        break;
    }

    return result;
  }, [profiles, searchQuery, sortBy]);

  // Create new profile
  const handleCreate = () => {
    if (!newProfileName.trim()) return;

    const newProfile: GamepadProfile = {
      id: `profile_${Date.now()}`,
      name: newProfileName.trim(),
      game: newProfileGame.trim() || newProfileName.trim(),
      packageName: newProfilePackage.trim() || undefined,
      description: '',
      deadzone: 0.15,
      smoothing: 0.5,
      antiBanEnabled: true,
      globalOpacity: 80,
      bgDimLevel: 50,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      buttons: [
        { id: 'l_stick', mappedKey: 'L_STICK', x: 20, y: 70, width: 120, height: 120, type: 'analog', label: 'Move', androidEventCode: 0 },
        { id: 'r_stick', mappedKey: 'R_STICK', x: 80, y: 50, width: 120, height: 120, type: 'analog', label: 'Camera', androidEventCode: 0 },
        { id: 'btn_a', mappedKey: 'A', x: 75, y: 80, width: 60, height: 60, type: 'button', label: 'Button A', androidEventCode: 96 },
        { id: 'btn_b', mappedKey: 'B', x: 82, y: 72, width: 60, height: 60, type: 'button', label: 'Button B', androidEventCode: 97 },
        { id: 'btn_x', mappedKey: 'X', x: 68, y: 72, width: 60, height: 60, type: 'button', label: 'Button X', androidEventCode: 99 },
        { id: 'btn_y', mappedKey: 'Y', x: 75, y: 64, width: 60, height: 60, type: 'button', label: 'Button Y', androidEventCode: 100 },
        { id: 'btn_lt', mappedKey: 'LT', x: 15, y: 15, width: 80, height: 60, type: 'button', label: 'L Trigger', androidEventCode: 102 },
        { id: 'btn_rt', mappedKey: 'RT', x: 85, y: 15, width: 80, height: 60, type: 'button', label: 'R Trigger', androidEventCode: 103 },
        { id: 'btn_lb', mappedKey: 'LB', x: 15, y: 8, width: 80, height: 50, type: 'button', label: 'L Bumper', androidEventCode: 102 },
        { id: 'btn_rb', mappedKey: 'RB', x: 85, y: 8, width: 80, height: 50, type: 'button', label: 'R Bumper', androidEventCode: 103 },
        { id: 'btn_start', mappedKey: 'START', x: 60, y: 5, width: 50, height: 40, type: 'button', label: 'Start', androidEventCode: 108 },
        { id: 'btn_select', mappedKey: 'SELECT', x: 40, y: 5, width: 50, height: 40, type: 'button', label: 'Select', androidEventCode: 109 },
      ]
    };

    onCreateProfile(newProfile);
    setNewProfileName('');
    setNewProfilePackage('');
    setNewProfileGame('');
    setIsCreating(false);
    onLogMessage(`[PROFILE] Created new profile "${newProfile.name}"`);
  };

  // FIX BUG-M01: Delete dengan custom modal
  const handleDelete = (profileId: string) => {
    const profile = profiles.find(p => p.id === profileId);
    if (!profile) return;

    setConfirmDialog({
      isOpen: true,
      title: 'Delete Profile',
      message: `Are you sure you want to delete profile "${profile.name}"? This action cannot be undone. All button mappings will be lost.`,
      variant: 'danger',
      onConfirm: () => {
        onDeleteProfile(profileId);
        onLogMessage(`[PROFILE] Deleted profile "${profile.name}"`);
        setConfirmDialog(prev => ({ ...prev, isOpen: false }));
      }
    });
  };

  // Duplicate profile
  const handleDuplicate = (profile: GamepadProfile) => {
    const duplicate: GamepadProfile = {
      ...JSON.parse(JSON.stringify(profile)),
      id: `profile_${Date.now()}`,
      name: `${profile.name} (Copy)`,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    onCreateProfile(duplicate);
    onProfileSelect(duplicate.id);
    onLogMessage(`[PROFILE] Duplicated "${profile.name}" → "${duplicate.name}"`);
  };

  // FIX BUG-M02: Export dengan Capacitor Filesystem + fallback
  const handleExport = async (profile: GamepadProfile) => {
    try {
      const exportData = JSON.stringify(profile, null, 2);
      const fileName = `${profile.name.replace(/[^a-zA-Z0-9]/g, '_')}_profile.json`;

      // Try Capacitor Filesystem first
      try {
        const { Filesystem, Directory } = await import('@capacitor/filesystem');
        await Filesystem.writeFile({
          path: fileName,
          data: exportData,
          directory: Directory.Documents,
          recursive: true
        });

        // Try to share
        try {
          const { Share } = await import('@capacitor/share');
          const uriResult = await Filesystem.getUri({
            path: fileName,
            directory: Directory.Documents
          });

          await Share.share({
            title: `Export Profile: ${profile.name}`,
            text: `GameMapperMind Profile: ${profile.name}`,
            url: uriResult.uri,
            dialogTitle: 'Share Profile'
          });
        } catch {
          // Share not available, fallback to download
          fallbackDownload(exportData, fileName);
        }

        onLogMessage(`[PROFILE] Exported "${profile.name}" to Documents`);
        setImportSuccess(`Profile "${profile.name}" exported successfully!`);
      } catch {
        // Fallback to browser download
        fallbackDownload(exportData, fileName);
        onLogMessage(`[PROFILE] Exported "${profile.name}" as download`);
        setImportSuccess(`Profile "${profile.name}" exported successfully!`);
      }

      setTimeout(() => setImportSuccess(null), 3000);
    } catch (err) {
      console.error('Export failed:', err);
      onLogMessage(`[PROFILE] Export failed - ${err}`);
      setImportError(`Export failed: ${err}`);
      setTimeout(() => setImportError(null), 5000);
    }
  };

  // FIX BUG-M02: Fallback download untuk browser
  const fallbackDownload = (data: string, fileName: string) => {
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // FIX BUG-M09: Import dengan validasi schema ketat
  const handleImport = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    setImportError(null);
    setImportSuccess(null);

    try {
      const text = await file.text();
      const importedData = JSON.parse(text);

      // FIX BUG-M09: Validasi schema
      const validation = validateProfileSchema(importedData);
      if (!validation.valid) {
        setImportError(`Invalid profile schema:\n${validation.errors.join('\n')}`);
        onLogMessage(`[PROFILE] Import failed - invalid schema: ${validation.errors[0]}`);
        setTimeout(() => setImportError(null), 8000);
        return;
      }

      // Cek duplikasi ID
      if (profiles.find(p => p.id === importedData.id)) {
        importedData.id = `profile_${Date.now()}`;
        importedData.name = `${importedData.name} (Imported)`;
      }

      // Build profile dengan default values
      const profile: GamepadProfile = {
        id: importedData.id,
        name: importedData.name,
        game: importedData.game || importedData.name,
        packageName: importedData.packageName,
        description: importedData.description || '',
        deadzone: importedData.deadzone ?? 0.15,
        smoothing: importedData.smoothing ?? 0.5,
        antiBanEnabled: importedData.antiBanEnabled ?? true,
        globalOpacity: importedData.globalOpacity ?? 80,
        bgDimLevel: importedData.bgDimLevel ?? 50,
        createdAt: importedData.createdAt || Date.now(),
        updatedAt: Date.now(),
        buttons: (importedData.buttons || []).map((btn: any, idx: number) => ({
          id: btn.id || `btn_${Date.now()}_${idx}`,
          mappedKey: btn.mappedKey,
          x: btn.x ?? 50,
          y: btn.y ?? 50,
          width: btn.width ?? 60,
          height: btn.height ?? 60,
          type: btn.type || 'button',
          label: btn.label || btn.mappedKey,
          androidEventCode: btn.androidEventCode ?? 0,
          swipeDirection: btn.swipeDirection,
          swipeDuration: btn.swipeDuration,
        }))
      };

      onCreateProfile(profile);
      onProfileSelect(profile.id);
      onLogMessage(`[PROFILE] Imported "${profile.name}" successfully (${profile.buttons.length} buttons)`);
      setImportSuccess(`Profile "${profile.name}" imported successfully!`);
      setTimeout(() => setImportSuccess(null), 3000);
    } catch (err) {
      console.error('Import failed:', err);
      setImportError(`Failed to parse profile file: ${err}`);
      onLogMessage(`[PROFILE] Import failed - ${err}`);
      setTimeout(() => setImportError(null), 5000);
    }

    // Reset file input
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  // Rename handlers
  const handleStartRename = (profile: GamepadProfile) => {
    setEditingProfileId(profile.id);
    setEditingName(profile.name);
  };

  const handleSaveRename = (profileId: string) => {
    if (!editingName.trim()) return;
    const profile = profiles.find(p => p.id === profileId);
    if (!profile) return;

    onUpdateProfile({ ...profile, name: editingName.trim(), updatedAt: Date.now() });
    setEditingProfileId(null);
    setEditingName('');
    onLogMessage(`[PROFILE] Renamed to "${editingName.trim()}"`);
  };

  const handleCancelRename = () => {
    setEditingProfileId(null);
    setEditingName('');
  };

  return (
    <div className="bg-slate-900/40 border border-slate-800/60 rounded-xl overflow-hidden">
      {/* Header */}
      <div className="bg-slate-950/60 border-b border-slate-800/60 px-6 py-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Gamepad2 className="w-5 h-5 text-indigo-400" />
          <h2 className="font-orbitron text-sm font-bold tracking-wider text-slate-100 uppercase">
            Profile Manager
          </h2>
          <span className="text-[9px] font-mono bg-indigo-950/50 text-indigo-400 border border-indigo-900/60 px-2 py-0.5 rounded uppercase tracking-wider">
            {profiles.length} profiles
          </span>
        </div>
        <div className="flex items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            onChange={handleImport}
            className="hidden"
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            className="px-3 py-1.5 text-[10px] font-bold font-mono uppercase bg-emerald-950/40 hover:bg-emerald-900/40 border border-emerald-500/50 text-emerald-400 rounded-lg flex items-center gap-1.5 transition-colors"
          >
            <Upload className="w-3 h-3" />
            Import
          </button>
          <button
            onClick={() => setIsCreating(true)}
            className="px-3 py-1.5 text-[10px] font-bold font-mono uppercase bg-indigo-950/40 hover:bg-indigo-900/40 border border-indigo-500/50 text-indigo-400 rounded-lg flex items-center gap-1.5 transition-colors"
          >
            <Plus className="w-3 h-3" />
            New Profile
          </button>
        </div>
      </div>

      {/* Notifications */}
      {importError && (
        <div className="mx-6 mt-4 p-3 bg-red-950/40 border border-red-500/50 rounded-lg flex items-start gap-2">
          <AlertTriangle className="w-4 h-4 text-red-400 mt-0.5 flex-shrink-0" />
          <div className="flex-1">
            <p className="text-xs font-bold text-red-400">Import Error</p>
            <p className="text-[10px] font-mono text-red-300 mt-1 whitespace-pre-line">{importError}</p>
          </div>
          <button onClick={() => setImportError(null)} className="p-1 text-red-400 hover:text-red-300">
            <X className="w-3 h-3" />
          </button>
        </div>
      )}
      {importSuccess && (
        <div className="mx-6 mt-4 p-3 bg-emerald-950/40 border border-emerald-500/50 rounded-lg flex items-center gap-2">
          <CheckCircle className="w-4 h-4 text-emerald-400" />
          <p className="text-xs font-mono text-emerald-300">{importSuccess}</p>
        </div>
      )}

      {/* Create New Profile Form */}
      {isCreating && (
        <div className="mx-6 mt-4 p-4 bg-slate-950/60 border border-indigo-500/30 rounded-lg">
          <h3 className="text-xs font-bold text-indigo-400 mb-3 flex items-center gap-2">
            <Plus className="w-3.5 h-3.5" />
            Create New Profile
          </h3>
          <div className="space-y-3">
            <div>
              <label className="text-[10px] font-mono text-slate-500 block mb-1">Profile Name *</label>
              <input
                type="text"
                value={newProfileName}
                onChange={(e) => setNewProfileName(e.target.value)}
                placeholder="e.g., Honkai Star Rail"
                className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-sm text-slate-200 outline-none focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="text-[10px] font-mono text-slate-500 block mb-1">Game Name (optional)</label>
              <input
                type="text"
                value={newProfileGame}
                onChange={(e) => setNewProfileGame(e.target.value)}
                placeholder="e.g., Honkai: Star Rail"
                className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-sm text-slate-200 outline-none focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="text-[10px] font-mono text-slate-500 block mb-1">Package Name (optional)</label>
              <input
                type="text"
                value={newProfilePackage}
                onChange={(e) => setNewProfilePackage(e.target.value)}
                placeholder="e.g., com.miHoYo.hkrpgoversea"
                className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-sm text-slate-200 outline-none focus:border-indigo-500"
              />
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={handleCreate}
                disabled={!newProfileName.trim()}
                className="px-4 py-2 text-[10px] font-bold font-mono uppercase bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                Create
              </button>
              <button
                onClick={() => { setIsCreating(false); setNewProfileName(''); setNewProfilePackage(''); setNewProfileGame(''); }}
                className="px-4 py-2 text-[10px] font-bold font-mono uppercase bg-slate-800/40 hover:bg-slate-800/60 border border-slate-700/50 text-slate-400 rounded-lg transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Search & Sort Bar */}
      <div className="px-6 py-3 border-b border-slate-800/40 flex items-center gap-3">
        <div className="flex-1 relative">
          <Search className="w-3.5 h-3.5 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search profiles..."
            className="w-full bg-slate-900/60 border border-slate-800/60 rounded-lg pl-9 pr-3 py-1.5 text-xs text-slate-200 outline-none focus:border-indigo-500/50"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 text-slate-500 hover:text-slate-300"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
        <div className="relative">
          <button
            onClick={() => setShowSortMenu(!showSortMenu)}
            className="px-3 py-1.5 text-[10px] font-mono text-slate-400 bg-slate-900/60 border border-slate-800/60 rounded-lg flex items-center gap-1.5 hover:bg-slate-800/60 transition-colors"
          >
            Sort: {sortBy === 'name' ? 'Name' : sortBy === 'recent' ? 'Recent' : 'Buttons'}
            {showSortMenu ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
          </button>
          {showSortMenu && (
            <div className="absolute top-full right-0 mt-1 w-36 bg-slate-900 border border-slate-700 rounded-lg shadow-xl z-10 overflow-hidden">
              {(['recent', 'name', 'buttons'] as const).map(option => (
                <button
                  key={option}
                  onClick={() => { setSortBy(option); setShowSortMenu(false); }}
                  className={`w-full px-3 py-2 text-[10px] font-mono text-left transition-colors ${
                    sortBy === option ? 'bg-indigo-950/40 text-indigo-400' : 'text-slate-400 hover:bg-slate-800 hover:text-slate-200'
                  }`}
                >
                  {option === 'name' ? 'By Name' : option === 'recent' ? 'By Recent' : 'By Buttons'}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Profile List */}
      <div className="p-6 space-y-3 max-h-[500px] overflow-y-auto">
        {filteredProfiles.length === 0 ? (
          <div className="text-center py-12">
            <Gamepad2 className="w-12 h-12 text-slate-700 mx-auto mb-3" />
            <p className="text-sm text-slate-500">
              {searchQuery ? 'No profiles match your search' : 'No profiles yet'}
            </p>
            <p className="text-[10px] text-slate-600 mt-1">
              {searchQuery ? 'Try a different search term' : 'Create your first profile to get started'}
            </p>
          </div>
        ) : (
          filteredProfiles.map(profile => (
            <ProfileCard
              key={profile.id}
              profile={profile}
              isActive={activeProfileId === profile.id}
              isEditing={editingProfileId === profile.id}
              editName={editingName}
              onSelect={() => onProfileSelect(profile.id)}
              onStartRename={() => handleStartRename(profile)}
              onSaveRename={() => handleSaveRename(profile.id)}
              onCancelRename={handleCancelRename}
              onEditNameChange={setEditingName}
              onDuplicate={() => handleDuplicate(profile)}
              onExport={() => handleExport(profile)}
              onDelete={() => handleDelete(profile.id)}
            />
          ))
        )}
      </div>

      {/* FIX BUG-M01: Custom Confirmation Dialog */}
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        title={confirmDialog.title}
        message={confirmDialog.message}
        variant={confirmDialog.variant}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={confirmDialog.onConfirm}
        onCancel={() => setConfirmDialog(prev => ({ ...prev, isOpen: false }))}
      />
    </div>
  );
}
