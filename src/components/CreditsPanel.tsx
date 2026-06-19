import React from 'react';
import { Send, Instagram, Twitter, MessageSquare, Youtube, Heart, Code } from 'lucide-react';

export default function CreditsPanel() {
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
            Connect & Support
          </h1>
          <p className="text-slate-400 max-w-lg mx-auto leading-relaxed">
            Thank you for using Game Mapper! Join our community, report issues, or just hang out with us on our social platforms.
          </p>
        </div>

        {/* Social Links Grid */}
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
              <div className="flex-1">
                <h3 className="font-semibold">{social.name}</h3>
                <p className="text-xs opacity-70 mt-1 truncate max-w-[200px] sm:max-w-[250px]">
                  {social.url}
                </p>
              </div>
            </a>
          ))}
        </div>

        {/* Footer info */}
        <div className="mt-12 p-6 rounded-xl border border-slate-800 bg-slate-900/50 text-center">
          <Code className="w-6 h-6 text-slate-500 mx-auto mb-3" />
          <h4 className="text-sm font-medium text-slate-300">Open Source & Community Driven</h4>
          <p className="text-xs text-slate-500 mt-2">
            Build with passion for mobile gamers.
          </p>
        </div>
      </div>
    </div>
  );
}
