const fs = require('fs');

const orig = fs.readFileSync('src/components/_OverlayWysiwyg.backup', 'utf-8');

const jsxMatch = orig.match(/return \([\s\S]+?\);/);
const jsx = jsxMatch[0];

// I can just find the indices and slice
// ProfileToolbar: <div className="flex flex-wrap justify-between items-center gap-3 mb-4"> to </div> </div> </> )}
// ScreenshotBackground: <div className={isNativeOverlay ? "w-screen h-screen overflow-hidden" : "bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl grid grid-cols-1 lg:grid-cols-12"}>
// It's much easier to just do it manually with edit_file.
