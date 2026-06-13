import React from 'react';
import { 
  Cpu, Terminal, Zap, Sparkles, Code, Play, RefreshCw, AlertCircle, 
  HelpCircle, Eye, ShieldAlert, CheckCircle, Info, Copy, Check, Power, Sliders, PlayCircle,
  Target, Flag, ListTodo, Edit3
} from 'lucide-react';
import { AITunnelState } from '../types';

interface AITunnelPanelProps {
  onLogMessage: (msg: string) => void;
}

export default function AITunnelPanel({ onLogMessage }: AITunnelPanelProps) {
  const [tunnelState, setTunnelState] = React.useState<AITunnelState>({
    isEnabled: false,
    activeAgent: "vlm_gemini",
    tunnelStatus: 'WAITING_FOR_CLIENT',
    clientIp: '192.168.1.104',
    apiToken: 'NX-9981-GEMINI-TUNNEL',
    responseDelayMs: 64,
    confidenceScore: 0.95,
    totalModelCommandsExecuted: 142,
    allowAutonomousTap: true,
    allowMacroTriggers: true,
    logs: [
      "[AI-TUNNEL] Listening spawned on port 3000 at /api/ai/* secure bindings.",
      "[AI-TUNNEL] Authorization token successfully generated: NX-9981-GEMINI-TUNNEL",
      "[AI-TUNNEL] Waiting for VLM agent websocket or direct REST API handshake...",
      "[SYSTEM] AI Copilot Ready. Access parameters using correct header authentication."
    ]
  });

  const [isLoading, setIsLoading] = React.useState(false);
  const [copiedToken, setCopiedToken] = React.useState(false);
  const [copiedCurl, setCopiedCurl] = React.useState<string | null>(null);
  const [selectedLanguage, setSelectedLanguage] = React.useState<'curl' | 'python' | 'nodejs'>('curl');

  // Scenario states
  const [selectedScenario, setSelectedScenario] = React.useState<'harian_quest' | 'boss_dodge' | 'farm_ore' | 'custom_scenario'>('harian_quest');
  const [customPrompt, setCustomPrompt] = React.useState<string>("Jelajahi daerah tebing selatan, kumpulkan material buah liar dan hindari patroli penjaga.");
  const [customGoal, setCustomGoal] = React.useState<string>("Kumpulkan 3 buah hias (Sunsettia/Berry)");
  const [progressCounters, setProgressCounters] = React.useState<Record<string, number>>({
    harian_quest: 1,
    boss_dodge: 1,
    farm_ore: 3,
    custom_scenario: 0
  });

  // Fetch status of the AI Tunnel
  const fetchTunnelStatus = async () => {
    try {
      const res = await fetch('/api/ai/tunnel-status');
      if (res.ok) {
        const data = await res.json();
        setTunnelState(data);
      }
    } catch (err) {
      console.error("Failed to fetch tunnel status", err);
    }
  };

  React.useEffect(() => {
    fetchTunnelStatus();
    const interval = setInterval(fetchTunnelStatus, 2500);
    return () => clearInterval(interval);
  }, []);

  const handleToggleEnable = async () => {
    setIsLoading(true);
    try {
      const res = await fetch('/api/ai/tunnel-control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ isEnabled: !tunnelState.isEnabled })
      });
      if (res.ok) {
        const data = await res.json();
        setTunnelState(data);
        onLogMessage(`AI Tunnel status updated: ${data.isEnabled ? 'ENABLED' : 'DISABLED'}`);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleUpdateAgent = async (agent: 'vision_agent' | 'vlm_gemini' | 'reinforcement_rl') => {
    try {
      const res = await fetch('/api/ai/tunnel-control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ activeAgent: agent })
      });
      if (res.ok) {
        const data = await res.json();
        setTunnelState(data);
        onLogMessage(`AI Agent target changed to: ${agent}`);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleUpdatePermission = async (field: 'allowAutonomousTap' | 'allowMacroTriggers', val: boolean) => {
    try {
      const res = await fetch('/api/ai/tunnel-control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [field]: val })
      });
      if (res.ok) {
        const data = await res.json();
        setTunnelState(data);
        onLogMessage(`AI Tunnel security permission updated: ${field} = ${val}`);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleTriggerSimulation = async () => {
    if (!tunnelState.isEnabled) return;
    setIsLoading(true);
    try {
      const res = await fetch('/api/ai/sim-vision', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          scenarioId: selectedScenario,
          customPrompt: selectedScenario === 'custom_scenario' ? customPrompt : undefined,
          customGoal: selectedScenario === 'custom_scenario' ? customGoal : undefined
        })
      });
      if (res.ok) {
        const data = await res.json();
        setTunnelState(data);
        
        // Simbiotically update scenario success progression
        setProgressCounters(prev => {
          const maxes: Record<string, number> = { harian_quest: 4, boss_dodge: 5, farm_ore: 10, custom_scenario: 3 };
          const activeMax = maxes[selectedScenario];
          const curr = prev[selectedScenario] || 0;
          if (curr < activeMax) {
            const nextVal = curr + 1;
            onLogMessage(`[AI TASK PROGRESS] Skenario Otonom '${selectedScenario}' bertambah: ${nextVal}/${activeMax}`);
            return {
              ...prev,
              [selectedScenario]: nextVal
            };
          } else {
            onLogMessage(`[AI SUCCESS] Target skenario '${selectedScenario}' telah tercapai 100%! 🎉`);
            return prev;
          }
        });
      }
    } catch (err) {
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  };

  const copyToClipboard = (text: string, type: 'token' | 'curl' | 'python' | 'node') => {
    navigator.clipboard.writeText(text);
    if (type === 'token') {
      setCopiedToken(true);
      setTimeout(() => setCopiedToken(false), 2000);
    } else {
      setCopiedCurl(type);
      setTimeout(() => setCopiedCurl(null), 2000);
    }
  };

  const codeSnippets = {
    curl: `curl -X POST \\
  \${window.location.origin}/api/ai/input \\
  -H "Content-Type: application/json" \\
  -d '{
    "token": "${tunnelState.apiToken}",
    "command": "tap",
    "params": { "x": 640, "y": 480 }
  }'`,
    python: `import requests

url = "\${window.location.origin}/api/ai/input"
headers = {"Content-Type": "application/json"}
payload = {
    "token": "${tunnelState.apiToken}",
    "command": "drag",
    "params": {
        "fromX": 200, "fromY": 500,
        "toX": 350, "toY": 500
    }
}

response = requests.post(url, json=payload, headers=headers)
print(response.json())`,
    nodejs: `const fetch = require('node-fetch');

async function sendInput() {
  const response = await fetch('\${window.location.origin}/api/ai/input', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      token: "${tunnelState.apiToken}",
      command: "press_button",
      params: { "key": "BUTTON_A" }
    })
  });
  const data = await response.json();
  console.log(data);
}
sendInput();`
  };

  // Replace ${window.location.origin} with real domain or fallback
  const getRenderedCode = (rawCode: string) => {
    const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost:3000';
    return rawCode.replace(/\${window\.location\.origin}/g, origin);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 animate-fade-in">
      {/* LEFT COLUMN: Controls, Status, Token */}
      <div className="lg:col-span-7 flex flex-col gap-6">
        
        {/* Core Status Block */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className={`p-2 rounded-lg bg-pink-500/10 border text-pink-400 ${
                tunnelState.isEnabled ? 'border-pink-500/30' : 'border-slate-800'
              }`}>
                <Cpu className={`w-5 h-5 ${tunnelState.isEnabled ? 'animate-pulse' : ''}`} />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-sm font-bold text-slate-100 font-sans tracking-tight">AI Orchestration Agent Tunnel</h3>
                  <span className={`text-[8px] font-mono font-bold uppercase px-1.5 py-0.5 rounded border ${
                    tunnelState.isEnabled 
                      ? 'bg-pink-950/40 text-pink-400 border-pink-900/60' 
                      : 'bg-slate-950 text-slate-500 border-slate-800'
                  }`}>
                    {tunnelState.isEnabled ? 'Active Channel' : 'Offline'}
                  </span>
                </div>
                <p className="text-[10px] text-slate-400 mt-0.5">Automate touches, joystick sweeps, or calibration directly via external models</p>
              </div>
            </div>

            {/* Quick Trigger Power Button */}
            <button
              onClick={handleToggleEnable}
              disabled={isLoading}
              className={`px-4 py-2 text-xs font-bold rounded-lg flex items-center gap-2 border transition-all active:scale-[0.98] ${
                tunnelState.isEnabled
                  ? 'bg-gradient-to-r from-red-600 to-amber-600 text-white border-red-500/20 shadow-md shadow-red-500/5 hover:from-red-500 hover:to-amber-500'
                  : 'bg-gradient-to-r from-indigo-600 to-purple-600 text-white border-indigo-500/20 shadow-md shadow-indigo-500/5 hover:from-indigo-500 hover:to-purple-500'
              }`}
            >
              <Power className="w-3.5 h-3.5" />
              {tunnelState.isEnabled ? 'NONAKTIFKAN TUNNEL' : 'AKTIFKAN TUNNEL AI'}
            </button>
          </div>

          {/* Connected state & info cards */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-2">
            <div className="bg-slate-950 border border-slate-900 rounded-lg p-3 space-y-1">
              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-widest block">Status Terakhir</span>
              <div className="flex items-center gap-1.5">
                <span className={`w-2 h-2 rounded-full ${
                  !tunnelState.isEnabled ? 'bg-slate-700' :
                  tunnelState.tunnelStatus === 'AUTOPILOT_DRIVING' ? 'bg-amber-400 animate-ping' : 'bg-indigo-400 animate-pulse'
                }`} />
                <span className="text-xs font-bold font-mono text-slate-200">
                  {!tunnelState.isEnabled ? 'DISENGAGED' : tunnelState.tunnelStatus}
                </span>
              </div>
            </div>
            
            <div className="bg-slate-950 border border-slate-900 rounded-lg p-3 space-y-1">
              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-widest block">Model Respon</span>
              <div className="flex items-center gap-1">
                <Zap className="w-3.5 h-3.5 text-indigo-400" />
                <span className="text-xs font-bold font-mono text-indigo-300">
                  {tunnelState.isEnabled ? `${tunnelState.responseDelayMs}ms latency` : 'N/A'}
                </span>
              </div>
            </div>

            <div className="bg-slate-950 border border-slate-900 rounded-lg p-3 space-y-1">
              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-widest block">Komando Terkirim</span>
              <div className="flex items-center gap-1">
                <Sparkles className="w-3.5 h-3.5 text-pink-400" />
                <span className="text-xs font-bold font-mono text-pink-300">
                  {tunnelState.totalModelCommandsExecuted} signals
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Configurations selector */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-5">
          <div className="flex items-center gap-2">
            <Sliders className="w-4 h-4 text-indigo-400" />
            <h4 className="text-xs font-bold text-slate-200 uppercase tracking-wider font-sans">Setelan Strategi & Keamanan AI</h4>
          </div>

          <div className="space-y-4">
            {/* Agent type */}
            <div className="space-y-2">
              <label className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">Metode Agen Autopilot</label>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                {[
                  { id: 'vlm_gemini', name: 'Gemini VLM', desc: 'Analisis visual video frame & direct tactile execution.' },
                  { id: 'vision_agent', name: 'YOLO Vision Feed', desc: 'Gunakan detektor target koordinat bounding boxes.' },
                  { id: 'reinforcement_rl', name: 'DQL Reinforcement', desc: 'Strategi feedback iteratif berlandaskan status game.' }
                ].map(agent => (
                  <button
                    key={agent.id}
                    onClick={() => handleUpdateAgent(agent.id as any)}
                    className={`p-3 rounded-lg border text-left transition-all relative overflow-hidden ${
                      tunnelState.activeAgent === agent.id
                        ? 'border-indigo-500/60 bg-indigo-950/20 text-indigo-300 shadow-md'
                        : 'border-slate-800/80 bg-slate-950/30 hover:border-slate-700 hover:bg-slate-950/60 text-slate-400'
                    }`}
                  >
                    <span className="text-xs font-bold block">{agent.name}</span>
                    <span className="text-[9px] block leading-normal mt-1 opacity-80">{agent.desc}</span>
                    {tunnelState.activeAgent === agent.id && (
                      <div className="absolute top-1 right-1">
                        <Check className="w-3.5 h-3.5 text-indigo-400" />
                      </div>
                    )}
                  </button>
                ))}
              </div>
            </div>

            {/* Security controls */}
            <div className="p-3 bg-slate-950 border border-slate-900/80 rounded-lg space-y-3.5">
              <span className="text-[10px] uppercase font-bold text-slate-400 tracking-wider flex items-center gap-1">
                <ShieldAlert className="w-3.5 h-3.5 text-amber-500" />
                Izin Keamanan Input Driver
              </span>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="flex items-center justify-between">
                  <div>
                    <span className="text-xs font-medium text-slate-200 block">Izin Touch Otomatis</span>
                    <span className="text-[9px] text-slate-500 leading-normal block">Izinkan AI menyentuh/menggeser area layar secara otonom</span>
                  </div>
                  <button
                    onClick={() => handleUpdatePermission('allowAutonomousTap', !tunnelState.allowAutonomousTap)}
                    className={`relative inline-flex h-5 w-10 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${
                      tunnelState.allowAutonomousTap ? 'bg-indigo-600' : 'bg-slate-800'
                    }`}
                  >
                    <span className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                      tunnelState.allowAutonomousTap ? 'translate-x-5' : 'translate-x-0'
                    }`} />
                  </button>
                </div>

                <div className="flex items-center justify-between">
                  <div>
                    <span className="text-xs font-medium text-slate-200 block">Izin Eksekusi Makro</span>
                    <span className="text-[9px] text-slate-500 leading-normal block">Izinkan AI mentrigger rangkaian makro game terdaftar</span>
                  </div>
                  <button
                    onClick={() => handleUpdatePermission('allowMacroTriggers', !tunnelState.allowMacroTriggers)}
                    className={`relative inline-flex h-5 w-10 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${
                      tunnelState.allowMacroTriggers ? 'bg-indigo-600' : 'bg-slate-800'
                    }`}
                  >
                    <span className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                      tunnelState.allowMacroTriggers ? 'translate-x-5' : 'translate-x-0'
                    }`} />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Dynamic Scenario & Target Planner */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Target className="w-4 h-4 text-pink-400" />
              <h4 className="text-xs font-bold text-slate-200 uppercase tracking-wider font-sans">Perencana Skenario & Target Insting AI</h4>
            </div>
            <span className="text-[9px] bg-pink-950/40 text-pink-400 border border-pink-900/60 px-2 py-0.5 rounded font-mono font-bold uppercase">
              STRATEGIS
            </span>
          </div>

          <p className="text-[11px] text-slate-400 leading-normal">
            Pilih skenario aktivitas game terarah atau formulasikan instruksi taktis kustom Anda sendiri. AI Copilot VLM akan memproses visual layar secara otonom berlandaskan target skenario aktif.
          </p>

          {/* Preset Buttons Grid */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
            {[
              {
                id: 'harian_quest',
                title: 'Daily Commissions / Quest Harian',
                desc: 'Mencari tanda seru quest, navigasi jalan otomatis, klaim reward NPC.',
                goal: '4 Harian Commissions selesai',
                max: 4
              },
              {
                id: 'boss_dodge',
                title: 'Boss Battle - Dodging Priority',
                desc: 'Deteksi radius visual AoE berbahaya, prioritaskan menghindar daripada serang.',
                goal: '5x Dodge sukses tanpa terkena hit',
                max: 5
              },
              {
                id: 'farm_ore',
                title: 'Mining Material & Ore Farmer',
                desc: 'Cari letak bongkahan kristal Magic Ore terdekat, pukul, lalu pungut otomatis.',
                goal: 'Kumpulkan 10 Magic Crystals',
                max: 10
              },
              {
                id: 'custom_scenario',
                title: 'Skenario Mandiri / Instruksi Kustom',
                desc: 'Formasikan keinginan taktis, navigasi, atau tindakan unik tersendiri bagi visi AI.',
                goal: customGoal,
                max: 3
              },
            ].map(scen => {
              const currentProgress = progressCounters[scen.id] || 0;
              const isFinished = currentProgress >= scen.max;
              const isSelected = selectedScenario === scen.id;

              return (
                <button
                  key={scen.id}
                  onClick={() => setSelectedScenario(scen.id as any)}
                  className={`p-3 rounded-lg border text-left transition-all flex flex-col justify-between h-auto relative overflow-hidden ${
                    isSelected
                      ? 'border-pink-500/50 bg-pink-950/15 text-slate-200 shadow-md shadow-pink-500/5'
                      : 'border-slate-800 bg-slate-950/20 text-slate-400 hover:border-slate-700/80 hover:bg-slate-950/40'
                  }`}
                >
                  <div className="space-y-1">
                    <span className="text-xs font-bold block text-slate-200">{scen.title}</span>
                    <p className="text-[10px] leading-normal opacity-85">{scen.desc}</p>
                  </div>

                  {/* Goal and progression feedback inside each card */}
                  <div className="mt-3 pt-2 border-t border-slate-900/45 flex items-center justify-between text-[9px] w-full">
                    <span className="text-emerald-400/90 flex items-center gap-1">
                      <Flag className="w-2.5 h-2.5" /> {scen.id === 'custom_scenario' ? customGoal : scen.goal}
                    </span>
                    <span className={`font-mono font-bold px-1.5 py-0.2 rounded ${
                      isFinished ? 'bg-emerald-950 text-emerald-400 border border-emerald-900/50' : 'bg-slate-950 text-slate-300'
                    }`}>
                      {currentProgress}/{scen.max}
                    </span>
                  </div>

                  {isSelected && (
                    <div className="absolute top-1.5 right-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-pink-400 inline-block animate-pulse"></span>
                    </div>
                  )}
                </button>
              );
            })}
          </div>

          {/* Conditional Input Fields for Custom Scenario */}
          {selectedScenario === 'custom_scenario' && (
            <div className="p-3 bg-slate-950 border border-slate-900 rounded-lg space-y-3 animate-fade-in">
              <div className="flex items-center gap-1.5 text-[10px] uppercase font-bold text-slate-200">
                <Edit3 className="w-3.5 h-3.5 text-pink-400" />
                Dikte Taktis Kustom Anda (Prompter)
              </div>

              <div className="space-y-2.5">
                <div className="space-y-1">
                  <label className="text-[9px] font-mono uppercase text-slate-400 tracking-wider">Apa yang harus diinstruksikan pada AI?</label>
                  <textarea
                    value={customPrompt}
                    onChange={(e) => setCustomPrompt(e.target.value)}
                    placeholder="Contoh: Tangkap ikan di pesisir sungai, hindari arus air deras..."
                    className="w-full bg-slate-900 border border-slate-800 rounded px-2.5 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-pink-500/55 h-16 resize-none leading-normal font-sans"
                  />
                </div>

                <div className="space-y-1">
                  <label className="text-[9px] font-mono uppercase text-slate-400 tracking-wider">Target Capaian (Goals)?</label>
                  <input
                    type="text"
                    value={customGoal}
                    onChange={(e) => setCustomGoal(e.target.value)}
                    placeholder="Contoh: Kumpulkan 3 Ikan mas koki"
                    className="w-full bg-slate-900 border border-slate-800 rounded px-2.5 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-pink-500/55"
                  />
                </div>
              </div>
            </div>
          )}

          {/* Active Milestone Status Tracker Banner */}
          <div className="bg-slate-950 border border-slate-900 p-3 rounded-lg flex items-center justify-between">
            <div className="flex items-center gap-2">
              <ListTodo className="w-3.5 h-3.5 text-indigo-400" />
              <div>
                <span className="text-[10px] font-bold text-slate-300 block uppercase">Fokus Misi Insting AI</span>
                <span className="text-[10px] text-slate-400 block max-w-[280px] truncate leading-tight">
                  {selectedScenario === 'harian_quest' && 'Menelusuri peta petualangan & otomatisasi reward'}
                  {selectedScenario === 'boss_dodge' && 'Menjaga prioritas dodging frame visual'}
                  {selectedScenario === 'farm_ore' && 'Mendekati Magic Crystal Ore koordinat'}
                  {selectedScenario === 'custom_scenario' && `Instruksi: "${customPrompt}"`}
                </span>
              </div>
            </div>

            {/* Overall current status badge */}
            <div>
              {(() => {
                const maxes: Record<string, number> = { harian_quest: 4, boss_dodge: 5, farm_ore: 10, custom_scenario: 3 };
                const currentProgress = progressCounters[selectedScenario] || 0;
                const activeMax = maxes[selectedScenario];
                const isFinished = currentProgress >= activeMax;

                return (
                  <span className={`text-[9px] font-mono font-bold px-2 py-0.5 rounded border uppercase flex items-center gap-1 ${
                    isFinished 
                      ? 'bg-emerald-950/80 text-emerald-400 border-emerald-900/60' 
                      : 'bg-indigo-950/80 text-indigo-300 border-indigo-900/60 animate-pulse'
                  }`}>
                    {isFinished ? (
                      <>
                        <CheckCircle className="w-3 h-3 text-emerald-400" /> TARGET TERCAPAI
                      </>
                    ) : (
                      <>
                        <RefreshCw className="w-2.5 h-2.5 text-indigo-400 animate-spin" /> PROSES OTONOM
                      </>
                    )}
                  </span>
                );
              })()}
            </div>
          </div>
        </div>

        {/* API Token and copy credentials */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Code className="w-4 h-4 text-emerald-400" />
              <h4 className="text-xs font-bold text-slate-200 uppercase tracking-wider font-sans">Kredensial Integrasi Pembuat Script</h4>
            </div>
            <span className="text-[9px] bg-emerald-950/50 text-emerald-400 border border-emerald-900 px-2 py-0.5 rounded font-mono">
              AES-Secure Token
            </span>
          </div>

          <p className="text-[11px] text-slate-400 leading-normal">
            Masukkan token di bawah ke dalam file python/script Anda untuk mengizinkan input injeksi secara nirkabel / lokal tanpa hambatan.
          </p>

          <div className="flex items-center gap-2">
            <div className="flex-1 bg-slate-950 border border-slate-900 rounded-lg px-3.5 py-2.5 font-mono text-xs text-slate-200 flex items-center justify-between">
              <span>{tunnelState.apiToken}</span>
              <button
                type="button"
                onClick={() => copyToClipboard(tunnelState.apiToken, 'token')}
                className="text-slate-500 hover:text-slate-300 focus:outline-none"
              >
                {copiedToken ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {/* Quick Code Integration Panel */}
          <div className="bg-slate-950 rounded-lg border border-slate-900 overflow-hidden">
            <div className="flex items-center justify-between px-3.5 py-2 bg-slate-900/55 border-b border-slate-900/70">
              <div className="flex gap-1.5">
                {(['curl', 'python', 'nodejs'] as const).map(lang => (
                  <button
                    key={lang}
                    onClick={() => setSelectedLanguage(lang)}
                    className={`text-[9px] font-bold uppercase px-2 py-1 rounded transition-colors ${
                      selectedLanguage === lang 
                        ? 'bg-slate-800 text-indigo-400 border border-slate-705' 
                        : 'text-slate-500 hover:text-slate-300'
                    }`}
                  >
                    {lang === 'nodejs' ? 'NodeJS' : lang}
                  </button>
                ))}
              </div>

              <button
                onClick={() => copyToClipboard(getRenderedCode(codeSnippets[selectedLanguage]), selectedLanguage)}
                className="text-[9px] font-semibold text-slate-400 hover:text-slate-200 flex items-center gap-1"
              >
                {copiedCurl === selectedLanguage ? (
                  <>
                    <Check className="w-3 h-3 text-emerald-400" /> Copied!
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" /> Salin Code
                  </>
                )}
              </button>
            </div>

            <pre className="p-3 text-[10px] font-mono text-indigo-300 overflow-x-auto leading-relaxed max-h-[170px]">
              <code>{getRenderedCode(codeSnippets[selectedLanguage])}</code>
            </pre>
          </div>
        </div>

      </div>

      {/* RIGHT COLUMN: Interactive Vision Feed & AI simulation terminal */}
      <div className="lg:col-span-5 flex flex-col gap-6">
        
        {/* INTERACTIVE VISION FEED SCREEN */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Eye className="w-4 h-4 text-pink-400" />
              <h4 className="text-xs font-bold text-slate-200 uppercase tracking-wider font-sans">Live VLM Vision Monitor Stream</h4>
            </div>
            <span className="flex h-2 w-2 relative">
              <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
                tunnelState.isEnabled ? 'bg-pink-400' : 'bg-slate-600'
              }`} />
              <span className={`relative inline-flex rounded-full h-2 w-2 ${
                tunnelState.isEnabled ? 'bg-pink-500' : 'bg-slate-600'
              }`} />
            </span>
          </div>

          {/* Canvas Display of Simulation Game */}
          <div className="relative border border-slate-900 bg-slate-950 rounded-xl overflow-hidden aspect-video group">
            {/* Real Game Background Snapshot (Simulated layout) */}
            <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-slate-900 via-slate-950 to-black p-4 flex flex-col justify-between">
              
              {/* HUD Game Simulation Layer */}
              <div className="flex justify-between items-start text-[9px] font-mono text-slate-400">
                <div className="bg-slate-900/80 px-2 py-1 rounded border border-slate-800">
                  <span className="text-emerald-400 font-bold">FPS: 60</span> | LATENCY: 2ms
                </div>
                <div className="bg-slate-900/80 px-2 py-1 rounded border border-slate-800 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 bg-red-500 rounded-full inline-block"></span>
                  <span>ENEMY HP: 100% (BOSS DECAY)</span>
                </div>
              </div>

              {/* Targets Bounding Box overlays (only shown realistic vision cues) */}
              <div className="relative flex-1 flex items-center justify-center">
                {tunnelState.isEnabled ? (
                  <>
                    {/* Boss Target Area Bounding Box */}
                    <div className="absolute top-[20%] left-[30%] w-[40%] h-[50%] border-2 border-red-500/50 bg-red-500/5 rounded-lg flex flex-col items-start p-1 pointer-events-none">
                      <span className="text-[8px] font-bold font-mono text-red-400 bg-red-950/90 border border-red-900 px-1 py-0.1 rounded leading-none">
                        BOSS_LICH_DETECTED [{(tunnelState.confidenceScore * 100).toFixed(0)}%]
                      </span>
                    </div>

                    {/* Joystick Virtual overlay indicator */}
                    <div className="absolute bottom-[10%] left-[10%] w-14 h-14 rounded-full border-2 border-indigo-500/40 bg-indigo-500/5 flex items-center justify-center">
                      <div className="w-4 h-4 rounded-full bg-indigo-400/80 translate-y-[-10px] animate-pulse" />
                    </div>

                    {/* Attack button virtual overlay */}
                    <div className="absolute bottom-[12%] right-[12%] w-10 h-10 rounded-full border-2 border-pink-500/50 bg-pink-500/5 flex items-center justify-center">
                      <span className="text-[8px] font-mono font-bold text-pink-400">TAP INJECT</span>
                    </div>
                  </>
                ) : (
                  <div className="text-center space-y-1">
                    <Info className="w-7 h-7 text-slate-600 mx-auto" />
                    <span className="text-[11px] text-slate-500 block">AI Stream is offline. Enable the tunnel above to activate vision tracking overlays.</span>
                  </div>
                )}
              </div>

              {/* Status info overlay footer */}
              <div className="bg-slate-950/90 border-t border-slate-900 p-2 -mx-4 -mb-4 flex justify-between items-center text-[9px] font-mono">
                <span className="text-slate-400">Resolution: 1920x1080 @ 30 FPS Stream</span>
                <span className="text-pink-400 font-bold">
                  {tunnelState.isEnabled ? `Active model: ${tunnelState.activeAgent.toUpperCase()}` : 'STANDBY'}
                </span>
              </div>
            </div>
            
            {/* Static HUD Overlay grids */}
            <div className="absolute inset-0 border-collapse border-slate-900/30 grid grid-cols-6 grid-rows-6 pointer-events-none">
              {Array.from({ length: 36 }).map((_, i) => (
                <div key={i} className="border border-slate-900/5" />
              ))}
            </div>
          </div>

          {/* AI Simulation activation action */}
          <div className="bg-slate-950 rounded-lg p-3 border border-slate-900 space-y-3">
            <div className="flex items-start gap-2.5">
              <div className="p-1.5 rounded bg-amber-500/10 text-amber-500 border border-amber-500/20 text-xs">
                <AlertCircle className="w-4 h-4" />
              </div>
              <div className="space-y-0.5">
                <h5 className="text-[11px] font-bold text-amber-500">Model Simulation Playground</h5>
                <p className="text-[10px] text-slate-400 leading-relaxed">
                  Tidak memiliki script python? Jangan khawatir! Anda bisa mengklik tombol simulasi otonom di bawah ini untuk mensimulasikan input keputusan VLM langsung dari browser Anda.
                </p>
              </div>
            </div>

            <button
              onClick={handleTriggerSimulation}
              disabled={!tunnelState.isEnabled || isLoading}
              className={`w-full py-2.5 text-xs font-bold rounded-lg border flex items-center justify-center gap-2 transition-all active:scale-[0.99] ${
                !tunnelState.isEnabled 
                  ? 'bg-slate-900 border-slate-800 text-slate-500 cursor-not-allowed'
                  : 'bg-indigo-600/10 border-indigo-500/30 text-indigo-300 hover:bg-indigo-600/20 shadow-md shadow-indigo-600/5'
              }`}
            >
              <PlayCircle className="w-4 h-4 text-indigo-400" />
              SIMULASIKAN KEPUTUSAN TERBIMBING GEMINI
            </button>
          </div>
        </div>

        {/* SECURE TUNNEL LOGS SCREEN */}
        <div className="bg-slate-900/40 border border-slate-900 rounded-xl p-5 space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Terminal className="w-4 h-4 text-indigo-400" />
              <h4 className="text-xs font-bold text-slate-200 uppercase tracking-wider font-sans">Tunnel & Autopilot Syslogs</h4>
            </div>
            <button
              onClick={fetchTunnelStatus}
              className="text-[10px] text-indigo-400 hover:text-indigo-300 font-bold flex items-center gap-1"
            >
              <RefreshCw className="w-3 h-3" /> Refresh Logs
            </button>
          </div>

          <div className="bg-slate-950 rounded-xl border border-slate-900 p-3 h-[290px] font-mono text-[10px] text-slate-300 overflow-y-auto space-y-1.5 flex flex-col-reverse">
            <div className="flex flex-col gap-1.5">
              {tunnelState.logs.slice().reverse().map((log, idx) => {
                let colorClass = "text-slate-400";
                if (log.includes("[AI-TUNNEL]")) colorClass = "text-indigo-300 font-semibold";
                if (log.includes("[SYSTEM]")) colorClass = "text-emerald-400";
                if (log.includes("[COMMAND]")) colorClass = "text-pink-400";
                if (log.includes("[SIM-VLM]")) colorClass = "text-amber-300";
                
                return (
                  <div key={idx} className={`leading-normal border-b border-slate-900/20 pb-1 break-all ${colorClass}`}>
                    {log}
                  </div>
                );
              })}
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}
