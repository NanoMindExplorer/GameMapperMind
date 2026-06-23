const fs = require('fs');
const orig = fs.readFileSync('src/components/OverlayWysiwyg.tsx', 'utf-8');

const s1 = orig.indexOf('<div className="flex flex-wrap justify-between items-center gap-3 mb-4">');
const e1 = orig.indexOf('{/* Visual Canvas stage Area (Col 9) */}');
const profileToolbarJSX = orig.slice(s1, e1).trim();

const s2 = orig.indexOf('getBackgroundUrl()');
// Actually, ScreenshotBackground wraps the Canvas. Let's find exactly.
const b1 = orig.indexOf('<div className={isNativeOverlay ? "w-screen h-screen overflow-hidden" : "bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl grid grid-cols-1 lg:grid-cols-12"}>');
const b2 = orig.indexOf('{/* Controller Parameters (Col 4) */}');
const canvasAreaJSX = orig.slice(b1, b2).trim();

const p1 = orig.indexOf('{/* Controller Parameters (Col 4) */}');
const p2 = orig.indexOf('</form>');
const propPanelJSX = orig.slice(p1, p2 + 7).trim(); // + form and closing divs...

const pal1 = orig.indexOf('{/* Canvas Overlay Gamepad Palette */}');
const pal2 = orig.indexOf('{/* Button configurations */}', pal1);
const paletteJSX = orig.slice(pal1, pal2).trim();

// Because parsing brackets is hard in JS String indexOf, I will just generate functional components that wrap parts!
// But wait! If I just split and wrap, the original state was extracted to the hook. I can just rewrite the original file completely!
