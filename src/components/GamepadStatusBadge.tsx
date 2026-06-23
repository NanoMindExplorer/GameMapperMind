import { useGamepad } from "../hooks/useGamepad";

export function GamepadStatusBadge() {
  const gamepads = useGamepad();
  const gamepad = gamepads.length > 0 ? gamepads[0] : null;
  return (
    <div className={`p-2 rounded text-xs font-mono font-bold flex items-center gap-2 ${gamepad ? 'bg-emerald-950/50 text-emerald-400 border border-emerald-500/50' : 'bg-slate-900 border border-slate-800 text-slate-500'}`}>
      <span className={`w-2 h-2 rounded-full ${gamepad ? 'bg-emerald-400 animate-pulse' : 'bg-slate-500'}`} />
      {gamepad ? `🎮 ${gamepads.length > 1 ? gamepads.length + ' Gamepads' : gamepad.id.split("(")[0].trim()}` : "⚠️ Web Gamepad: DISCONNECTED"}
    </div>
  );
}
