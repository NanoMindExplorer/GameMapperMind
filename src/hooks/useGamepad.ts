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


export function useGamepad(onButtonPress?: (index: number) => void) {
  const [state, setState] = useState<GamepadState | null>(null);
  const rafRef = useRef<number>(0);
  const prevButtonsRef = useRef<boolean[]>([]);
  const buttonStatesRef = useRef<ButtonActionState[]>([]);
  const onButtonPressRef = useRef<(index: number) => void>(onButtonPress || (() => {}));

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
        let gp: Gamepad | null = null;
        for (let i = 0; i < gamepads.length; i++) {
            if (gamepads[i] && gamepads[i]!.connected) {
                gp = gamepads[i];
                break;
            }
        }

        if (gp) {
          const axesArray = gp.axes || [];
          const getAxis = (idx: number) => axesArray.length > idx ? axesArray[idx] : 0;
          
          const buttonsList = Array.from(gp.buttons || []);
          const currentButtons = buttonsList.map((b: any) => (typeof b === "object" && b !== null) ? b.pressed : b === 1.0);
          const currentStates: ButtonActionState[] = [];

          currentButtons.forEach((isPressed, i) => {
            const wasPressed = prevButtonsRef.current[i] || false;
            const prevState = buttonStatesRef.current[i] || 'IDLE';
            let nextState: ButtonActionState = 'IDLE';

            if (isPressed && !wasPressed) {
                nextState = 'PRESSED';
                onButtonPressRef.current(i);
            } else if (isPressed && wasPressed) {
                nextState = 'HELD';
            } else if (!isPressed && wasPressed) {
                nextState = 'RELEASED';
            } else {
                nextState = 'IDLE';
            }
            currentStates[i] = nextState;
          });

          prevButtonsRef.current = currentButtons;
          buttonStatesRef.current = currentStates;
          
          const leftStick = radialDeadzone(getAxis(0), getAxis(1), DEFAULT_DEADZONE);
          const rightStick = radialDeadzone(getAxis(2), getAxis(3), DEFAULT_DEADZONE);
          
          // Normalized axes to -1.0 to 1.0 (after deadzone)
          const axes = [leftStick.x, leftStick.y, rightStick.x, rightStick.y];
          
          setState({
            connected: true,
            id: gp.id,
            buttons: currentButtons,
            buttonStates: currentStates,
            axes,
            timestamp: gp.timestamp,
          });
        } else {
            setState(null);
        }
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
