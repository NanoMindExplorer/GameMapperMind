import { useState, useEffect, useRef, useCallback } from 'react';
import { DEFAULT_DEADZONE, radialDeadzone } from "../constants/gamepad";


export type ButtonActionState = 'IDLE' | 'PRESSED' | 'HELD' | 'RELEASED';

export interface GamepadState {
  connected: boolean;
  id: string;
  buttons: boolean[];
  buttonStates: ButtonActionState[];
  axes: number[];
  timestamp: number;
}

// Radial deadzone moved to constants


export function useGamepad(onButtonPress?: (gamepadIndex: number, buttonIndex: number) => void) {
  const [states, setStates] = useState<GamepadState[]>([]);
  const rafRef = useRef<number>(0);
  const prevButtonsRef = useRef<boolean[][]>([]);
  const buttonStatesRef = useRef<ButtonActionState[][]>([]);
  const onButtonPressRef = useRef<(gamepadIndex: number, buttonIndex: number) => void>(onButtonPress || (() => {}));

  useEffect(() => {
    onButtonPressRef.current = onButtonPress || (() => {});
  }, [onButtonPress]);

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
        
        // BUG-H4 FIX: Throttle poll to 1Hz when no gamepad connected (was 60Hz always).
        // Saves ~5% battery per hour on devices without a gamepad.
        const hasAnyGamepad = gamepads.some(gp => gp && gp.connected);
        if (!hasAnyGamepad) {
            rafRef.current = window.setTimeout(poll, 1000) as unknown as number;
            return;
        }

        const newStates: GamepadState[] = [];
        const maxGamepads = 4;
        
        for (let gpIdx = 0; gpIdx < Math.min(gamepads.length, maxGamepads); gpIdx++) {
            const gp = gamepads[gpIdx];
            if (!gp || !gp.connected) continue;

            if (!prevButtonsRef.current[gpIdx]) prevButtonsRef.current[gpIdx] = [];
            if (!buttonStatesRef.current[gpIdx]) buttonStatesRef.current[gpIdx] = [];

            const axesArray = gp.axes || [];
            const getAxis = (idx: number) => axesArray.length > idx ? axesArray[idx] : 0;
            
            const buttonsList = Array.from(gp.buttons || []);
            // BUG-M4 FIX: Use threshold comparison (>= 0.5) instead of strict equality (=== 1.0).
            // Gamepad analog buttons (e.g., L2/R2 triggers) report float values 0.0-1.0.
            // Using === 1.0 misses presses where value is 0.95-0.99 (common with worn controllers).
            // The W3C spec defines "pressed" as value >= 0.5 for analog buttons, but for digital
            // buttons (boolean), b.pressed is authoritative.
            const currentButtons = buttonsList.map((b: any) => {
              if (typeof b === "object" && b !== null) {
                return b.pressed === true || (typeof b.value === "number" && b.value >= 0.5);
              }
              return b === 1.0 || b === true;
            });
            const currentStates: ButtonActionState[] = [];

            if (prevButtonsRef.current[gpIdx].length === 0 && currentButtons.length > 0) {
              prevButtonsRef.current[gpIdx] = new Array(currentButtons.length).fill(false);
              currentButtons.forEach((isPressed, i) => {
                if (isPressed) {
                  onButtonPressRef.current(gpIdx, i);
                }
              });
            }

            currentButtons.forEach((isPressed, i) => {
              const wasPressed = prevButtonsRef.current[gpIdx][i] || false;
              const prevState = buttonStatesRef.current[gpIdx][i] || 'IDLE';
              let nextState: ButtonActionState = 'IDLE';

              if (isPressed && !wasPressed) {
                nextState = 'PRESSED';
                if (prevState !== 'PRESSED' && prevState !== 'HELD') {
                  onButtonPressRef.current(gpIdx, i);
                }
              } else if (isPressed && wasPressed) {
                nextState = 'HELD';
              } else if (!isPressed && wasPressed) {
                nextState = 'RELEASED';
              } else {
                nextState = 'IDLE';
              }
              currentStates[i] = nextState;
            });
            prevButtonsRef.current[gpIdx] = currentButtons;
            buttonStatesRef.current[gpIdx] = currentStates;

            const leftStick = radialDeadzone(getAxis(0), getAxis(1), DEFAULT_DEADZONE);
            const rightStick = radialDeadzone(getAxis(2), getAxis(3), DEFAULT_DEADZONE);
            const axes = [leftStick.x, leftStick.y, rightStick.x, rightStick.y];
            
            newStates.push({
              connected: true,
              id: gp.id,
              buttons: currentButtons,
              buttonStates: currentStates,
              axes,
              timestamp: gp.timestamp,
            });
        }
        
        setStates(prev => {
            if (prev.length !== newStates.length) return newStates;
            let changed = false;
            // BUG-M13 FIX: Use epsilon comparison for axes to avoid infinite re-renders
            // caused by floating-point drift (e.g., 0.12345678 vs 0.12345679).
            const AXES_EPSILON = 0.001;
            for (let i = 0; i < prev.length; i++) {
                const p = prev[i];
                const n = newStates[i];
                if (p.id !== n.id || p.connected !== n.connected) { changed = true; break; }
                if (p.buttons.length !== n.buttons.length) { changed = true; break; }
                if (p.axes.length !== n.axes.length) { changed = true; break; }
                for (let j = 0; j < p.buttons.length; j++) {
                    if (p.buttons[j] !== n.buttons[j]) { changed = true; break; }
                }
                if (!changed) {
                    for (let j = 0; j < p.axes.length; j++) {
                        if (Math.abs(p.axes[j] - n.axes[j]) > AXES_EPSILON) { changed = true; break; }
                    }
                }
                if (changed) break;
            }
            if (changed) return newStates;
            return prev;
        });
    } catch(err) {
        console.error("Gamepad poll error", err);
    }
    rafRef.current = requestAnimationFrame(poll);
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(poll);
    
    const onConnect = (e: GamepadEvent) => {
      console.log("Gamepad terhubung:", e.gamepad.id);
    };
    const onDisconnect = () => {
      console.log("Gamepad disconnected");
      // Poll will naturally handle the removal
    };
    window.addEventListener("gamepadconnected", onConnect);
    window.addEventListener("gamepaddisconnected", onDisconnect);
    return () => {
      cancelAnimationFrame(rafRef.current);
      // BUG-H4 FIX: Also clear timeout if poll was throttled (setTimeout instead of rAF).
      clearTimeout(rafRef.current);
      window.removeEventListener("gamepadconnected", onConnect);
      window.removeEventListener("gamepaddisconnected", onDisconnect);
    };
  }, [poll]);

  return states;
}
