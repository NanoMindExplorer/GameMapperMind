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
    try {
        if (!navigator.getGamepads) return;
        let gamepads: (Gamepad | null)[] = [];
        try {
          gamepads = navigator.getGamepads();
        } catch (e) {
          console.warn("Gamepad access disabled or not supported", e);
          return;
        }
        let gp: Gamepad | null = null;
        for (let i = 0; i < gamepads.length; i++) {
            if (gamepads[i]) {
                gp = gamepads[i];
                break;
            }
        }

        if (gp) {
          const axesArray = gp.axes || [];
          const getAxis = (idx: number) => axesArray.length > idx ? axesArray[idx] : 0;
          
          const buttonsList = Array.from(gp.buttons || []);
          buttonsList.forEach((btn, i) => {
            const wasPressed = prevButtonsRef.current[i] || false;
            const isPressed = (typeof btn === "object" && btn !== null) ? btn.pressed : (btn as any) === 1.0;
            if (isPressed && !wasPressed) {
                onButtonPressRef.current(i);
            }
          });
          prevButtonsRef.current = buttonsList.map((b: any) => (typeof b === "object" && b !== null) ? b.pressed : b === 1.0);

          const buttons = buttonsList.map((b: any) => (typeof b === "object" && b !== null) ? b.pressed : b === 1.0);
          
          const leftStick = radialDeadzone(getAxis(0), getAxis(1), 0.12);
          const rightStick = radialDeadzone(getAxis(2), getAxis(3), 0.12);
          
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
    } catch(err) {
        console.error("Gamepad poll error", err);
    }
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(poll);
    
    const onConnect = (e: GamepadEvent) => {
      console.log("Gamepad terhubung:", e.gamepad.id);
    };
    const onDisconnect = () => {
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
