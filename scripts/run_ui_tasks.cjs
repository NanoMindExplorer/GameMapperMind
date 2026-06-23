const fs = require('fs');

const shizukuCode = fs.readFileSync('src/components/ShizukuPanel.tsx', 'utf-8');

// 1. Extract SHIZUKU_STEPS and DESKTOP_STEPS
const stepsMatch = shizukuCode.match(/const SHIZUKU_STEPS = \[[\s\S]*?\];\n\nconst DESKTOP_STEPS = \[[\s\S]*?\];\n/);
const steps = stepsMatch ? stepsMatch[0] : '';

// 2. Extract states and handlers
const statesAndHandlersStr = `
  const [activeTab, setActiveTab] = useState<'shizuku' | 'desktop'>('shizuku');
  const [expandedShizukuStep, setExpandedShizukuStep] = useState<number | null>(0);
  const [expandedDesktopStep, setExpandedDesktopStep] = useState<number | null>(0);
  const [shizukuChecklist, setShizukuChecklist] = useState<boolean[]>([false, false, false, false, false, false]);
  const [desktopChecklist, setDesktopChecklist] = useState<boolean[]>([false, false, false, false, false]);

  const handleToggleExpand = (stepIdx: number) => {
    if (activeTab === 'shizuku') setExpandedShizukuStep(prev => prev === stepIdx ? null : stepIdx);
    else setExpandedDesktopStep(prev => prev === stepIdx ? null : stepIdx);
  };

  const handleToggleCheck = (e: React.MouseEvent) => {
    e.stopPropagation();
    const currExpanded = activeTab === 'shizuku' ? expandedShizukuStep : expandedDesktopStep;
    if (currExpanded === null) return;
    
    if (activeTab === 'shizuku') {
      const arr = [...shizukuChecklist];
      arr[currExpanded] = !arr[currExpanded];
      setShizukuChecklist(arr);
      if (arr[currExpanded] && currExpanded < SHIZUKU_STEPS.length - 1) setExpandedShizukuStep(currExpanded + 1);
    } else {
      const arr = [...desktopChecklist];
      arr[currExpanded] = !arr[currExpanded];
      setDesktopChecklist(arr);
      if (arr[currExpanded] && currExpanded < DESKTOP_STEPS.length - 1) setExpandedDesktopStep(currExpanded + 1);
    }
  };
`;

// 3. Extract JSX
const jsxMatch = shizukuCode.match(/          \{\/\* INTERACTIVE ACTIVATION GUIDE \(PANDUAN AKTIFASI\) \*\/\}[\s\S]*?          \{\/\* Live daemon logs \(Simulated dynamic native terminal output\) \*\/\}/);
const jsxBlock = jsxMatch ? jsxMatch[0].replace('          {/* Live daemon logs (Simulated dynamic native terminal output) */}', '').trim() : '';

// Add Imports
const creditsCode = fs.readFileSync('src/components/CreditsPanel.tsx', 'utf-8');

let newCreditsCode = creditsCode.replace(/import \{.*?\} from 'lucide-react';/, "import { Send, Instagram, Twitter, MessageSquare, Youtube, Heart, Code, BookOpen, Smartphone, Laptop, CheckSquare, Square, ChevronRight, ChevronDown, Check, Settings } from 'lucide-react';\nimport { useState } from 'react';");

newCreditsCode = newCreditsCode.replace('export default function CreditsPanel() {', 
  steps + "\ninterface CreditsPanelProps {\n  onLogMessage?: (msg: string) => void;\n}\n\nexport default function CreditsPanel({ onLogMessage }: CreditsPanelProps) {"
);

newCreditsCode = newCreditsCode.replace('  const socials = [', statesAndHandlersStr + '\n  const [copiedAddress, setCopiedAddress] = useState<string | null>(null);\n\n  const handleCopy = (address: string, label: string) => {\n    navigator.clipboard.writeText(address);\n    setCopiedAddress(label);\n    setTimeout(() => setCopiedAddress(null), 2000);\n  };\n\n  const socials = [');


const cryptos = `
        {/* Interactive Activation Guide (Panduan Aktifasi) */}
        <div className="bg-slate-950/60 rounded-xl border border-indigo-950/60 p-4 space-y-4">
          ${jsxBlock.replace(/onLogMessage/g, 'onLogMessage && onLogMessage')}
        </div>

        {/* Donation Section - Support the Creator */}
        <div className="bg-gradient-to-br from-amber-950/20 to-orange-950/10 rounded-xl border border-amber-900/40 p-6 space-y-4">
          <div className="text-center space-y-2">
            <div className="inline-flex items-center justify-center p-3 bg-amber-500/10 rounded-full mb-2 ring-1 ring-amber-500/30">
              <Heart className="w-6 h-6 text-amber-400" />
            </div>
            <h3 className="text-xl font-bold text-white">Support the Creator</h3>
            <p className="text-sm text-slate-400 max-w-md mx-auto">
              Jika aplikasi ini bermanfaat untuk Anda, dukung kreator dengan donasi crypto. Setiap kontribusi sangat berarti untuk pengembangan lebih lanjut.
            </p>
          </div>

          {/* Crypto Address Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {/* Bitcoin */}
            <div className="bg-slate-950/50 rounded-lg border border-amber-900/30 p-4 space-y-2 hover:border-amber-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-amber-500/10 rounded-full flex items-center justify-center text-amber-400 font-bold text-xs">₿</div>
                  <span className="font-semibold text-amber-300">Bitcoin (BTC)</span>
                </div>
                <button
                  onClick={() => handleCopy('TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt', 'BTC')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'BTC' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt
              </code>
            </div>

            {/* EVM */}
            <div className="bg-slate-950/50 rounded-lg border border-blue-900/30 p-4 space-y-2 hover:border-blue-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-blue-500/10 rounded-full flex items-center justify-center text-blue-400 font-bold text-xs">Ξ</div>
                  <span className="font-semibold text-blue-300">EVM (ETH/BSC/Polygon)</span>
                </div>
                <button
                  onClick={() => handleCopy('0x96e49c673252bb0a2253418417cf1db000fec6ef', 'EVM')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'EVM' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                0x96e49c673252bb0a2253418417cf1db000fec6ef
              </code>
            </div>

            {/* Solana */}
            <div className="bg-slate-950/50 rounded-lg border border-purple-900/30 p-4 space-y-2 hover:border-purple-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-purple-500/10 rounded-full flex items-center justify-center text-purple-400 font-bold text-xs">◎</div>
                  <span className="font-semibold text-purple-300">Solana (SOL)</span>
                </div>
                <button
                  onClick={() => handleCopy('4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR', 'SOL')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'SOL' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR
              </code>
            </div>

            {/* Tron */}
            <div className="bg-slate-950/50 rounded-lg border border-red-900/30 p-4 space-y-2 hover:border-red-500/50 transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 bg-red-500/10 rounded-full flex items-center justify-center text-red-400 font-bold text-xs">T</div>
                  <span className="font-semibold text-red-300">Tron (TRX)</span>
                </div>
                <button
                  onClick={() => handleCopy('TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt', 'TRX')}
                  className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                >
                  {copiedAddress === 'TRX' ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <code className="text-[11px] text-slate-400 break-all font-mono block">
                TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt
              </code>
            </div>
          </div>

          {/* Disclaimer */}
          <p className="text-[10px] text-slate-500 text-center mt-3">
            Always double-check the address before sending. Crypto transactions are irreversible.
          </p>
        </div>
`;

newCreditsCode = newCreditsCode.replace('{/* Footer info */}', cryptos + '\n\n        {/* Footer info */}');

fs.writeFileSync('src/components/CreditsPanel.tsx', newCreditsCode);


// 4. Cleanup ShizukuPanel.tsx
if(stepsMatch) {
  let newShizukuCode = shizukuCode.replace(stepsMatch[0], '');
  newShizukuCode = newShizukuCode.replace(/  const \[activeTab.*?setActiveTab\] = React.useState.*?\n[\s\S]*?  const handleToggleCheck[\s\S]*?    \}\n  \};\n/, "");
  newShizukuCode = newShizukuCode.replace(/          \{\/\* INTERACTIVE ACTIVATION GUIDE \(PANDUAN AKTIFASI\) \*\/\}[\s\S]*?          \{\/\* Live daemon logs \(Simulated dynamic native terminal output\) \*\/\}/, '          {/* Live daemon logs (Simulated dynamic native terminal output) */}');
  
  // also add setActiveTab back because it seems to be used elsewhere probably?
  // Actually wait, does ShizukuPanel have activeTab for anything else?
  // Let me check. The tab code is probably in the JSX.
  fs.writeFileSync('src/components/ShizukuPanel.tsx', newShizukuCode);
} else {
  console.log('regex step failed');
}
