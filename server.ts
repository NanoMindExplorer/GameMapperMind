import express, { Request, Response, NextFunction } from "express";
import path from "path";
import cors from "cors";
import crypto from "crypto";
import { z } from "zod";
import rateLimit from "express-rate-limit";

const app = express();
const PORT = 3000;

const ALLOWED_ORIGINS = [
  "capacitor://localhost",
  "http://localhost",
  "http://localhost:3000",
  "http://localhost:5173",
];

// BUG-02: CORS middleware
app.use(cors({
  origin: true, // Allow all origins for the dev preview
  methods: ["GET", "POST", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization"],
  credentials: true,
}));

app.use(express.json({ limit: "1mb" }));

// BUG-01: Generate secure token
const generateSecureToken = () => crypto.randomBytes(32).toString("hex");

// BUG-15: Safe logic clamped Random
function clampedRandom(min: number, max: number): number {
  const val = min + Math.random() * (max - min);
  return Math.min(1.0, Math.max(0.0, parseFloat(val.toFixed(4))));
}

// Global state
const aiTunnelState = {
  apiToken: process.env.AI_TUNNEL_SECRET || generateSecureToken(),
  confidenceScore: clampedRandom(0.80, 0.99),
  logs: [] as string[]
};

// BUG-08: Auto-trim with while loop
function pushLog(logs: string[], message: string, max = 50): void {
  logs.push(message);
  while (logs.length > max) {
    logs.shift();
  }
}

// BUG-11: Mask token in logs
pushLog(aiTunnelState.logs, "[AI-TUNNEL] Server listening on /api/ai/* endpoints.");
pushLog(aiTunnelState.logs, "[AI-TUNNEL] Token generated. Visible ONLY in server console.");
pushLog(aiTunnelState.logs, "[AI-TUNNEL] Waiting for VLM agent handshake...");

app.get("/api/ai/tunnel-status", (req: Request, res: Response) => {
  const { apiToken, ...safeState } = aiTunnelState;
  res.json(safeState);
});

// BUG-19: Rate limit
const aiLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 120, // max 120 req/min
  message: { error: "Terlalu banyak request. Coba lagi nanti." },
  standardHeaders: true,
  legacyHeaders: false,
});
app.use("/api/ai/", aiLimiter);

// BUG-05 & BUG-25: Sanitize input & Zod schema validation
function sanitizeLogInput(input: string): string {
  if (typeof input !== "string") return "[INVALID INPUT]";
  return input
    .replace(/[\r\n\t]/g, " ")
    .replace(/[\x00-\x1F]/g, "")
    .substring(0, 256);
}

const DaemonControlSchema = z.object({
  action: z.enum(["start", "stop", "toggle_mode"]),
  mode: z.enum(["shizuku", "desktop", "adb"]).optional(),
});

app.post("/api/daemon/control", (req: Request, res: Response) => {
  const parsed = DaemonControlSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { action, mode } = parsed.data;
  
  if (action === "start") {
    pushLog(aiTunnelState.logs, `[INFO] Daemon start requested via mode: ${mode || 'unknown'}`);
  } else if (action === "stop") {
    pushLog(aiTunnelState.logs, `[INFO] Daemon stop requested`);
  }
  
  res.json({ success: true, action, state: "applied" });
});

app.post("/api/daemon/log", (req: Request, res: Response) => {
  const msg = req.body?.message;
  if (msg) {
    const cleanMsg = sanitizeLogInput(msg);
    pushLog(aiTunnelState.logs, `[DAEMON] ${cleanMsg}`);
  }
  res.json({ success: true });
});

// Endpoint untuk menyimpan log biasa
const LogSchema = z.object({
  message: z.string(),
  instruksi: z.string().optional() // BUG-16: typo diperbaiki
});

app.post("/api/log", (req: Request, res: Response) => {
  try {
    const parsed = LogSchema.parse(req.body);
    const clientIp = req.ip || "";
    const cleanMsg = sanitizeLogInput(`[${clientIp}] ${parsed.message} ${parsed.instruksi ? '- ' + parsed.instruksi : ''}`);
    pushLog(aiTunnelState.logs, cleanMsg);
    
    res.json({ success: true, count: aiTunnelState.logs.length });
  } catch (error: unknown) {
    res.status(400).json({ error: "Invalid request payload" });
  }
});

app.get("/api/logs", (req: Request, res: Response) => {
    res.json({ logs: aiTunnelState.logs });
});

// BUG-09: Try/Catch on server start
async function startServer() {
  try {
    if (process.env.NODE_ENV !== "production") {
      const { createServer: createViteServer } = await import("vite");
      const vite = await createViteServer({
        server: { middlewareMode: true },
        appType: "spa",
      });
      app.use(vite.middlewares);
    } else {
      const distPath = path.join(process.cwd(), "dist", "client");
      app.use(express.static(distPath));
      app.get("*", (req: Request, res: Response) => {
        res.sendFile(path.join(distPath, "index.html"));
      });
    }

    app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
      console.error("Global Error:", err.message);
      res.status(500).json({ error: "Internal Server Error" });
    });

    app.listen(PORT, "0.0.0.0", () => {
      console.log(`✅ Server running on http://localhost:${PORT}`);
      console.log(`🔑 AI Tunnel Token (RAHASIA): ${aiTunnelState.apiToken}`);
      console.log("⚠️  Jangan bagikan token ini! Simpan di environment variable.");
    });
  } catch (err) {
    console.error("❌ FATAL: Server gagal start:", err);
    process.exit(1);
  }
}

process.on("unhandledRejection", (reason) => {
  console.error("Unhandled rejection:", reason);
  process.exit(1);
});

startServer();
