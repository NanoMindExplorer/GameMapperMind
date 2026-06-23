import { useState, useEffect, useRef, useCallback } from "react";

export interface GamepadState {
  connected: boolean;
  id: string;
  buttons: boolean[];
  axes: number[];
  timestamp: number;
}

// Radial deadzone formula (lebih akurat dari linear)
function radialDeadzone(x: number, y: number, dz: number) {
  const magnitude = Math.sqrt(x*x + y*y);
  if (magnitude < dz) return { x: 0, y: 0 };
  const scale = (magnitude - dz) / (1 - dz);
  return {
    x: (x / magnitude) * scale,
    y: (y / magnitude) * scale,
  };
}

export function useGamepad() {
  const [state, setState] = useState<GamepadState | null>(null);
  const rafRef = useRef<number>(0);
  const prevButtonsRef = useRef<boolean[]>([]);
  const onButtonPressRef = useRef<(index: number) => void>(() => {});

  const poll = useCallback(() => {
    const gamepads = navigator.getGamepads();
    const gp = gamepads[0];

    if (gp) {
      gp.buttons.forEach((btn, i) => {
        const wasPressed = prevButtonsRef.current[i] || false;
        const isPressed = btn.pressed;
        if (isPressed && !wasPressed) {
            onButtonPressRef.current(i);
        }
      });
      prevButtonsRef.current = gp.buttons.map(b => b.pressed);

      const buttons = gp.buttons.map(b => b.pressed);
      
      const leftStick = radialDeadzone(gp.axes[0], gp.axes[1], 0.12);
      const rightStick = radialDeadzone(gp.axes[2], gp.axes[3], 0.12);
      
      const axes = [leftStick.x, leftStick.y, rightStick.x, rightStick.y];
      
      setState({
        connected: true,
        id: gp.id,
        buttons,
        axes,
        timestamp: gp.timestamp,
      });
    }
    rafRef.current = requestAnimationFrame(poll);
  }, []);

  useEffect(() => {
    const onConnect = (e: GamepadEvent) => {
      console.log("Gamepad terhubung:", e.gamepad.id);
      rafRef.current = requestAnimationFrame(poll);
    };
    const onDisconnect = () => {
      cancelAnimationFrame(rafRef.current);
      setState(null);
    };
    window.addEventListener("gamepadconnected", onConnect);
    window.addEventListener("gamepaddisconnected", onDisconnect);
    return () => {
      cancelAnimationFrame(rafRef.current);
      window.removeEventListener("gamepadconnected", onConnect);
      window.removeEventListener("gamepaddisconnected", onDisconnect);
    };
  }, [poll]);

  return state;
}
