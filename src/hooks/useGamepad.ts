import { useEffect, useState, useRef } from 'react';

export function useGamepad(
  onButtonChange?: (button: string, isPressed: boolean, value?: number) => void,
  onAxisMove?: (axes: { lx: number, ly: number, rx: number, ry: number }) => void
) {
  const [connectedGamepad, setConnectedGamepad] = useState<Gamepad | null>(null);
  const previousButtons = useRef<Record<string, boolean>>({});
  const previousAxes = useRef({ lx: 0, ly: 0, rx: 0, ry: 0 });

  useEffect(() => {
    let animationFrameId: number;

    const poll = () => {
      const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
      let activeGP: Gamepad | null = null;
      
      for (let i = 0; i < gamepads.length; i++) {
        if (gamepads[i] && gamepads[i]!.mapping !== '') {
          // Prefer controllers with known mapping (standard)
          activeGP = gamepads[i];
          break;
        }
      }
      if (!activeGP) {
        for (let i = 0; i < gamepads.length; i++) {
          if (gamepads[i]) {
            activeGP = gamepads[i];
            break;
          }
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
            let isPressed = buttons[idx].pressed;
            
            // Fix R2 and L2 (analog triggers) which fluctuate. Add actuation point.
            if (btnName === 'BUTTON_L2' || btnName === 'BUTTON_R2') {
               isPressed = buttons[idx].value > 0.3;
            }

            currentButtons[btnName] = isPressed;
            const wasPressed = !!previousButtons.current[btnName];
            
            if (isPressed !== wasPressed) {
               if (onButtonChange) onButtonChange(btnName, isPressed, buttons[idx].value);
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
        const deadzone = 0.05;
        const flx = Math.abs(lx) > deadzone ? lx : 0;
        const fly = Math.abs(ly) > deadzone ? ly : 0;
        const frx = Math.abs(rx) > deadzone ? rx : 0;
        const fry = Math.abs(ry) > deadzone ? ry : 0;
        
        const isNeutral = flx === 0 && fly === 0 && frx === 0 && fry === 0;
        const hasChanged = Math.abs(flx - previousAxes.current.lx) > 0.002 ||
                           Math.abs(fly - previousAxes.current.ly) > 0.002 ||
                           Math.abs(frx - previousAxes.current.rx) > 0.002 ||
                           Math.abs(fry - previousAxes.current.ry) > 0.002;
                           
        if (hasChanged || !isNeutral) {
           previousAxes.current = { lx: flx, ly: fly, rx: frx, ry: fry };
           if (onAxisMove) onAxisMove(previousAxes.current);
        }
        
      } else {
        setConnectedGamepad(null);
      }
      
      animationFrameId = requestAnimationFrame(poll);
    };

    animationFrameId = requestAnimationFrame(poll);

    return () => {
      cancelAnimationFrame(animationFrameId);
    };
  }, [onButtonChange, onAxisMove]);

  return { connectedGamepad };
}
