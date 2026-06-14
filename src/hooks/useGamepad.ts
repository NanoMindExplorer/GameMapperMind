import { useEffect, useState, useRef } from 'react';

export function useGamepad(
  onButtonPress?: (button: string, value?: number) => void,
  onAxisMove?: (axes: { lx: number, ly: number, rx: number, ry: number }) => void
) {
  const [connectedGamepad, setConnectedGamepad] = useState<Gamepad | null>(null);
  const previousButtons = useRef<Record<string, boolean>>({});
  const previousAxes = useRef({ lx: 0, ly: 0, rx: 0, ry: 0 });

  useEffect(() => {
    let animationFrameId: number;
    let timeoutId: number;

    const poll = () => {
      const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
      let activeGP: Gamepad | null = null;
      
      for (let i = 0; i < gamepads.length; i++) {
        if (gamepads[i]) {
          activeGP = gamepads[i];
          break;
        }
      }

      if (activeGP) {
        setConnectedGamepad(activeGP);
        
        const buttons = activeGP.buttons;
        const currentButtons: Record<string, boolean> = {};

        // Typical layout map mapped to application BUTTON_* standard
        const map = [
          'BUTTON_A', 'BUTTON_B', 'BUTTON_X', 'BUTTON_Y', 
          'BUTTON_L1', 'BUTTON_R1', 'BUTTON_L2', 'BUTTON_R2',
          'BUTTON_SELECT', 'BUTTON_START', 'BUTTON_L3', 'BUTTON_R3', 
          'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'
        ];

        map.forEach((btnName, idx) => {
          if (buttons[idx]) {
            currentButtons[btnName] = buttons[idx].pressed;
            const wasPressed = previousButtons.current[btnName];
            
            if (buttons[idx].pressed && !wasPressed) {
               if (onButtonPress) onButtonPress(btnName, buttons[idx].value);
            }
          }
        });

        previousButtons.current = currentButtons;
        
        // Analog polling
        const axes = activeGP.axes;
        const lx = axes[0] || 0;
        const ly = axes[1] || 0;
        const rx = axes[2] || 0;
        const ry = axes[3] || 0;
        
        // Deadzone filter
        const deadzone = 0.1;
        const flx = Math.abs(lx) > deadzone ? lx : 0;
        const fly = Math.abs(ly) > deadzone ? ly : 0;
        const frx = Math.abs(rx) > deadzone ? rx : 0;
        const fry = Math.abs(ry) > deadzone ? ry : 0;
        
        if (
          Math.abs(flx - previousAxes.current.lx) > 0.05 ||
          Math.abs(fly - previousAxes.current.ly) > 0.05 ||
          Math.abs(frx - previousAxes.current.rx) > 0.05 ||
          Math.abs(fry - previousAxes.current.ry) > 0.05
        ) {
           previousAxes.current = { lx: flx, ly: fly, rx: frx, ry: fry };
           if (onAxisMove) onAxisMove(previousAxes.current);
        }
        
      } else {
        setConnectedGamepad(null);
      }

      if (document.hidden) {
        timeoutId = window.setTimeout(poll, 16);
      } else {
        animationFrameId = requestAnimationFrame(poll);
      }
    };

    if (document.hidden) {
      timeoutId = window.setTimeout(poll, 16);
    } else {
      animationFrameId = requestAnimationFrame(poll);
    }

    return () => {
      cancelAnimationFrame(animationFrameId);
      clearTimeout(timeoutId);
    };
  }, [onButtonPress, onAxisMove]);

  return { connectedGamepad };
}
