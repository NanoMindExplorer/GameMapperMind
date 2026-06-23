// Purpose: Updates the GamepadTester component file with interactive test handlers
import fs from 'fs';

let content = fs.readFileSync('src/components/GamepadTester.tsx', 'utf-8');

// Left Stick
content = content.replace(
  '                {/* Left Stick (Top Left) */}\n                <div className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mt-1 sm:mt-2">',
  `                {/* Left Stick (Top Left) */}
                <div 
                  className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mt-1 sm:mt-2 touch-none cursor-crosshair"
                  onPointerDown={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    const centerX = rect.left + rect.width / 2;
                    const centerY = rect.top + rect.height / 2;
                    const maxDist = rect.width / 2;
                    
                    const handleMove = (ev) => {
                      let dx = ev.clientX - centerX;
                      let dy = ev.clientY - centerY;
                      const dist = Math.sqrt(dx * dx + dy * dy);
                      if (dist > maxDist) {
                        dx = (dx / dist) * maxDist;
                        dy = (dy / dist) * maxDist;
                      }
                      handleStickMoveSimulate('l', dx / maxDist, dy / maxDist);
                    };
                    
                    const handleUp = () => {
                      handleStickMoveSimulate('l', 0, 0);
                      window.removeEventListener('pointermove', handleMove);
                      window.removeEventListener('pointerup', handleUp);
                    };
                    
                    handleMove(e);
                    window.addEventListener('pointermove', handleMove);
                    window.addEventListener('pointerup', handleUp);
                  }}
                >`
);

// Right Stick
content = content.replace(
  '                {/* Right Stick (Bottom Right) */}\n                <div className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mb-1 sm:mb-2">',
  `                {/* Right Stick (Bottom Right) */}
                <div 
                  className="w-14 h-14 sm:w-16 sm:h-16 bg-slate-800 border-2 border-slate-700 rounded-full flex items-center justify-center relative shadow-inner mx-auto mb-1 sm:mb-2 touch-none cursor-crosshair"
                  onPointerDown={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    const centerX = rect.left + rect.width / 2;
                    const centerY = rect.top + rect.height / 2;
                    const maxDist = rect.width / 2;
                    
                    const handleMove = (ev) => {
                      let dx = ev.clientX - centerX;
                      let dy = ev.clientY - centerY;
                      const dist = Math.sqrt(dx * dx + dy * dy);
                      if (dist > maxDist) {
                        dx = (dx / dist) * maxDist;
                        dy = (dy / dist) * maxDist;
                      }
                      handleStickMoveSimulate('r', dx / maxDist, dy / maxDist);
                    };
                    
                    const handleUp = () => {
                      handleStickMoveSimulate('r', 0, 0);
                      window.removeEventListener('pointermove', handleMove);
                      window.removeEventListener('pointerup', handleUp);
                    };
                    
                    handleMove(e);
                    window.addEventListener('pointermove', handleMove);
                    window.addEventListener('pointerup', handleUp);
                  }}
                >`
);

// A B X Y mapping for simulation
content = content.replace(
  '<div className={`absolute top-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 ${pressedButtons[\'y\'] ? \'bg-yellow-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-yellow-500\'}`}>Y</div>',
  '<div onPointerDown={() => simulateInteractiveEvent("y")} onPointerUp={() => simulateInteractiveEvent("y")} onPointerLeave={() => pressedButtons["y"] && simulateInteractiveEvent("y")} className={`absolute top-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons[\'y\'] ? \'bg-yellow-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-yellow-500\'}`}>Y</div>'
);
content = content.replace(
  '<div className={`absolute left-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 ${pressedButtons[\'x\'] ? \'bg-blue-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-blue-500\'}`}>X</div>',
  '<div onPointerDown={() => simulateInteractiveEvent("x")} onPointerUp={() => simulateInteractiveEvent("x")} onPointerLeave={() => pressedButtons["x"] && simulateInteractiveEvent("x")} className={`absolute left-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons[\'x\'] ? \'bg-blue-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-blue-500\'}`}>X</div>'
);
content = content.replace(
  '<div className={`absolute right-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 ${pressedButtons[\'b\'] ? \'bg-red-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-red-500\'}`}>B</div>',
  '<div onPointerDown={() => simulateInteractiveEvent("b")} onPointerUp={() => simulateInteractiveEvent("b")} onPointerLeave={() => pressedButtons["b"] && simulateInteractiveEvent("b")} className={`absolute right-0 top-1/2 -translate-y-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons[\'b\'] ? \'bg-red-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-red-500\'}`}>B</div>'
);
content = content.replace(
  '<div className={`absolute bottom-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 ${pressedButtons[\'a\'] ? \'bg-emerald-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-emerald-500\'}`}>A</div>',
  '<div onPointerDown={() => simulateInteractiveEvent("a")} onPointerUp={() => simulateInteractiveEvent("a")} onPointerLeave={() => pressedButtons["a"] && simulateInteractiveEvent("a")} className={`absolute bottom-0 left-1/2 -translate-x-1/2 w-6 h-6 sm:w-7 sm:h-7 rounded-full shadow-lg flex items-center justify-center font-bold text-[9px] sm:text-[10px] transition-transform duration-75 cursor-pointer select-none touch-none ${pressedButtons[\'a\'] ? \'bg-emerald-500 text-slate-900 scale-95\' : \'bg-slate-800 border-b-2 border-slate-900 text-emerald-500\'}`}>A</div>'
);

// Shoulders L1 / R1
content = content.replace(
  '<div className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg transition-colors duration-75 ${pressedButtons[\'l_shoulder\'] ? \'bg-indigo-500\' : \'bg-slate-800\'}`}></div>',
  '<div onPointerDown={() => simulateInteractiveEvent("l_shoulder")} onPointerUp={() => simulateInteractiveEvent("l_shoulder")} onPointerLeave={() => pressedButtons["l_shoulder"] && simulateInteractiveEvent("l_shoulder")} className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg pointer-events-auto cursor-pointer touch-none transition-colors duration-75 ${pressedButtons[\'l_shoulder\'] ? \'bg-indigo-500\' : \'bg-slate-800\'}`}></div>'
);
content = content.replace(
  '<div className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg transition-colors duration-75 ${pressedButtons[\'r_shoulder\'] ? \'bg-indigo-500\' : \'bg-slate-800\'}`}></div>',
  '<div onPointerDown={() => simulateInteractiveEvent("r_shoulder")} onPointerUp={() => simulateInteractiveEvent("r_shoulder")} onPointerLeave={() => pressedButtons["r_shoulder"] && simulateInteractiveEvent("r_shoulder")} className={`w-16 h-4 border border-slate-700 rounded-t-xl shadow-lg pointer-events-auto cursor-pointer touch-none transition-colors duration-75 ${pressedButtons[\'r_shoulder\'] ? \'bg-indigo-500\' : \'bg-slate-800\'}`}></div>'
);
// Also remove pointer-events-none from shoulders container
content = content.replace('className="absolute top-14 left-1/2 -translate-x-1/2 w-[280px] sm:w-[320px] flex justify-between px-2 pointer-events-none opacity-80"', 'className="absolute top-14 left-1/2 -translate-x-1/2 w-[280px] sm:w-[320px] flex justify-between px-2 opacity-80"');

// Triggers LT / RT
content = content.replace(
  '<div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700">',
  '<div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700 cursor-pointer pointer-events-auto touch-none" onPointerDown={(e) => { const rect = e.currentTarget.getBoundingClientRect(); const val = (e.clientX - rect.left) / rect.width; setTriggers(p => ({...p, lt: Math.max(0, Math.min(1, val))})); onLogMessage(`Gamepad Tester: LT Triger -> ${val.toFixed(2)}`); }} >'
);
content = content.replace(
  '<div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700">',
  '<div className="w-full h-2 bg-slate-800 rounded-full overflow-hidden border border-slate-700 cursor-pointer pointer-events-auto touch-none" onPointerDown={(e) => { const rect = e.currentTarget.getBoundingClientRect(); const val = (e.clientX - rect.left) / rect.width; setTriggers(p => ({...p, rt: Math.max(0, Math.min(1, val))})); onLogMessage(`Gamepad Tester: RT Trigger -> ${val.toFixed(2)}`); }} >'
);

fs.writeFileSync('src/components/GamepadTester.tsx', content);
