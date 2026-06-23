import React from 'react';
import { OverlayWysiwygHook } from './OverlayTypes';

export default function OverlayCanvas({ h }: { h: OverlayWysiwygHook }) {
  const isSelected = (id: string) => h.selectedButtonId === id;
  const bgUrl = h.getBackgroundUrl();
  const isDataUrl = bgUrl && (bgUrl.startsWith('data:') || bgUrl.startsWith('blob:'));
  const isGradient = bgUrl && bgUrl.startsWith('linear-gradient');

  return (
    <div className={`${h.isNativeOverlay ? "w-screen h-screen" : "flex-1"} relative overflow-hidden bg-slate-950 flex flex-col group select-none`} style={{ minHeight: 0 }}>
      
      {/* Visual Canvas stage Area — fixed dimensions to prevent resize during drag */}
      <div 
        className="absolute inset-0 w-full h-full overflow-hidden"
        onClick={h.handleContainerClick}
        onMouseMove={h.handleDragMove}
        onMouseUp={h.handleDragEnd}
        onMouseLeave={h.handleDragEnd}
        onTouchMove={h.handleDragMove}
        onTouchEnd={h.handleDragEnd}
        style={isGradient ? {
          backgroundImage: bgUrl,
          backgroundColor: '#0f172a'
        } : {
          backgroundColor: '#0f172a'
        }}
        id="canvas-container"
      >
        {/* Render screenshot as <img> tag — more reliable than CSS backgroundImage in Capacitor WebView */}
        {isDataUrl && (
          <img 
            src={bgUrl} 
            alt="Screenshot background" 
            className="absolute inset-0 w-full h-full object-cover"
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
                boxShadow: isSelected(btn.id) && h.showPalette ? '0 0 16px rgba(139, 92, 246, 0.8), inset 0 0 8px rgba(139, 92, 246, 0.4)' : undefined,
                borderColor: isSelected(btn.id) && h.showPalette ? '#8B5CF6' : undefined
              }}
              onMouseDown={(e) => h.handleDragStart(btn.id, e)}
              onTouchStart={(e) => h.handleDragStart(btn.id, e)}
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
                      {/* Sub-grid crosshair indicator */}
                      <div className="absolute inset-3 border border-white/10 rounded-full flex items-center justify-center pointer-events-none">
                        <div className="w-full h-[1px] bg-white/5 absolute"></div>
                        <div className="h-full w-[1px] bg-white/5 absolute"></div>
                      </div>
                      
                      {/* The analog cap that moves */}
                      <div 
                        className={`absolute w-[45%] h-[45%] ${baseColor} rounded-full border-[2.5px] ${borderColor} shadow-[0_4px_10px_rgba(0,0,0,0.5),inset_0_3px_5px_rgba(255,255,255,0.4)] z-10 flex items-center justify-center backdrop-blur-md`}
                        style={{
                          transform: `translate(${stickX}px, ${stickY}px)`,
                          transition: 'transform 80ms ease-out'
                        }}
                      >
                         <div className="w-1/3 h-1/3 rounded-full bg-white/30 blur-[1px]"></div>
                      </div>
                    </React.Fragment>
                  );
                }
                
                if (btn.type === 'gyro_area') {
                  return <div className="border border-dashed border-pink-400/40 absolute inset-2 rounded-full animate-spin" style={{ animationDuration: '20s' }}></div>;
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

              {isSelected(btn.id) && !h.showPalette && (
                <div className="absolute top-full left-1/2 -translate-x-1/2 mt-2 flex gap-1 pointer-events-auto shadow-xl z-50 bg-slate-900/95 backdrop-blur p-1.5 rounded-lg border border-slate-700">
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
                    onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperty('width', (btn.width || 56) - 5); h.handleUpdateBtnProperty('height', (btn.height || 56) - 5); }}
                    className="w-7 h-7 rounded bg-slate-800 hover:bg-rose-900 border border-slate-700 flex items-center justify-center text-[11px] touch-none"
                    title="Perkecil Tombol"
                  >
                    −
                  </button>
                  <button 
                    onClick={(e) => { e.stopPropagation(); h.handleUpdateBtnProperty('width', (btn.width || 56) + 5); h.handleUpdateBtnProperty('height', (btn.height || 56) + 5); }}
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
        <div className="absolute bottom-3 left-4 right-4 flex justify-between text-[10px] font-mono text-slate-400 tracking-wide bg-slate-950/80 p-2 rounded backdrop-blur border border-slate-900 pointer-events-none">
          <span>Orchestration Context Active Node Out: {h.activeProfile.packageName}</span>
          <span>Sub-frame Latency: &lt;8 ms</span>
        </div>
      </div>
    </div>
  );
}
