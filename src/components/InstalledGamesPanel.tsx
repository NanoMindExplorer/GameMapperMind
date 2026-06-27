/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 *
 * InstalledGamesPanel — shows all installed games on device + lets user launch them.
 * Also lets user create a profile for a game that doesn't have one yet.
 */

import React from 'react';
import { Capacitor } from '@capacitor/core';
import { Play, Search, RefreshCw, Plus, AlertTriangle, Gamepad2, Loader2 } from 'lucide-react';
import InstalledGames, { InstalledGame } from '../plugins/InstalledGames';
import { INITIAL_PROFILES } from '../defaults';
import { GamepadProfile } from '../types';

interface InstalledGamesPanelProps {
  onLogMessage: (msg: string) => void;
  profiles: GamepadProfile[];
  onProfileSelect: (id: string) => void;
  onCreateProfile: (profile: GamepadProfile) => void;
}

export default function InstalledGamesPanel({ onLogMessage, profiles, onProfileSelect, onCreateProfile }: InstalledGamesPanelProps) {
  const [games, setGames] = React.useState<InstalledGame[]>([]);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [searchQuery, setSearchQuery] = React.useState('');
  const [showAllApps, setShowAllApps] = React.useState(false);
  const [launchingPkg, setLaunchingPkg] = React.useState<string | null>(null);

  const loadGames = async () => {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') {
      setError('Hanya tersedia di Android native.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      // Both APIs return a 'count' field; one returns 'games', the other returns 'apps'.
      // Normalize here so the union type doesn't confuse TypeScript.
      const result = showAllApps
        ? await InstalledGames.listAllUserApps()
        : await InstalledGames.listInstalledGames();
      const list: InstalledGame[] = showAllApps
        ? (result as { apps: InstalledGame[] }).apps
        : (result as { games: InstalledGame[] }).games;
      setGames(list || []);
      onLogMessage(`[GAMES] Loaded ${result.count} ${showAllApps ? 'apps' : 'games'} from device.`);
    } catch (e: any) {
      setError(e.message || String(e));
      onLogMessage(`[GAMES ERROR] ${e.message || e}`);
    } finally {
      setLoading(false);
    }
  };

  React.useEffect(() => {
    loadGames();
  }, [showAllApps]);

  const handleLaunch = async (game: InstalledGame) => {
    // BUG-HIGH-16 FIX: Auto-switch profile based on packageName before launching.
    const matchingProfile = profiles.find(p => p.packageName === game.packageName);
    if (matchingProfile) {
      onProfileSelect(matchingProfile.id);
      onLogMessage(`[PROFILE] Auto-switched ke "${matchingProfile.name}" untuk ${game.name}.`);
    } else {
      onLogMessage(`[PROFILE] Tidak ada profil untuk ${game.name}. Buat profil terlebih dahulu.`);
    }

    setLaunchingPkg(game.packageName);
    try {
      await InstalledGames.launchApp({ packageName: game.packageName });
      onLogMessage(`[LAUNCH] ${game.name} started.`);
    } catch (e: any) {
      onLogMessage(`[LAUNCH ERROR] ${game.name}: ${e.message || e}`);
    } finally {
      setTimeout(() => setLaunchingPkg(null), 1500);
    }
  };

  const handleCreateProfileForGame = (game: InstalledGame) => {
    // Check if profile already exists for this package
    const existing = profiles.find(p => p.packageName === game.packageName);
    if (existing) {
      onProfileSelect(existing.id);
      onLogMessage(`[PROFILE] Existing profile found for ${game.name}, activated.`);
      return;
    }
    // Create a new blank profile for this game
    const newId = `custom_${Date.now()}`;
    const newProfile: GamepadProfile = {
      id: newId,
      name: game.name,
      packageName: game.packageName,
      description: `Profile for ${game.name}`,
      gyroSensitivity: 1.0,
      deadzone: 0.15,
      smoothing: 0.3,
      isCustom: true,
      buttons: [],
      antiBanEnabled: false,
    };
    onCreateProfile(newProfile);
    onProfileSelect(newId);
    onLogMessage(`[PROFILE] Created new profile for ${game.name} (${game.packageName}).`);
  };

  // Filter by search
  const filteredGames = games.filter(g =>
    !searchQuery ||
    g.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    g.packageName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  // Show eFootball default profile hint if user is searching for it
  const efootballDefault = INITIAL_PROFILES.find(p => p.id === 'efootball');

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-2xl">
      {/* Header */}
      <div className="bg-slate-950 px-6 py-4 border-b border-slate-800 flex flex-wrap justify-between items-center gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-emerald-500/10 text-emerald-400 rounded-lg border border-emerald-500/20">
            <Gamepad2 className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-100">Installed Games</h2>
            <p className="text-xs text-slate-400">Tap to launch — long-press to create profile</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowAllApps(!showAllApps)}
            className={`px-3 py-1.5 text-xs font-bold rounded border transition-colors ${
              showAllApps
                ? 'bg-amber-900/30 border-amber-700 text-amber-300'
                : 'bg-slate-900 border-slate-700 text-slate-400 hover:text-slate-200'
            }`}
            title="Toggle between game-only list and all user apps"
          >
            {showAllApps ? 'Show Games Only' : 'Show All Apps'}
          </button>
          <button
            onClick={loadGames}
            disabled={loading}
            className="p-2 rounded border border-slate-700 text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors disabled:opacity-50"
            title="Refresh list"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
          </button>
        </div>
      </div>

      {/* Search bar */}
      <div className="px-6 py-3 bg-slate-900/50 border-b border-slate-800">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search game by name or package..."
            className="w-full bg-slate-950 border border-slate-700 rounded pl-9 pr-3 py-2 text-xs text-slate-200 outline-none focus:border-emerald-500"
          />
        </div>
      </div>

      {/* Body */}
      <div className="p-6">
        {error && (
          <div className="bg-rose-950/40 border border-rose-700/50 rounded p-4 text-rose-200 text-xs flex items-center gap-2">
            <AlertTriangle className="w-4 h-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {!error && loading && games.length === 0 && (
          <div className="text-center py-12 text-slate-500 text-xs flex items-center justify-center gap-2">
            <Loader2 className="w-4 h-4 animate-spin" />
            <span>Scanning installed apps...</span>
          </div>
        )}

        {!error && !loading && filteredGames.length === 0 && (
          <div className="text-center py-12 text-slate-500 text-xs">
            <Gamepad2 className="w-8 h-8 mx-auto mb-2 opacity-50" />
            <p>{searchQuery ? 'No games match your search.' : 'No games detected on device.'}</p>
            {!showAllApps && (
              <button
                onClick={() => setShowAllApps(true)}
                className="mt-3 text-emerald-400 underline text-xs"
              >
                Show all installed apps instead
              </button>
            )}
          </div>
        )}

        {filteredGames.length > 0 && (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
            {filteredGames.map((game) => {
              const hasProfile = profiles.some(p => p.packageName === game.packageName);
              const isLaunching = launchingPkg === game.packageName;
              return (
                <div
                  key={game.packageName}
                  className="bg-slate-950/60 border border-slate-800 rounded-lg p-3 flex flex-col items-center gap-2 hover:border-emerald-600/60 hover:bg-slate-950 transition-all group"
                >
                  {/* Icon */}
                  <div className="w-14 h-14 rounded-lg overflow-hidden bg-slate-900 border border-slate-700 flex items-center justify-center">
                    {game.iconBase64 ? (
                      <img
                        src={`data:image/png;base64,${game.iconBase64}`}
                        alt={game.name}
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <Gamepad2 className="w-6 h-6 text-slate-600" />
                    )}
                  </div>
                  {/* Name */}
                  <div className="w-full text-center">
                    <div className="text-xs font-bold text-slate-200 truncate" title={game.name}>
                      {game.name}
                    </div>
                    <div className="text-[9px] font-mono text-slate-500 truncate" title={game.packageName}>
                      {game.packageName}
                    </div>
                  </div>
                  {/* Status badge */}
                  {hasProfile && (
                    <span className="text-[8px] font-bold px-1.5 py-0.5 rounded bg-emerald-900/40 text-emerald-400 border border-emerald-700/40 uppercase tracking-wider">
                      Profile Ready
                    </span>
                  )}
                  {/* Actions */}
                  <div className="w-full flex gap-1 mt-auto">
                    <button
                      onClick={() => handleLaunch(game)}
                      disabled={isLaunching}
                      className="flex-1 flex items-center justify-center gap-1 py-1.5 bg-emerald-600 hover:bg-emerald-500 disabled:bg-slate-800 disabled:text-slate-500 text-white text-[10px] font-bold rounded transition-colors"
                    >
                      {isLaunching ? (
                        <Loader2 className="w-3 h-3 animate-spin" />
                      ) : (
                        <Play className="w-3 h-3 fill-white" />
                      )}
                      {isLaunching ? 'Starting...' : 'Play'}
                    </button>
                    <button
                      onClick={() => handleCreateProfileForGame(game)}
                      className="p-1.5 bg-slate-800 hover:bg-indigo-700 text-slate-400 hover:text-white rounded transition-colors"
                      title={hasProfile ? 'Open existing profile' : 'Create new profile for this game'}
                    >
                      <Plus className="w-3 h-3" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {/* eFootball quick hint */}
        {efootballDefault && searchQuery.toLowerCase().includes('efootball') && (
          <div className="mt-4 p-3 bg-indigo-950/40 border border-indigo-700/40 rounded text-xs text-indigo-200">
            <strong>Tip:</strong> eFootball 2026 (jp.konami.pesam) has a built-in default profile.
            Go to <em>Profiles tab → select "eFootball"</em> to activate it. The default profile has
            11 button mappings (Pass/Shoot/Sprint/Through/Lob/Skill/Switch/Tactics/Pause + L-stick + R-stick).
          </div>
        )}
      </div>
    </div>
  );
}
