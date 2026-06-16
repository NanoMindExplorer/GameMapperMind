import React from 'react';
import {
  Send, Instagram, Twitter, MessageSquare, Youtube, Heart, Code,
  Bitcoin, Copy, Check, ExternalLink, Coins, Sparkles, Wallet
} from 'lucide-react';

// ============================================================
// Crypto donation addresses — feel free to extend.
// ============================================================
const CRYPTO_ADDRESSES = [
  {
    id: 'bitcoin',
    name: 'Bitcoin',
    symbol: 'BTC',
    address: 'bc1pt9lqxy0vnhrk0d2trn25j47hqm6y26t7ckzfw5hygphnt0rk94es77suv2',
    network: 'Bitcoin Mainnet (Bech32m / Taproot)',
    color: 'bg-orange-500/10 text-orange-400 border-orange-500/30 hover:border-orange-500/60',
    accent: '#f97316',
    icon: Bitcoin,
    explorerUrl: 'https://blockchair.com/bitcoin/address/',
  },
  {
    id: 'evm',
    name: 'EVM (Ethereum / BSC / Polygon / Base / Arbitrum)',
    symbol: 'ETH/BNB/MATIC',
    address: '0x96e49c673252bb0a2253418417cf1db000fec6ef',
    network: 'EVM-compatible chains (Ethereum, BNB Smart Chain, Polygon, Base, Arbitrum, Optimism)',
    color: 'bg-indigo-500/10 text-indigo-400 border-indigo-500/30 hover:border-indigo-500/60',
    accent: '#6366f1',
    icon: Wallet,
    explorerUrl: 'https://etherscan.io/address/',
  },
  {
    id: 'solana',
    name: 'Solana',
    symbol: 'SOL',
    address: '4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR',
    network: 'Solana Mainnet',
    color: 'bg-purple-500/10 text-purple-400 border-purple-500/30 hover:border-purple-500/60',
    accent: '#a855f7',
    icon: Sparkles,
    explorerUrl: 'https://solscan.io/account/',
  },
  {
    id: 'tron',
    name: 'Tron',
    symbol: 'TRX',
    address: 'TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt',
    network: 'Tron Network (TRC-20)',
    color: 'bg-rose-500/10 text-rose-400 border-rose-500/30 hover:border-rose-500/60',
    accent: '#f43f5e',
    icon: Coins,
    explorerUrl: 'https://tronscan.org/#/address/',
  },
];

// Helper: truncate long address for display.
// Example: "bc1pt9lqxy0vn...w5hygphnt0rk94es77suv2"
function truncateAddress(addr: string, head = 14, tail = 12): string {
  if (addr.length <= head + tail + 3) return addr;
  return `${addr.slice(0, head)}…${addr.slice(-tail)}`;
}

// Helper: copy text with fallback for older WebView (Capacitor Android)
async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (e) {
    console.warn('Clipboard API failed, falling back to execCommand', e);
  }
  // Fallback: temporary textarea + execCommand
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    ta.style.top = '0';
    ta.setAttribute('readonly', '');
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch (e) {
    console.error('execCommand copy failed', e);
    return false;
  }
}

export default function CreditsPanel() {
  // Tracks which address was just copied (for visual feedback)
  const [copiedId, setCopiedId] = React.useState<string | null>(null);
  // Tracks expanded state per address (show full instead of truncated)
  const [expandedId, setExpandedId] = React.useState<string | null>(null);

  const handleCopy = async (id: string, address: string) => {
    const ok = await copyToClipboard(address);
    if (ok) {
      setCopiedId(id);
      // Auto-reset after 2 seconds
      setTimeout(() => setCopiedId(prev => (prev === id ? null : prev)), 2000);
    }
  };

  const socials = [
    {
      name: "Telegram Chat Room",
      url: "https://t.me/oxnlyfams",
      icon: <Send className="w-5 h-5" />,
      color: "bg-blue-500/10 text-blue-400 border-blue-500/20 hover:border-blue-500/50 hover:bg-blue-500/20"
    },
    {
      name: "Instagram",
      url: "https://www.instagram.com/low_and.high?igsh=dXUyMjN1anp5Ymc5",
      icon: <Instagram className="w-5 h-5" />,
      color: "bg-pink-500/10 text-pink-400 border-pink-500/20 hover:border-pink-500/50 hover:bg-pink-500/20"
    },
    {
      name: "X (Twitter)",
      url: "https://x.com/Deadmouse_jpeg",
      icon: <Twitter className="w-5 h-5" />,
      color: "bg-slate-800/50 text-slate-300 border-slate-700 hover:border-slate-500 hover:bg-slate-800"
    },
    {
      name: "Discord",
      url: "https://discord.gg/CrG6Hxm8XZ",
      icon: <MessageSquare className="w-5 h-5" />,
      color: "bg-indigo-500/10 text-indigo-400 border-indigo-500/20 hover:border-indigo-500/50 hover:bg-indigo-500/20"
    },
    {
      name: "YouTube",
      url: "https://www.youtube.com/@Bakayaro_0",
      icon: <Youtube className="w-5 h-5" />,
      color: "bg-red-500/10 text-red-400 border-red-500/20 hover:border-red-500/50 hover:bg-red-500/20"
    }
  ];

  return (
    <div className="flex-1 p-6 overflow-y-auto custom-scrollbar bg-slate-950">
      <div className="max-w-4xl mx-auto space-y-8">
        {/* Header Section */}
        <div className="text-center space-y-4 py-8">
          <div className="inline-flex items-center justify-center p-4 bg-indigo-500/10 rounded-full mb-4 ring-1 ring-indigo-500/30">
            <Heart className="w-8 h-8 text-indigo-400" />
          </div>
          <h1 className="text-3xl font-bold font-sans tracking-tight text-white">
            Connect &amp; Support
          </h1>
          <p className="text-slate-400 max-w-lg mx-auto leading-relaxed">
            GameMapperMind is <strong className="text-slate-200">100% free and open source</strong>.
            Join our community, report issues, or support the developer with a crypto donation.
          </p>
        </div>

        {/* ============================================================ */}
        {/* Crypto Donation Section                                      */}
        {/* ============================================================ */}
        <div className="space-y-4">
          <div className="flex items-center gap-2 text-xs uppercase tracking-widest text-slate-500 font-bold">
            <Wallet className="w-4 h-4" />
            Support the Developer (Crypto)
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {CRYPTO_ADDRESSES.map((crypto) => {
              const Icon = crypto.icon;
              const isCopied = copiedId === crypto.id;
              const isExpanded = expandedId === crypto.id;
              const displayAddress = isExpanded ? crypto.address : truncateAddress(crypto.address);

              return (
                <div
                  key={crypto.id}
                  className={`p-5 rounded-xl border transition-all duration-300 ${crypto.color}`}
                >
                  {/* Header: icon + name + symbol */}
                  <div className="flex items-center gap-3 mb-3">
                    <div
                      className="p-2.5 bg-slate-950/50 rounded-lg"
                      style={{ boxShadow: `0 0 12px ${crypto.accent}20` }}
                    >
                      <Icon className="w-5 h-5" style={{ color: crypto.accent }} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-slate-100 leading-tight">{crypto.name}</h3>
                      <p className="text-[10px] uppercase tracking-wider text-slate-500 font-mono mt-0.5">
                        {crypto.symbol}
                      </p>
                    </div>
                    {isCopied && (
                      <div className="flex items-center gap-1 text-[10px] font-bold uppercase text-emerald-400 bg-emerald-950/40 px-2 py-1 rounded border border-emerald-500/30 animate-pulse">
                        <Check className="w-3 h-3" /> Copied
                      </div>
                    )}
                  </div>

                  {/* Address block — clickable to expand, copyable */}
                  <div className="space-y-2">
                    <div className="text-[10px] text-slate-500 uppercase tracking-wider font-semibold">
                      {crypto.network}
                    </div>
                    <button
                      type="button"
                      onClick={() => setExpandedId(isExpanded ? null : crypto.id)}
                      title={isExpanded ? 'Click to collapse' : 'Click to show full address'}
                      className="w-full text-left bg-slate-950/60 hover:bg-slate-950 border border-slate-800 hover:border-slate-700 rounded-lg p-3 transition-colors group"
                    >
                      <code className={`block font-mono text-xs ${isExpanded ? 'text-slate-100 break-all' : 'text-slate-300 truncate'} select-all`}>
                        {displayAddress}
                      </code>
                      <div className="text-[9px] text-slate-500 mt-1.5 group-hover:text-slate-400 transition-colors">
                        {isExpanded ? '▼ Click to collapse' : '▶ Click to expand full address'}
                      </div>
                    </button>

                    {/* Action buttons: copy + view on explorer */}
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() => handleCopy(crypto.id, crypto.address)}
                        className={`flex-1 py-2 rounded-lg text-xs font-bold transition-all flex items-center justify-center gap-1.5 border ${
                          isCopied
                            ? 'bg-emerald-500 text-white border-emerald-400'
                            : 'bg-slate-900 hover:bg-slate-800 text-slate-200 border-slate-700'
                        }`}
                      >
                        {isCopied ? (
                          <>
                            <Check className="w-3.5 h-3.5" /> Copied!
                          </>
                        ) : (
                          <>
                            <Copy className="w-3.5 h-3.5" /> Copy Address
                          </>
                        )}
                      </button>
                      <a
                        href={`${crypto.explorerUrl}${crypto.address}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="py-2 px-3 rounded-lg text-xs font-bold bg-slate-900 hover:bg-slate-800 text-slate-300 hover:text-slate-100 border border-slate-700 transition-colors flex items-center gap-1.5"
                        title="View on blockchain explorer"
                      >
                        <ExternalLink className="w-3.5 h-3.5" />
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* QR code helper — link to external generator */}
          <div className="p-4 bg-slate-900/50 rounded-xl border border-slate-800 text-center">
            <p className="text-xs text-slate-400 leading-relaxed">
              💡 <strong className="text-slate-300">Tip:</strong> For QR codes, copy the address above and paste it into your wallet app —
              most modern crypto wallets auto-generate QR codes from pasted addresses. Alternatively, you can generate a QR
              visually at <a href="https://www.bitcoinqrcodemaker.com/" target="_blank" rel="noopener noreferrer" className="text-indigo-400 hover:text-indigo-300 underline">bitcoinqrcodemaker.com</a>.
            </p>
          </div>

          {/* Donation thank-you note */}
          <div className="p-4 bg-gradient-to-br from-indigo-950/30 to-pink-950/20 rounded-xl border border-indigo-900/40">
            <div className="flex items-start gap-3">
              <Heart className="w-5 h-5 text-pink-400 shrink-0 mt-0.5" />
              <div className="text-xs text-slate-300 leading-relaxed">
                <strong className="text-slate-100">Thank you for your support!</strong> Donations help cover
                development time, testing devices, and keep the project ad-free forever. Every contribution —
                no matter how small — means a lot and motivates continued development.
              </div>
            </div>
          </div>
        </div>

        {/* ============================================================ */}
        {/* Social Links Section                                         */}
        {/* ============================================================ */}
        <div className="space-y-4 pt-4 border-t border-slate-900">
          <div className="flex items-center gap-2 text-xs uppercase tracking-widest text-slate-500 font-bold">
            <Send className="w-4 h-4" />
            Community &amp; Socials
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {socials.map((social) => (
              <a
                key={social.name}
                href={social.url}
                target="_blank"
                rel="noopener noreferrer"
                className={`flex items-center gap-4 p-5 rounded-xl border transition-all duration-300 group ${social.color}`}
              >
                <div className="p-3 bg-slate-950/50 rounded-lg group-hover:scale-110 transition-transform">
                  {social.icon}
                </div>
                <div className="flex-1 min-w-0">
                  <h3 className="font-semibold">{social.name}</h3>
                  <p className="text-xs opacity-70 mt-1 truncate max-w-[200px] sm:max-w-[250px]">
                    {social.url}
                  </p>
                </div>
              </a>
            ))}
          </div>
        </div>

        {/* ============================================================ */}
        {/* Footer info                                                  */}
        {/* ============================================================ */}
        <div className="mt-8 p-6 rounded-xl border border-slate-800 bg-slate-900/50 text-center">
          <Code className="w-6 h-6 text-slate-500 mx-auto mb-3" />
          <h4 className="text-sm font-medium text-slate-300">Open Source &amp; Community Driven</h4>
          <p className="text-xs text-slate-500 mt-2">
            Built with passion for mobile gamers. Free forever — no ads, no tracking, no paywalls.
          </p>
          <div className="mt-4 text-[10px] text-slate-600 font-mono">
            🎮 GameMapperMind • © 2026 NanoMind Systems Inc.
          </div>
        </div>
      </div>
    </div>
  );
}
