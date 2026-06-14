import { useEffect, useState, useRef } from 'react';

export function useGamepad(onButtonPress?: (button: string, value?: number) => void) {
  const [connectedGamepad, setConnectedGamepad] = useState<Gamepad | null>(null);
  const previousButtons = useRef<Record<string, boolean>>({});

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

        // Typical layout map
        const map = [
          'a', 'b', 'x', 'y', 'l_shoulder', 'r_shoulder', 'lt', 'rt',
          'select', 'start', 'l3', 'r3', 'd_up', 'd_down', 'd_left', 'd_right'
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
      } else {
        setConnectedGamepad(null);
      }

      // Fallback to setTimeout when webview is backgrounded/throttled
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
  }, [onButtonPress]);

  return { connectedGamepad };
}
