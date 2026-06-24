import React from 'react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function OverlayCanvas({ h }: { h: OverlayWysiwygHook }) {
  const isSelected = (id: string) => h.selectedButtonId === id;
  const bgUrl = h.getBackgroundUrl();
  const isDataUrl = bgUrl && (bgUrl.startsWith('data:') || bgUrl.startsWith('blob:'));
  const isGradient = bgUrl && bgUrl.startsWith('linear-gradient');

  // BUG-FIX: Set data-overlay-editing on document when dragging to suppress WebView zoom.
  React.useEffect(() => {
    if (typeof document === 'undefined') return;
    if (h.isDragging || h.isDraggingNexion) {
      document.documentElement.setAttribute('data-overlay-editing', 'true');
    } else {
      document.documentElement.removeAttribute('data-overlay-editing');
    }
    return () => {
      document.documentElement.removeAttribute('data-overlay-editing');
    };
  }, [h.isDragging, h.isDraggingNexion]);

  return (
    <div className={`${h.isNativeOverlay ? "w-screen h-screen" : "flex-1"} relative overflow-hidden bg-slate-950 select-none`} style={{ minHeight: 0 }}>
      
      {/* Canvas stage — absolute fill, screenshot rendered as object-contain inside.
          BUG-FIX: Previously tried to set aspect-ratio on container which caused it
          to collapse to 0 height in some WebView versions. Now container always fills
          parent (absolute inset-0). Screenshot uses object-contain to show full image
          without cropping. Button positions are percentages of the container — they
          match the game screen because the screenshot fills the same screen area. */}
      <div 
        className="absolute inset-0 overflow-hidden touch-none"
        onClick={h.handleContainerClick}
        onMouseMove={h.handleDragMove}
        onMouseUp={h.handleDragEnd}
        onMouseLeave={h.handleDragEnd}
        onTouchMove={h.handleDragMove}
        onTouchEnd={h.handleDragEnd}
        style={{
          ...(isGradient ? { backgroundImage: bgUrl, backgroundColor: '#0f172a' } : { backgroundColor: '#0f172a' }),
          touchAction: 'none',
          overscrollBehavior: 'none',
          WebkitUserSelect: 'none',
          WebkitTouchCallout: 'none',
        }}
        id="canvas-container"
      >
        {/* Screenshot background — object-contain preserves full image without crop.
            BUG-FIX: Changed from object-cover to object-contain. object-cover cropped
            the screenshot to fill container, causing visible area mismatch with game screen.
            object-contain shows the full screenshot. If aspect ratio differs from container,
            there will be letterbox bars — but the visible screenshot area matches the game screen
            exactly, so button positions (percentages) are correct. */}
        {isDataUrl && (
          <img 
            src={bgUrl} 
            alt="Screenshot background" 
            className="absolute inset-0 w-full h-full object-contain"
            style={{ pointerEvents: 'none' }}
            onError={(e) => console.error('Screenshot image failed to load', e)}
          />
        )}
        <div className="absolute inset-0 bg-black pointer-events-none transition-opacity" style={{ opacity: h.bgDimLevel / 100 }}></div>
        
        {!h.hideGrid && (
          <div className="absolute inset-0 pointer-events-none opacity-20"
               style={{
                 backgroundImage: 'linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)',
                 backgroundSize: '20px 20px'
               }}
          ></div>
        )}

        {!h.hideAllNodes && h.activeProfile?.buttons?.map((btn: any) => {
          let nodeRadiusClasses = "rounded-full";
          if (btn.type === 'swipe') nodeRadiusClasses = "rounded-lg";

          return (
            <div
              key={btn.id}
              id={`btn-${btn.id}`}
              data-btn-node={btn.id}
              className={`absolute ${nodeRadiusClasses} flex flex-col items-center justify-center cursor-move select-none touch-none group/node ${
                isSelected(btn.id) 
                  ? 'border-2 border-indigo-400 z-40 bg-indigo-500/40' 
                  : 'border border-slate-400/50 hover:border-slate-300 z-20 bg-slate-900/60'
              }`}
              style={{
                left: `${btn.x}%`,
                top: `${btn.y}%`,
                width: `${btn.width || 56}px`,
                height: `${btn.height || 56}px`,
                transform: 'translate(-50%, -50%)',
                opacity: h.globalNodeOpacity / 100,
                // BUG-FIX: Hapus box-shadow saat selected — visual "membesar" illusion
                // disebabkan oleh border-2 + box-shadow yang membuat analog stick (120px)
                // terlihat berubah ukuran saat select/deselect. Gunakan outline instead
                // (tidak menambah layout size).
                outline: isSelected(btn.id) && h.showPalette ? '2px solid #8B5CF6' : undefined,
                outlineOffset: '2px',
                boxShadow: undefined,
                borderColor: undefined,
              }}
              onMouseDown={(e) => {
                // BUG-FIX: preventDefault di sini untuk stop WebView zoom/scroll
                // saat touch analog stick. Sebelumnya hanya stopPropagation.
                e.preventDefault();
                e.stopPropagation();
                h.handleDragStart(btn.id, e);
              }}
              onTouchStart={(e) => {
                // BUG-FIX: preventDefault di touchStart juga untuk stop pinch-zoom.
                e.preventDefault();
                e.stopPropagation();
                h.handleDragStart(btn.id, e);
              }}
              onClick={(e) => e.stopPropagation()}
            >
              {isSelected(btn.id) && h.showPalette && (
                <div className="absolute -top-3 -right-3 w-5 h-5 bg-indigo-500 rounded-full animate-ping opacity-75"></div>
              )}
              
              <span className={`text-[10px] font-bold font-sans tracking-wide ${isSelected(btn.id) ? 'text-white drop-shadow-md' : 'text-slate-300'}`}>
                {btn.label}
              </span>
              
              <span className="text-[8px] font-mono opacity-50 block mt-0.5 whitespace-nowrap">
                {btn.mappedKey}
              </span>

              {/* Special visual indicators depending on type */}
              {(() => {
                const isAnalog = btn.type === 'analog_stick';
                const isSwipe = btn.type === 'swipe';
                
                if (isAnalog) {
                  let stickX = 0;
                  let stickY = 0;
                  
                  if (btn.mappedKey === 'L_STICK') {
                    stickX = h.activeAxes.lx * (btn.width / 3.5);
                    stickY = h.activeAxes.ly * (btn.height / 3.5);
                  } else if (btn.mappedKey === 'R_STICK') {
                    stickX = h.activeAxes.rx * (btn.width / 3.5);
                    stickY = h.activeAxes.ry * (btn.height / 3.5);
                  }
                  
                  const isLeft = btn.mappedKey === 'L_STICK';
                  const baseColor = isLeft ? 'bg-indigo-500' : 'bg-pink-500';
                  const borderColor = isLeft ? 'border-indigo-400' : 'border-pink-400';
                  
                  return (
                    <React.Fragment>
                      {/* Sub-grid crosshair indicator — pointer-events-none */}
                      <div className="absolute inset-3 border border-white/10 rounded-full flex items-center justify-center pointer-events-none">
                        <div className="w-full h-[1px] bg-white/5 absolute"></div>
                        <div className="h-full w-[1px] bg-white/5 absolute"></div>
                      </div>
                      
                      {/* The analog cap that moves.
                          BUG-FIX: pointer-events-none — analog cap tidak boleh intercept
                          touch/mouse events. Sebelumnya, analog cap (absolute w-[45%] h-[45%])
                          menyerap mousedown/touchstart, sehingga setelah drag analog stick,
                          drag tombol lain tidak berfungsi (event tertangkap oleh analog cap).
                          BUG-FIX: Hapus transition transform saat sedang drag (h.isDragging)
                          untuk mencegah visual glitch "membesar" saat drag analog + gamepad gerak.
                          BUG-FIX: Hapus backdrop-blur-md — backdrop-filter memaksa WebView
                          render ulang seluruh area di belakang analog cap (canvas, screenshot,
                          grid, tombol lain) setiap kali analog cap re-render. Saat analog
                          stick di-select/deselect, re-render analog cap memicu composite
                          ulang seluruh canvas → visual "membesar/mengecil". */}
                      <div 
                        className={`absolute w-[45%] h-[45%] ${baseColor} rounded-full border-[2.5px] ${borderColor} shadow-[0_4px_10px_rgba(0,0,0,0.5),inset_0_3px_5px_rgba(255,255,255,0.4)] z-10 flex items-center justify-center pointer-events-none`}
                        style={{
                          transform: `translate(${stickX}px, ${stickY}px)`,
                          transition: h.isDragging ? 'none' : 'transform 80ms ease-out'
                        }}
                      >
                         <div className="w-1/3 h-1/3 rounded-full bg-white/30 blur-[1px]"></div>
                      </div>
                    </React.Fragment>
                  );
                }
                
                if (btn.type === 'gyro_area') {
                  return <div className="border border-dashed border-pink-400/40 absolute inset-2 rounded-full animate-spin pointer-events-none" style={{ animationDuration: '20s' }}></div>;
                }
                
                if (isSwipe) {
                  return (
                    <div className="absolute inset-0 flex flex-col justify-center items-center pointer-events-none p-1 overflow-hidden">
                      {btn.androidEventCode === 201 && (
                        <div className="flex flex-col items-center select-none gap-0.5 mt-auto pb-1">
                           <div className="w-1 h-3 bg-indigo-400/60 rounded flex items-start justify-center"><div className="w-1 h-1 bg-white rounded-full"></div></div>
                        </div>
                      )}
                      {btn.androidEventCode === 202 && (
                        <div className="flex flex-col items-center select-none gap-0.5 mb-auto pt-1">
                           <div className="w-1 h-3 bg-indigo-400/60 rounded flex items-end justify-center"><div className="w-1 h-1 bg-white rounded-full"></div></div>
                        </div>
                      )}
                      {btn.androidEventCode === 203 && (
                        <div className="flex flex-row items-center select-none gap-0.5 ml-auto pr-1">
                           <div className="h-1 w-3 bg-indigo-400/60 rounded flex items-center justify-start"><div className="w-1 h-1 bg-white rounded-full"></div></div>
                        </div>
                      )}
                      {btn.androidEventCode === 204 && (
                        <div className="flex flex-row items-center select-none gap-0.5 mr-auto pl-1">
                           <div className="h-1 w-3 bg-indigo-400/60 rounded flex items-center justify-end"><div className="w-1 h-1 bg-white rounded-full"></div></div>
                        </div>
                      )}
                    </div>
                  );
                }
                
                return null;
              })()}

              {isSelected(btn.id) && (
                <div className="absolute top-full left-1/2 -translate-x-1/2 mt-2 flex gap-1 pointer-events-auto shadow-xl z-50 bg-slate-900/95 p-1.5 rounded-lg border border-slate-700">
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(btn.id, 0, -1); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Geser Atas"
                  >
                    ↑
                  </button>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(btn.id, 0, 1); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Geser Bawah"
                  >
                    ↓
                  </button>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(btn.id, -1, 0); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Geser Kiri"
                  >
                    ←
                  </button>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.relocateButtonOffset(btn.id, 1, 0); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Geser Kanan"
                  >
                    →
                  </button>
                  <div className="w-px h-7 bg-slate-700 mx-0.5"></div>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperties({ width: (btn.width || 56) - 5, height: (btn.height || 56) - 5 }); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-rose-900 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Perkecil Tombol"
                  >
                    −
                  </button>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperties({ width: (btn.width || 56) + 5, height: (btn.height || 56) + 5 }); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-indigo-900 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Perbesar Tombol"
                  >
                    +
                  </button>
                </div>
              )}

              {/* Visual coordinate hover flag */}
              <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 hidden group-hover/node:block bg-slate-950/90 text-[8px] font-mono text-indigo-300 px-1 py-0.5 rounded border border-indigo-900 pointer-events-none whitespace-nowrap">
                X:{Number(btn.x).toFixed(1)}% Y:{Number(btn.y).toFixed(1)}%
              </div>
            </div>
          );
        })}

        {/* Quick HUD guide */}
        <div className="absolute bottom-3 left-4 right-4 flex justify-between text-[10px] font-mono text-slate-400 tracking-wide bg-slate-950/80 p-2 rounded border border-slate-900 pointer-events-none">
          <span>Orchestration Context Active Node Out: {h.activeProfile.packageName}</span>
          <span>Sub-frame Latency: &lt;8 ms</span>
        </div>
      </div>
    </div>
  );
}
