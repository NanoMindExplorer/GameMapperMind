export interface GamepadState {
  connected: boolean;
  axes: readonly number[];
  buttons: readonly boolean[];
  id: string;
}

export const BUTTON_NAMES = [
  "A / Cross",
  "B / Circle",
  "X / Square",
  "Y / Triangle",
  "L1 / LB",
  "R1 / RB",
  "L2 / LT",
  "R2 / RT",
  "Select / Back",
  "Start / Forward",
  "L3 / Left Stick",
  "R3 / Right Stick",
  "D-Pad Up",
  "D-Pad Down",
  "D-Pad Left",
  "D-Pad Right",
  "Home / Guide"
];

import { useState, useEffect, useCallback, useRef } from "react";

export function useGamepad() {
  const [state, setState] = useState<GamepadState>({
    connected: false,
    axes: [],
    buttons: [],
    id: ""
  });
  
  const frameRef = useRef<number>(0);

  const pollGamepad = useCallback(() => {
    const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
    let gp: Gamepad | null = null;

    // Prioritaskan gamepad dengan 'standard' mapping (lebih kompatibel)
    for (const g of gamepads) {
        if (!g) continue;
        if (g.mapping === 'standard') { gp = g; break; }
        if (!gp) gp = g;
    }

    if (gp) {
        setState({
            connected: true,
            id: gp.id,
            axes: [...gp.axes],
            buttons: gp.buttons.map(b => b.pressed)
        });
    } else {
        setState(prev => prev.connected ? { connected: false, axes: [], buttons: [], id: "" } : prev);
    }

    frameRef.current = requestAnimationFrame(pollGamepad);
  }, []);

  useEffect(() => {
    const handleConnect = (e: GamepadEvent) => {
      console.log("Gamepad connected:", e.gamepad.id);
      if (frameRef.current === 0) {
        frameRef.current = requestAnimationFrame(pollGamepad);
      }
    };
    
    const handleDisconnect = (e: GamepadEvent) => {
      console.log("Gamepad disconnected:", e.gamepad.id);
      setState({ connected: false, axes: [], buttons: [], id: "" });
      if (frameRef.current) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = 0;
      }
    };

    window.addEventListener("gamepadconnected", handleConnect);
    window.addEventListener("gamepaddisconnected", handleDisconnect);

    // Initial check
    const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
    let hasGamepad = false;
    for (const g of gamepads) {
      if (g) { hasGamepad = true; break; }
    }
    if (hasGamepad && frameRef.current === 0) {
      frameRef.current = requestAnimationFrame(pollGamepad);
    }

    return () => {
      window.removeEventListener("gamepadconnected", handleConnect);
      window.removeEventListener("gamepaddisconnected", handleDisconnect);
      if (frameRef.current) {
        cancelAnimationFrame(frameRef.current);
        frameRef.current = 0;
      }
    };
  }, [pollGamepad]);

  return state;
}
