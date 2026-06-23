import { useGamepad } from "../hooks/useGamepad";

export function GamepadStatusBadge() {
  const gamepad = useGamepad();
  return (
    <div className={`p-2 rounded text-xs font-mono font-bold flex items-center gap-2 ${gamepad ? 'bg-emerald-950/50 text-emerald-400 border border-emerald-500/50' : 'bg-slate-900 border border-slate-800 text-slate-500'}`}>
      <span className={`w-2 h-2 rounded-full ${gamepad ? 'bg-emerald-400 animate-pulse' : 'bg-slate-500'}`} />
      {gamepad ? `🎮 ${gamepad.id.split("(")[0].trim()}` : "⚠️ Web Gamepad: DISCONNECTED"}
    </div>
  );
}
