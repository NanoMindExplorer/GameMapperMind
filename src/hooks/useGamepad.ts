import { useEffect, useRef } from "react";

const BUTTON_MAPPING = [
  "A", "B", "X", "Y",
  "LB", "RB", "LT", "RT",
  "SELECT", "START",
  "L3", "R3",
  "UP", "DOWN", "LEFT", "RIGHT"
];

export function useGamepad(
  buttonCallback: (buttonName: string, isPressed: boolean, value: number) => void,
  axisCallback: (axes: number[]) => void
) {
  const requestRef = useRef<number>();
  const buttonCallbackRef = useRef(buttonCallback);
  const axisCallbackRef = useRef(axisCallback);

  useEffect(() => {
    buttonCallbackRef.current = buttonCallback;
    axisCallbackRef.current = axisCallback;
  }, [buttonCallback, axisCallback]);

  useEffect(() => {
    const update = () => {
      const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
      let activeGamepad: Gamepad | null = null;
      
      for (const gp of gamepads) {
        if (gp) {
          activeGamepad = gp;
          break;
        }
      }

      if (activeGamepad) {
        activeGamepad.buttons.forEach((button, index) => {
          const buttonName = BUTTON_MAPPING[index] || `UNKNOWN_${index}`;
          if (buttonCallbackRef.current) {
            buttonCallbackRef.current(buttonName, button.pressed, button.value);
          }
        });

        if (axisCallbackRef.current && activeGamepad.axes.length >= 4) {
          axisCallbackRef.current([
            activeGamepad.axes[0],
            activeGamepad.axes[1],
            activeGamepad.axes[2],
            activeGamepad.axes[3]
          ]);
        }
      }

      requestRef.current = requestAnimationFrame(update);
    };

    requestRef.current = requestAnimationFrame(update);

    return () => {
      if (requestRef.current) {
        cancelAnimationFrame(requestRef.current);
      }
    };
  }, []);
}
