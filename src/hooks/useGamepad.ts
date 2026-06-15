import { useEffect, useState, useRef } from "react";

export function useGamepad(
  onButtonChange?: (button: string, isPressed: boolean, value?: number) => void,
  onAxisMove?: (axes: {
    lx: number;
    ly: number;
    rx: number;
    ry: number;
  }) => void
) {
  const [connectedGamepad, setConnectedGamepad] = useState<Gamepad | null>(
    null
  );
  const previousButtons = useRef<Record<string, boolean>>({});
  const previousAxes = useRef({ lx: 0, ly: 0, rx: 0, ry: 0 });
  const lastGamepadId = useRef<string | null>(null);

  useEffect(() => {
    let animationFrameId: number;

    const poll = () => {
      const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
      let activeGP: Gamepad | null = null;

      for (let i = 0; i < gamepads.length; i++) {
        if (gamepads[i] && gamepads[i]!.mapping !== "") {
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

      const currentId = activeGP ? activeGP.id : null;
      if (currentId !== lastGamepadId.current) {
        lastGamepadId.current = currentId;
        setConnectedGamepad(activeGP);
      }

      if (activeGP) {
        const buttons = activeGP.buttons;
        const currentButtons: Record<string, boolean> = {};

        const rawMap = [
          "BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y",
          "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2",
          "BUTTON_SELECT", "BUTTON_START", "BUTTON_L3", "BUTTON_R3",
          "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"
        ];

        buttons.forEach((btn, idx) => {
          let btnName = rawMap[idx];
          
          if (!btnName) {
            // M1, M2 macro mapping for obscure extra indices usually starting from 16 to 23
            if (idx === 16) btnName = "BUTTON_M1"; // Sometimes Home/Mode
            else if (idx === 17) btnName = "BUTTON_M2"; // Sometimes Capture/Macro
            else if (idx === 18) btnName = "BUTTON_M3"; 
            else if (idx === 19) btnName = "BUTTON_M4";
            else btnName = `BUTTON_EXTRA_${idx}`;
          }

          let isPressed = btn.pressed;

          // Fix R2 and L2 (analog triggers) which fluctuate. Add actuation point.
          if (btnName === "BUTTON_L2" || btnName === "BUTTON_R2") {
            isPressed = btn.value > 0.3;
          }

          currentButtons[btnName] = isPressed;
          const wasPressed = !!previousButtons.current[btnName];

          if (isPressed !== wasPressed) {
            if (onButtonChange)
              onButtonChange(btnName, isPressed, btn.value);
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
        const hasChanged =
          Math.abs(flx - previousAxes.current.lx) > 0.002 ||
          Math.abs(fly - previousAxes.current.ly) > 0.002 ||
          Math.abs(frx - previousAxes.current.rx) > 0.002 ||
          Math.abs(fry - previousAxes.current.ry) > 0.002;

        if (hasChanged || !isNeutral) {
          previousAxes.current = { lx: flx, ly: fly, rx: frx, ry: fry };
          if (onAxisMove) onAxisMove(previousAxes.current);
        }
      } else {
        // No operation, handled by ID tracking
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
