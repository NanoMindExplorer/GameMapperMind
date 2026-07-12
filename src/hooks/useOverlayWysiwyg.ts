import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';

export interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
  activeKeys?: string[];
  activeAxes?: {lx: number, ly: number, rx: number, ry: number};
  isNativeOverlay?: boolean;
}

export function useOverlayWysiwyg({
  activeProfile, onUpdateProfile, onLogMessage, activeKeys = [], activeAxes = {lx:0, ly:0, rx:0, ry:0}, isNativeOverlay = false
}: OverlayWysiwygProps) {

  const [showConfig, setShowConfig] = React.useState(true);
  const [selectedButtonId, setSelectedButtonId] = React.useState<string | null>(null);
  const [screenshotMode, setScreenshotMode] = React.useState<string>('genshin');
  const [customScreenshotUrl, setCustomScreenshotUrl] = React.useState<string | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = React.useState(false);
  const [nexionPos, setNexionPos] = React.useState({ x: 50, y: 10 });
  const [isDraggingNexion, setIsDraggingNexion] = React.useState(false);
  const nexionDragHasMoved = React.useRef(false);
  const [showPalette, setShowPalette] = React.useState(false);

  const [activePlayer, setActivePlayer] = React.useState<1|2|3|4>(1);
  const [hideGrid, setHideGrid] = React.useState(false);
  const [hideAllNodes, setHideAllNodes] = React.useState(false);

  const [bgDimLevel, setBgDimLevel] = React.useState(30);
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(80);

  // BUG-FIX: Track screenshot natural dimensions for aspect-ratio-aware canvas.
  // When user uploads a custom screenshot, the canvas-container must match the
  // screenshot's aspect ratio. Otherwise, object-cover crops the image and
  // button positions (percentages) don't map correctly to the game screen.
  const [screenshotDimensions, setScreenshotDimensions] = React.useState<{w: number, h: number} | null>(null);

  // FIX: screenshotDimensions above was declared and returned from this hook, but nothing
  // ever called setScreenshotDimensions() and nothing ever read the value — it was
  // completely inert. This is the actual root cause of "posisi tombol sangat jauh dan tidak
  // tepat": button x/y percentages were computed relative to the full canvas-container, but
  // the background screenshot (rendered with object-contain) only occupies a letterboxed/
  // pillarboxed sub-rectangle of that container whenever the container's aspect ratio
  // (screen width x (screen height - top bar)) differs from the screenshot's own aspect
  // ratio (the device's raw physical screen). At runtime the native overlay has no top bar
  // and maps percentages onto the full physical screen 1:1 — so a button placed "on" a
  // player in the editor visibly lands somewhere else entirely once injected for real.
  //
  // Fix: track the container's live pixel size, compute the actual rendered content
  // rectangle (mirroring object-contain's own letterbox math) and expose it so both the
  // JSX (to size the #content-rect wrapper the image + buttons live inside) and the drag
  // handlers below (to compute percentages relative to that rect, not the outer container)
  // use the exact same reference frame — which now matches the physical screen 1:1.
  const [containerSize, setContainerSize] = React.useState<{w: number, h: number} | null>(null);

  React.useEffect(() => {
    const el = document.getElementById('canvas-container');
    if (!el || typeof ResizeObserver === 'undefined') return;
    const update = () => setContainerSize({ w: el.clientWidth, h: el.clientHeight });
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const handleBackgroundImageLoad = React.useCallback((naturalWidth: number, naturalHeight: number) => {
    if (naturalWidth > 0 && naturalHeight > 0) {
      setScreenshotDimensions({ w: naturalWidth, h: naturalHeight });
    }
  }, []);

  // Pure letterbox/pillarbox math — same result object-contain would produce, but computed
  // explicitly so both rendering and drag-coordinate code agree on the exact same rectangle.
  const contentRect = React.useMemo(() => {
    if (!containerSize || containerSize.w <= 0 || containerSize.h <= 0) return null;
    if (!screenshotDimensions || screenshotDimensions.w <= 0 || screenshotDimensions.h <= 0) {
      // No screenshot loaded yet — content rect is the full container (legacy behavior).
      return { left: 0, top: 0, width: containerSize.w, height: containerSize.h };
    }
    const containerAspect = containerSize.w / containerSize.h;
    const imgAspect = screenshotDimensions.w / screenshotDimensions.h;
    let width: number, height: number;
    if (imgAspect > containerAspect) {
      // Image is relatively wider than the container → constrained by width, letterboxed top/bottom.
      width = containerSize.w;
      height = width / imgAspect;
    } else {
      // Image is relatively taller than the container → constrained by height, pillarboxed left/right.
      height = containerSize.h;
      width = height * imgAspect;
    }
    return { left: (containerSize.w - width) / 2, top: (containerSize.h - height) / 2, width, height };
  }, [containerSize, screenshotDimensions]);

  // BUG-FIX: Clear screenshot dimensions when switching away from custom mode.
  // Otherwise, canvas-container keeps old aspect ratio from previous screenshot.
  React.useEffect(() => {
    if (screenshotMode !== 'custom') {
      setScreenshotDimensions(null);
    }
  }, [screenshotMode]);

  const selectedButton = activeProfile.buttons.find(b => b.id === selectedButtonId);

  // Keep a ref to latest profile to avoid stale state in callbacks
  const profileRef = React.useRef(activeProfile);
  React.useEffect(() => { profileRef.current = activeProfile; }, [activeProfile]);

  // BUG-O1/O2/O4 FIX: Use refs for drag state to avoid stale closure in event handlers.
  // React state updates are async; closure captures old value during rapid mousemove events.
  const isDraggingRef = React.useRef(false);
  const isDraggingNexionRef = React.useRef(false);
  const selectedButtonIdRef = React.useRef<string | null>(null);
  const dragOffsetRef = React.useRef<{ dx: number; dy: number }>({ dx: 0, dy: 0 });

  React.useEffect(() => { selectedButtonIdRef.current = selectedButtonId; }, [selectedButtonId]);

  const handleContainerClick = (e: React.MouseEvent) => {
    // BUG-C3 FIX: Don't deselect if click target is inside a button node.
    const target = e.target as HTMLElement;
    if (target.closest('[data-btn-node]')) return;
    if (isDraggingRef.current) return;
    setSelectedButtonId(null);
    if (showPalette) setShowPalette(false);
  };

  // BUG-FIX: Cache canvas rect at drag START so reflow during drag doesn't shift positions.
  // Previously, getBoundingClientRect() was called on every handleDragMove event.
  // When dragging caused layout reflow (panel/HUD/flex changes), rect changed,
  // causing all percentage-based positions to shift → buttons "jumped".
  const cachedRectRef = React.useRef<DOMRect | null>(null);

  const handleDragStart = (btnId: string, e: React.MouseEvent | React.TouchEvent) => {
    e.stopPropagation();
    if (e.preventDefault) e.preventDefault();

    // FIX: previously cached the outer canvas-container's rect directly, which is wrong
    // whenever the screenshot is letterboxed/pillarboxed within it (see contentRect above).
    // Combine the container's viewport position with the computed content-rect offset/size
    // so all drag math below operates in "screenshot space", matching the physical screen
    // 1:1 at runtime.
    const containerRect = document.getElementById('canvas-container')?.getBoundingClientRect() ?? null;
    cachedRectRef.current = containerRect && contentRect
      ? new DOMRect(containerRect.left + contentRect.left, containerRect.top + contentRect.top, contentRect.width, contentRect.height)
      : containerRect;

    isDraggingRef.current = true;
    selectedButtonIdRef.current = btnId;
    setIsDragging(true);
    setSelectedButtonId(btnId);

    // BUG-O5 FIX: Capture initial offset between pointer and button center.
    const current = profileRef.current;
    const btn = current?.buttons.find(b => b.id === btnId);
    if (btn && cachedRectRef.current) {
      const clientX = 'touches' in e ? (e.touches[0]?.clientX ?? 0) : e.clientX;
      const clientY = 'touches' in e ? (e.touches[0]?.clientY ?? 0) : e.clientY;
      const rect = cachedRectRef.current;
      const btnScreenX = rect.left + (btn.x / 100) * rect.width;
      const btnScreenY = rect.top + (btn.y / 100) * rect.height;
      dragOffsetRef.current = {
        dx: clientX - btnScreenX,
        dy: clientY - btnScreenY,
      };
    }
  };

  const handleDragMove = (e: any) => {
    // BUG-FIX: preventDefault EARLY (before any early-return) to suppress WebView zoom/scroll
    // during touch drag. Previously, preventDefault was called AFTER the isDragging check,
    // so non-drag touches (e.g., pinch-zoom) were not suppressed → canvas "membesar/mengecil".
    if (e.cancelable && (e.type === 'touchmove' || e.touches)) {
      e.preventDefault?.();
    }

    // BUG-O2 FIX: Use ref for isDraggingNexion.
    if (isDraggingNexionRef.current) {
      nexionDragHasMoved.current = true;
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;
      // BUG-FIX: Use cached rect for stable coordinate space.
      const rect = cachedRectRef.current ?? document.getElementById('canvas-container')?.getBoundingClientRect();
      if (rect) {
        let x = ((clientX - rect.left) / rect.width) * 100;
        let y = ((clientY - rect.top) / rect.height) * 100;
        setNexionPos({
          x: Math.max(0, Math.min(100, x)),
          y: Math.max(0, Math.min(100, y))
        });
      }
      return;
    }

    // BUG-O1/O4 FIX: Use refs instead of stale state closure.
    if (!isDraggingRef.current || !selectedButtonIdRef.current) return;

    // BUG-FIX: Use cached rect (set at drag start) — stable coordinate space.
    // Fallback to live rect only if cache is null (defensive).
    const rect = cachedRectRef.current ?? document.getElementById('canvas-container')?.getBoundingClientRect();
    if (!rect) return;

    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;

    // BUG-O5 FIX: Apply drag offset so button follows pointer smoothly.
    let x = ((clientX - dragOffsetRef.current.dx - rect.left) / rect.width) * 100;
    let y = ((clientY - dragOffsetRef.current.dy - rect.top) / rect.height) * 100;

    x = Math.max(0, Math.min(100, x));
    y = Math.max(0, Math.min(100, y));

    // Use ref to avoid stale state, pass GamepadProfile (not function)
    const current = profileRef.current;
    if (!current) return;
    const btnId = selectedButtonIdRef.current;
    const updatedButtons = current.buttons.map(b => {
      if (b.id === btnId) {
        return { ...b, x, y };
      }
      return b;
    });
    onUpdateProfile({ ...current, buttons: updatedButtons });
  };

  const handleDragEnd = () => {
    isDraggingRef.current = false;
    setIsDragging(false);
    // BUG-FIX: Clear cached rect so next drag re-captures fresh layout.
    cachedRectRef.current = null;
    if (isDraggingNexionRef.current) {
      setTimeout(() => {
        setIsDraggingNexion(false);
        isDraggingNexionRef.current = false;
        setTimeout(() => { nexionDragHasMoved.current = false; }, 100);
      }, 50);
    }
  };

  // BUG-O5/W1 FIX: Attach global mouse/touch listeners when dragging.
  // This ensures drag continues even if pointer leaves canvas-container (e.g., fast drag).
  React.useEffect(() => {
    if (!isDragging && !isDraggingNexion) return;

    const moveHandler = (e: MouseEvent | TouchEvent) => {
      handleDragMove(e);
    };
    const endHandler = () => {
      handleDragEnd();
    };

    window.addEventListener('mousemove', moveHandler);
    window.addEventListener('mouseup', endHandler);
    window.addEventListener('touchmove', moveHandler, { passive: false } as any);
    window.addEventListener('touchend', endHandler);
    window.addEventListener('touchcancel', endHandler);

    return () => {
      window.removeEventListener('mousemove', moveHandler);
      window.removeEventListener('mouseup', endHandler);
      window.removeEventListener('touchmove', moveHandler as any);
      window.removeEventListener('touchend', endHandler);
      window.removeEventListener('touchcancel', endHandler);
    };
  }, [isDragging, isDraggingNexion]);

  const handleUpdateBtnProperty = (key: keyof VirtualButton, value: any) => {
    if (!selectedButtonIdRef.current) return;
    const current = profileRef.current;
    const btnId = selectedButtonIdRef.current;
    const updatedButtons = current.buttons.map(b => {
      if (b.id === btnId) {
        return { ...b, [key]: value };
      }
      return b;
    });
    onUpdateProfile({ ...current, buttons: updatedButtons });
  };

  // BUG-PP1 FIX: Batch multiple property updates into a single profile update.
  // Previously, calling handleUpdateBtnProperty multiple times in a row (e.g., when
  // changing mappedKey to SWIPE_UP: set mappedKey, type, androidEventCode, label)
  // would read stale profileRef.current between calls, causing only the LAST update
  // to persist. Now, updates is a partial object applied in one shot.
  const handleUpdateBtnProperties = (updates: Partial<VirtualButton>) => {
    if (!selectedButtonIdRef.current) return;
    const current = profileRef.current;
    const btnId = selectedButtonIdRef.current;
    const updatedButtons = current.buttons.map(b => {
      if (b.id === btnId) {
        return { ...b, ...updates };
      }
      return b;
    });
    onUpdateProfile({ ...current, buttons: updatedButtons });
  };

  const relocateButtonOffset = (id: string, dx: number, dy: number) => {
    const current = profileRef.current;
    const updatedButtons = current.buttons.map(b => {
      if (b.id === id) {
        return {
          ...b,
          x: Math.max(0, Math.min(100, b.x + dx)),
          y: Math.max(0, Math.min(100, b.y + dy))
        };
      }
      return b;
    });
    onUpdateProfile({ ...current, buttons: updatedButtons });
  };

  const handleAddSpecificButton = (label: string, mappedKey: string, androidEventCode: number, defaultSize: number = 56, type: VirtualButton['type'] = 'button') => {
    const freshId = Math.random().toString(16).substring(2, 10).padStart(8, '0');

    // BUG-O7 FIX: Set swipeDirection for swipe buttons.
    let swipeDirection: VirtualButton['swipeDirection'] | undefined;
    if (type === 'swipe') {
      if (label.includes('UP') || mappedKey === 'SWIPE_UP') swipeDirection = 'UP';
      else if (label.includes('DOWN') || mappedKey === 'SWIPE_DOWN') swipeDirection = 'DOWN';
      else if (label.includes('LEFT') || mappedKey === 'SWIPE_LEFT') swipeDirection = 'LEFT';
      else if (label.includes('RIGHT') || mappedKey === 'SWIPE_RIGHT') swipeDirection = 'RIGHT';
    }

    const newBtn: VirtualButton = {
      id: freshId,
      label,
      x: 50,
      y: 50,
      width: defaultSize,
      height: defaultSize,
      mappedKey: mappedKey as any,
      androidEventCode,
      player: activePlayer,
      type,
      opacity: 80,
      deadzone: type === 'analog_stick' ? 0.15 : undefined,
      sensitivity: type === 'analog_stick' ? 1.0 : undefined,
      tapDuration: type === 'swipe' ? 30 : undefined,
      swipeDirection,
    };
    const current = profileRef.current;
    onUpdateProfile({ ...current, buttons: [...current.buttons, newBtn] });
    setSelectedButtonId(freshId);
    setShowPalette(false);
  };

  // BUG-O6 FIX: Pass correct androidEventCode for swipe directions (201=UP, 202=DOWN, 203=LEFT, 204=RIGHT).
  const handleAddNewButton = (type: VirtualButton['type'] = 'button', defaultLabel: string = 'New', eventCode: number = 0) => {
    let mappedKey = 'A';
    let code = eventCode;
    if (type === 'swipe') {
      const dir = defaultLabel.toUpperCase();
      if (dir === 'UP') { mappedKey = 'SWIPE_UP'; code = 201; }
      else if (dir === 'DOWN') { mappedKey = 'SWIPE_DOWN'; code = 202; }
      else if (dir === 'LEFT') { mappedKey = 'SWIPE_LEFT'; code = 203; }
      else if (dir === 'RIGHT') { mappedKey = 'SWIPE_RIGHT'; code = 204; }
    }
    handleAddSpecificButton(defaultLabel, mappedKey, code, type === 'swipe' ? 80 : 56, type);
  };

  const handleRemoveButton = (id: string) => {
    const current = profileRef.current;
    onUpdateProfile({ ...current, buttons: current.buttons.filter(b => b.id !== id) });
    setSelectedButtonId(null);
  };

  const getBackgroundUrl = () => {
    if (screenshotMode === 'custom' && customScreenshotUrl) return customScreenshotUrl;
    // Use CSS gradient as placeholder — no external network needed (Capacitor WebView CSP safe)
    if (screenshotMode === 'genshin') return 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)';
    if (screenshotMode === 'pubg') return 'linear-gradient(135deg, #1a1a1a 0%, #2d2d2d 50%, #1a1a1a 100%)';
    if (screenshotMode === 'codm') return 'linear-gradient(135deg, #0d1b2a 0%, #1b263b 50%, #0d1b2a 100%)';
    if (screenshotMode === 'efootball') return 'linear-gradient(135deg, #144552 0%, #0d2d3a 50%, #144552 100%)';
    return 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)';
  };

  React.useEffect(() => {
    if (isNativeOverlay && typeof window !== 'undefined' && (window as any).AndroidOverlay) {
      (window as any).AndroidOverlay.setInteractive(showPalette);
    }
  }, [showPalette, isNativeOverlay]);

  React.useEffect(() => {
    (window as any).togglePalette = (isOpen: boolean) => {
      setShowPalette(isOpen);
      if (!isOpen) {
        setSelectedButtonId(null);
      }
    };
    return () => {
      delete (window as any).togglePalette;
    };
  }, []);

  return {
    activeProfile, onUpdateProfile, onLogMessage, activeKeys, activeAxes, isNativeOverlay,
    showConfig, setShowConfig, selectedButtonId, setSelectedButtonId, screenshotMode, setScreenshotMode,
    customScreenshotUrl, setCustomScreenshotUrl, fileInputRef, isDragging, setIsDragging, nexionPos, setNexionPos,
    isDraggingNexion, setIsDraggingNexion, isDraggingNexionRef, nexionDragHasMoved, showPalette, setShowPalette,
    activePlayer, setActivePlayer, hideGrid, setHideGrid, hideAllNodes, setHideAllNodes,
    bgDimLevel, setBgDimLevel, globalNodeOpacity, setGlobalNodeOpacity, selectedButton,
    screenshotDimensions, setScreenshotDimensions, contentRect, handleBackgroundImageLoad,
    handleContainerClick, handleDragStart, handleDragMove, handleDragEnd, handleUpdateBtnProperty,
    handleUpdateBtnProperties, relocateButtonOffset, handleAddSpecificButton, handleAddNewButton, handleRemoveButton, getBackgroundUrl
  };
}
