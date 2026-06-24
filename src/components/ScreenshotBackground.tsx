import React from 'react';
import { Settings, Play, Check, ChevronLeft, ChevronRight } from 'lucide-react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function ScreenshotBackground({ h, children }: { h: OverlayWysiwygHook, children?: React.ReactNode }) {
  return (
    <div className={`${h.isNativeOverlay ? "w-screen h-screen overflow-hidden" : "bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl flex flex-col h-full relative"}`}>
      {/* Layout Settings Panel — collapsible, tidak menghalangi canvas.
          BUG-FIX: Sebelumnya panel mengambil 25% lebar (lg:w-1/4) dan selalu visible,
          menghalangi canvas. Sekarang panel bisa di-toggle (show/hide) dengan tombol
          di tepi kiri. Saat terbuka, panel muncul sebagai overlay浮动 di atas canvas
          (absolute positioned), bukan mengambil space dari canvas. */}
      {!h.isNativeOverlay && (
        <>
          {/* Toggle button — always visible di tepi kiri */}
          <button
            onClick={() => h.setShowConfig(!h.showConfig)}
            className="absolute left-0 top-1/2 -translate-y-1/2 z-30 w-7 h-16 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-r-lg flex items-center justify-center transition-colors shadow-lg"
            title={h.showConfig ? "Tutup Panel" : "Buka Panel Settings"}
          >
            {h.showConfig ? <ChevronLeft className="w-4 h-4 text-slate-400" /> : <ChevronRight className="w-4 h-4 text-slate-400" />}
          </button>

          {/* Settings Panel — absolute overlay, tidak push canvas */}
          {h.showConfig && (
            <div className="absolute left-7 top-2 bottom-2 w-64 bg-slate-950/95 border border-slate-700 rounded-lg p-4 flex flex-col gap-4 overflow-y-auto custom-scrollbar z-20 shadow-2xl">
              <div>
                <h3 className="text-sm font-bold font-sans tracking-tight text-white flex items-center gap-2 mb-1">
                  <Settings className="w-4 h-4 text-indigo-400" /> Layout Settings
                </h3>
                <p className="text-[10px] text-slate-400 leading-relaxed">
                  Konfigurasi referensi visual untuk kemudahan pemetaan kontrol.
                </p>
              </div>

              <div className="space-y-3 flex-1">
                <div className="space-y-1.5">
                  <label className="text-[11px] font-semibold text-slate-300">Background Reference</label>
                  <select
                    className="w-full bg-slate-900 text-slate-100 text-xs px-2.5 py-2 rounded-lg focus:outline-none focus:border-indigo-500 font-sans border border-slate-700"
                    value={h.screenshotMode}
                    onChange={(e) => h.setScreenshotMode(e.target.value)}
                  >
                    <option value="genshin">Genshin Impact</option>
                    <option value="pubg">PUBG Mobile</option>
                    <option value="codm">Call of Duty Mobile</option>
                    <option value="efootball">eFootball</option>
                    <option value="custom">Upload Screenshot...</option>
                  </select>
                </div>

                {h.screenshotMode === 'custom' && (
                  <div className="space-y-1.5">
                    <label className="text-[11px] font-semibold text-slate-300">Upload Screenshot</label>
                    <input 
                      type="file" 
                      ref={h.fileInputRef}
                      accept="image/png, image/jpeg, image/webp"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) {
                          const prev = h.customScreenshotUrl;
                          if (prev && prev.startsWith('blob:')) {
                            try { URL.revokeObjectURL(prev); } catch (_) {}
                          }
                          const reader = new FileReader();
                          reader.onload = () => {
                            h.setCustomScreenshotUrl(reader.result as string);
                          };
                          reader.onerror = () => {
                            const url = URL.createObjectURL(file);
                            h.setCustomScreenshotUrl(url);
                          };
                          reader.readAsDataURL(file);
                        }
                        e.target.value = '';
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

                <div className="space-y-1.5">
                  <div className="flex justify-between items-center">
                    <label className="text-[11px] font-semibold text-slate-300">Brightness</label>
                    <span className="text-[10px] text-slate-400 font-mono">{h.bgDimLevel}%</span>
                  </div>
                  <input 
                    type="range" min="0" max="100" value={h.bgDimLevel}
                    onChange={(e) => h.setBgDimLevel(Number(e.target.value))}
                    className="w-full accent-indigo-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                  />
                </div>
                
                <div className="space-y-1.5">
                  <div className="flex justify-between items-center">
                    <label className="text-[11px] font-semibold text-slate-300">Node Opacity</label>
                    <span className="text-[10px] text-slate-400 font-mono">{h.globalNodeOpacity}%</span>
                  </div>
                  <input 
                    type="range" min="10" max="100" value={h.globalNodeOpacity}
                    onChange={(e) => h.setGlobalNodeOpacity(Number(e.target.value))}
                    className="w-full accent-rose-500 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer"
                  />
                </div>
              </div>
              
              <div className="pt-3 border-t border-slate-800">
                <div className="bg-amber-950/20 border border-amber-900/40 p-2.5 rounded-lg mb-2">
                  <h4 className="text-[9px] uppercase font-bold tracking-wider text-amber-500 mb-0.5">Rotasi Perangkat</h4>
                  <p className="text-[9px] text-amber-400/80 leading-relaxed">
                    Pastikan screenshot diambil dalam posisi yang sama dengan saat bermain.
                  </p>
                </div>
                <button
                  onClick={() => h.setShowConfig(false)}
                  className="w-full py-2 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-[11px] rounded shadow flex justify-center items-center gap-2 transition-transform active:scale-[0.98]"
                >
                  <Play className="w-3.5 h-3.5" />
                  Tutup Panel
                </button>
              </div>
            </div>
          )}
        </>
      )}
      {children}
    </div>
  );
}
