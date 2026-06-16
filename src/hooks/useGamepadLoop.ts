import { useRef } from 'react';
import ShizukuBridge from '../plugins/ShizukuBridge';
import { useGamepad } from './useGamepad';

export function useGamepadLoop(mapProfile: any, active: boolean) {
  const lastState = useRef<Record<string, boolean>>({});
  const isAnalogActive = useRef(false);

  useGamepad(
    async (buttonName, isPressed, value) => {
      if (!active) return;

      const mapping = mapProfile?.mappings?.find((m: any) => m.hardwareKey === buttonName);
      
      if (mapping && isPressed && !lastState.current[buttonName]) {
        // Single tap or start of hold
        await ShizukuBridge.injectTap({ x: mapping.x, y: mapping.y });
      }

      lastState.current[buttonName] = isPressed;
    },
    async (axes) => {
      if (!active) return;
      
      const lx = axes[0] || 0;
      const ly = axes[1] || 0;
      const deadzone = 0.15;
      
      const magnitude = Math.sqrt(lx * lx + ly * ly);
      
      // Default to 500,500 if not found, but we should use mapProfile features
      let centerX = 500;
      let centerY = 500;
      let radius = 200;

      // Find joystick mapping if it exists
      const joystickNode = mapProfile?.joystickNode;
      if (joystickNode) {
        centerX = joystickNode.centerX ?? centerX;
        centerY = joystickNode.centerY ?? centerY;
        radius = joystickNode.radius ?? radius;
      }
      
      const pointerId = 1; // Arbitrary pointer ID for left joystick
      
      try {
        if (magnitude > deadzone) {
          const targetX = centerX + Math.round(lx * radius);
          const targetY = centerY + Math.round(ly * radius);
          
          if (!isAnalogActive.current) {
            isAnalogActive.current = true;
            await ShizukuBridge.touchDown({ x: centerX, y: centerY, pointerId });
          }
          await ShizukuBridge.touchMove({ x: targetX, y: targetY, pointerId });
        } else {
          if (isAnalogActive.current) {
            isAnalogActive.current = false;
            await ShizukuBridge.touchUp({ pointerId });
          }
        }
      } catch (err) {
        console.error("Analog touch injection failed via ShizukuBridge", err);
      }
    }
  );
}
