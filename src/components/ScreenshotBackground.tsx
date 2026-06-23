import React from 'react';
import { Settings, Play, Check } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function ScreenshotBackground({ h, children }: { h: OverlayWysiwygHook, children?: React.ReactNode }) {
  return (
    <div className={`${h.isNativeOverlay ? "w-screen h-screen overflow-hidden" : "bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl flex flex-col lg:flex-row"}`}>
      {/* Canvas Header / Config (Col 3 or Topbar) */}
      {!h.isNativeOverlay && h.showConfig && (
        <div className="lg:w-1/4 bg-slate-950 border-r border-slate-800 p-5 flex flex-col gap-5 overflow-y-auto max-h-[600px] custom-scrollbar z-10 shrink-0">
          <div>
            <h3 className="text-sm font-bold font-sans tracking-tight text-white flex items-center gap-2 mb-2">
              <Settings className="w-4 h-4 text-indigo-400" /> Layout Settings
            </h3>
            <p className="text-[11px] text-slate-400 leading-relaxed mb-4">
              Konfigurasi referensi visual untuk kemudahan pemetaan kontrol.
            </p>
          </div>

          <div className="space-y-4 flex-1">
            <div className="space-y-2">
              <label className="text-xs font-semibold text-slate-300">Background Reference Map</label>
              <select
                className="w-full bg-slate-900 text-slate-100 text-xs px-3 py-2.5 rounded-lg focus:outline-none focus:border-indigo-500 font-sans border border-slate-700"
                value={h.screenshotMode}
                onChange={(e) => h.setScreenshotMode(e.target.value)}
              >
                <option value="genshin">Genshin Impact (Action RPG)</option>
                <option value="pubg">PUBG Mobile (Battle Royale)</option>
                <option value="codm">Call of Duty Mobile (FPS)</option>
                <option value="efootball">eFootball (Sports)</option>
                <option value="custom">Upload Screenshot Custom...</option>
              </select>
            </div>

            {h.screenshotMode === 'custom' && (
              <div className="space-y-2 animate-in fade-in zoom-in duration-200">
                <label className="text-xs font-semibold text-slate-300">Upload Screenshot Anda</label>
                <input 
                  type="file" 
                  ref={h.fileInputRef}
                  accept="image/png, image/jpeg, image/webp"
                  className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      // Try FileReader first (data URL), fallback to blob URL
                      const reader = new FileReader();
                      reader.onload = () => {
                        h.setCustomScreenshotUrl(reader.result as string);
                      };
                      reader.onerror = () => {
                        // Fallback: use blob URL (works with <img src> but not CSS backgroundImage)
                        const url = URL.createObjectURL(file);
                        h.setCustomScreenshotUrl(url);
                      };
                      reader.readAsDataURL(file);
                    }
                  }}
                />
                <button 
                  onClick={() => h.fileInputRef.current?.click()}
                  className="w-full py-2 bg-slate-800 hover:bg-slate-700 text-indigo-300 border border-slate-700 font-semibold text-[11px] rounded transition-colors"
                >
                  Pilih File Gambar
                </button>
                {h.customScreenshotUrl && (
                  <div className="text-[10px] text-emerald-400 flex items-center gap-1 mt-1">
                    <Check className="w-3 h-3" /> Gambar dimuat
                  </div>
                )}
              </div>
            )}

            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <label className="text-xs font-semibold text-slate-300">Background Brightness</label>
                <span className="text-[10px] text-slate-400 font-mono">{h.bgDimLevel}%</span>
              </div>
              <input 
                type="range" min="0" max="100" value={h.bgDimLevel}
                onChange={(e) => h.setBgDimLevel(Number(e.target.value))}
                className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
              />
            </div>
            
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <label className="text-xs font-semibold text-slate-300">Global Node Opacity</label>
                <span className="text-[10px] text-slate-400 font-mono">{h.globalNodeOpacity}%</span>
              </div>
              <input 
                type="range" min="10" max="100" value={h.globalNodeOpacity}
                onChange={(e) => h.setGlobalNodeOpacity(Number(e.target.value))}
                className="w-full accent-rose-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
              />
            </div>
          </div>
          
          <div className="pt-4 border-t border-slate-800 space-y-3">
             <div className="bg-amber-950/20 border border-amber-900/40 p-3 rounded-lg">
                <h4 className="text-[10px] uppercase font-bold tracking-wider text-amber-500 mb-1">Rotasi Perangkat</h4>
                <p className="text-[10px] text-amber-400/80 leading-relaxed text-justify">
                  Perhatikan posisi notch/kamera. Jika game dimainkan dalam posisi landscape terbalik (port charger di kiri), pastikan screenshot juga diambil dalam posisi tersebut agar map akurat.
                </p>
             </div>
             <button
               onClick={() => h.setShowConfig(false)}
               className="w-full py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-xs rounded shadow flex justify-center items-center gap-2 transition-transform active:scale-[0.98]"
             >
               <Play className="w-3.5 h-3.5" />
               Tutup Panel Konfigurasi
             </button>
          </div>
        </div>
      )}
      {children}
    </div>
  );
}
