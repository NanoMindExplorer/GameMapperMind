import express, { Request, Response, NextFunction } from "express";
import path from "path";
import cors from "cors";
import crypto from "crypto";
import { z } from "zod";
import rateLimit from "express-rate-limit";
import fs from "fs";
import { SimAction, MacroProfile, SafeAiTunnelState } from "./src/types/simulation.js";

// Port config via env var
const PORT = parseInt(process.env.PORT ?? '3000', 10);

const ADMIN_TOKEN_FILE = path.join(process.cwd(), ".admin_token");
let ADMIN_TOKEN = process.env.ADMIN_TOKEN as string;

if (!ADMIN_TOKEN) {
    if (fs.existsSync(ADMIN_TOKEN_FILE)) {
        ADMIN_TOKEN = fs.readFileSync(ADMIN_TOKEN_FILE, "utf-8").trim();
    } else {
        // Fallback: generate and save
        ADMIN_TOKEN = crypto.randomBytes(32).toString("hex");
        fs.writeFileSync(ADMIN_TOKEN_FILE, ADMIN_TOKEN, { mode: 0o600 });
        console.log("[SECURITY] Generated new ADMIN_TOKEN, saved to .admin_token (mode 0600)");
    }
} else {
    console.log("[SECURITY] ADMIN_TOKEN loaded from environment");
}

if (!ADMIN_TOKEN || ADMIN_TOKEN.length < 32) {
    console.error("ADMIN_TOKEN required and must be at least 32 characters.");
    process.exit(1);
}

const DATA_FILE = path.join(process.cwd(), "app_data.json");

interface PersistedState {
    logs: string[];
    macros: MacroProfile[];
    apiToken: string | null;
}

const defaultState: PersistedState = {
    logs: [],
    macros: [],
    apiToken: null
};

// Generate separate DATA_ENCRYPTION_KEY on first run, save to .data_key (gitignored)
const DATA_KEY_FILE = path.join(process.cwd(), ".data_key");
let DATA_ENCRYPTION_KEY: Buffer;

if (process.env.DATA_ENCRYPTION_KEY) {
  DATA_ENCRYPTION_KEY = Buffer.from(process.env.DATA_ENCRYPTION_KEY, 'hex');
} else if (fs.existsSync(DATA_KEY_FILE)) {
  DATA_ENCRYPTION_KEY = fs.readFileSync(DATA_KEY_FILE);
} else {
  DATA_ENCRYPTION_KEY = crypto.randomBytes(32);
  fs.writeFileSync(DATA_KEY_FILE, DATA_ENCRYPTION_KEY, { mode: 0o600 });
}

// C03: AES-256-GCM Encryption
const ALGORITHM = 'aes-256-gcm';
const ENCRYPTION_KEY = DATA_ENCRYPTION_KEY;

class StateStore {
    public static state: PersistedState = { ...defaultState };

    public static async load(): Promise<void> {
        try {
            if (fs.existsSync(DATA_FILE)) {
                let data = await fs.promises.readFile(DATA_FILE, 'utf-8');
                try {
                    // Try to decrypt if it looks like a JSON with iv and authTag
                    const parsedData = JSON.parse(data);
                    if (parsedData.iv && parsedData.authTag && parsedData.encryptedData) {
                        const iv = Buffer.from(parsedData.iv, 'hex');
                        const authTag = Buffer.from(parsedData.authTag, 'hex');
                        const decipher = crypto.createDecipheriv(ALGORITHM, ENCRYPTION_KEY, iv);
                        decipher.setAuthTag(authTag);
                        let decrypted = decipher.update(parsedData.encryptedData, 'hex', 'utf-8');
                        decrypted += decipher.final('utf-8');
                        data = decrypted;
                    }
                } catch(e) {
                    // fall back to plain JSON for backwards compatibility
                }
                this.state = JSON.parse(data, (key, value) => value);
            } else {
              this.state.apiToken = crypto.randomBytes(32).toString("hex");
              await this.save();
            }
        } catch (error) {
            console.error("Failed to load state from JSON, using defaults:", error);
            this.state = { ...defaultState };
        }
    }

    public static async save(): Promise<void> {
        try {
            const rawJson = JSON.stringify(this.state, null, 2);
            const iv = crypto.randomBytes(12);
            const cipher = crypto.createCipheriv(ALGORITHM, ENCRYPTION_KEY, iv);
            let encrypted = cipher.update(rawJson, 'utf-8', 'hex');
            encrypted += cipher.final('hex');
            const authTag = cipher.getAuthTag();
            
            const encryptedPayload = {
                iv: iv.toString('hex'),
                authTag: authTag.toString('hex'),
                encryptedData: encrypted
            };
            
            await fs.promises.writeFile(DATA_FILE, JSON.stringify(encryptedPayload, null, 2), 'utf-8');
        } catch (error) {
            console.error("Failed to save state to JSON:", error);
        }
    }
}

// Ensure state is loaded early
// await StateStore.load(); // Since it's a module, await works at the top level in node >= 14 with ESM, but we are inside CJS via esbuild maybe?
// Wait, to be safe, I'll load it inside startServer.

const app = express();

app.use(cors({
  origin: ['http://localhost:5173', 'http://localhost:3000', 'capacitor://localhost', 'http://127.0.0.1:3000', 'http://127.0.0.1:5173'],
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization'],
  credentials: true,
}));

app.use(express.json({ limit: "5mb" }));

function addLog(arr: string[], msg: string, max = 500): void {
  arr.push(msg);
  while (arr.length > max) {
    arr.shift();
  }
}

const requireAuth = (req: Request, res: Response, next: NextFunction) => {
  const bearer = req.headers.authorization?.split(' ')[1];
  if (!bearer || bearer !== ADMIN_TOKEN) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }
  next();
};

const aiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  message: { error: "Terlalu banyak request. Coba lagi nanti." },
  standardHeaders: true,
  legacyHeaders: false,
});

app.use("/api/ai/", aiLimiter);

app.get("/api/ai/tunnel-status", (req: Request, res: Response) => {
  const safeState: SafeAiTunnelState = {
    logs: StateStore.state.logs
  };
  res.json(safeState);
});

app.post("/api/ai/tunnel-control", requireAuth, (req: Request, res: Response) => {
  addLog(StateStore.state.logs, "[INFO] Tunnel control accessed");
  StateStore.save();
  res.json({ success: true });
});

app.post("/api/ai/kill-switch", requireAuth, (req: Request, res: Response) => {
  addLog(StateStore.state.logs, "[CRITICAL] Kill Switch Activated!");
  StateStore.save();
  res.json({ success: true });
});


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

app.post("/api/daemon/control", requireAuth, (req: Request, res: Response) => {
  const parsed = DaemonControlSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { action, mode } = parsed.data;
  
  if (action === "start") {
    addLog(StateStore.state.logs, `[INFO] Daemon start requested via mode: ${mode || 'unknown'}`);
  } else if (action === "stop") {
    addLog(StateStore.state.logs, `[INFO] Daemon stop requested`);
  }
  
  StateStore.save();
  res.json({ success: true, action, state: "applied" });
});

app.post("/api/daemon/log", requireAuth, (req: Request, res: Response) => {
  const msg = req.body?.message;
  if (msg) {
    const cleanMsg = sanitizeLogInput(msg);
    addLog(StateStore.state.logs, `[DAEMON] ${cleanMsg}`);
    StateStore.save();
  }
  res.json({ success: true });
});

// Endpoint kalibrasi gyro palsu diganti yang sesungguhnya di app (Mandat 14), endpoint ini cuma untuk testing auth
app.post("/api/daemon/calibrate", requireAuth, (req: Request, res: Response) => {
  res.json({ success: true, message: 'Calibrated payload received' });
});

const LogSchema = z.object({
  message: z.string(),
  instruksi: z.string().optional() });

const logLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  message: { error: "Terlalu banyak log request. Coba lagi nanti." },
  standardHeaders: true,
  legacyHeaders: false,
});

app.use("/api/log", logLimiter);

// Health check endpoint
app.get("/api/health", (req: Request, res: Response) => {
  res.json({ status: "ok", timestamp: Date.now() });
});

app.post("/api/log", requireAuth, (req: Request, res: Response) => {
  try {
    const parsed = LogSchema.parse(req.body);
    const clientIp = req.ip || "";
    const cleanMsg = sanitizeLogInput(`[${clientIp}] ${parsed.message} ${parsed.instruksi ? '- ' + parsed.instruksi : ''}`);
    addLog(StateStore.state.logs, cleanMsg);
    StateStore.save();
    
    res.json({ success: true, count: StateStore.state.logs.length });
  } catch (error: unknown) {
    res.status(400).json({ error: "Invalid request payload" });
  }
});

app.get("/api/logs", requireAuth, (req: Request, res: Response) => {
    res.json({ logs: StateStore.state.logs });
});

// MANDAT 15: MACRO PERSISTENCE ENDPOINTS
app.get("/api/macros", requireAuth, (req: Request, res: Response) => {
    res.json(StateStore.state.macros);
});

app.post("/api/macros", requireAuth, (req: Request, res: Response) => {
    const newMacro: MacroProfile = req.body;
    StateStore.state.macros.push(newMacro);
    StateStore.save();
    res.json({ success: true, macro: newMacro });
});

app.put("/api/macros/:id", requireAuth, (req: Request, res: Response) => {
    const { id } = req.params;
    const index = StateStore.state.macros.findIndex(m => m.id === id);
    if (index === -1) {
       res.status(404).json({ error: "Not found" });
       return;
    }
    StateStore.state.macros[index] = req.body;
    StateStore.save();
    res.json({ success: true, macro: StateStore.state.macros[index] });
});

app.delete("/api/macros/:id", requireAuth, (req: Request, res: Response) => {
    const { id } = req.params;
    StateStore.state.macros = StateStore.state.macros.filter(m => m.id !== id);
    StateStore.save();
    res.json({ success: true });
});

app.post("/api/simulation/execute", requireAuth, (req: Request, res: Response) => {
    const customAction: SimAction = { cmd: "tap", params: { x: 500, y: 500 } };
    addLog(StateStore.state.logs, `Executing sim action: ${customAction.cmd}`);
    res.json({ success: true, executed: customAction });
});


app.all("/api/*", (req: Request, res: Response) => {
  res.status(404).json({ error: "API route not found", path: req.path });
});

async function startServer() {
  try {
    await StateStore.load(); // Ensure state is loaded
    
    addLog(StateStore.state.logs, "[AI-TUNNEL] Server listening on /api/ai/* endpoints.");
    addLog(StateStore.state.logs, "[AI-TUNNEL] Token generated. Visible ONLY in server console.");
    addLog(StateStore.state.logs, "[AI-TUNNEL] Waiting for client connection...");
    await StateStore.save();

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

    const server = app.listen(PORT, "0.0.0.0", () => {
      console.log(`✅ Server running on http://localhost:${PORT}`);
      console.log(`🔑 AI Tunnel Token (RAHASIA): ${StateStore.state.apiToken}`);
      console.log(`🛡️ Admin Token for daemon control: ${ADMIN_TOKEN}`);
      console.log("⚠️  Jangan bagikan token ini! Simpan di environment variable.");
    });
    
    // Graceful shutdown
    // BUG-N10 FIX: Handle both SIGTERM and SIGINT (Ctrl+C).
    // Previously only SIGTERM was handled — Ctrl+C in dev mode killed the process
    // without saving state, causing data loss.
    const gracefulShutdown = (signal: string) => {
        console.log(`${signal} received, shutting down gracefully`);
        server.close(async () => {
           await StateStore.save();
           process.exit(0);
        });
        // Fallback: if server.close() hangs (e.g., keep-alive connections), force exit after 5s.
        setTimeout(() => {
            console.error("Graceful shutdown timed out, forcing exit.");
            process.exit(1);
        }, 5000);
    };
    process.on("SIGTERM", () => gracefulShutdown("SIGTERM"));
    process.on("SIGINT", () => gracefulShutdown("SIGINT"));
  } catch (err) {
    console.error("❌ FATAL: Server gagal start:", err);
    process.exit(1);
  }
}

process.on("unhandledRejection", (reason) => {
  console.error("Unhandled rejection:", reason);
  // Log but do not crash unless strictly required, we want to stay alive
});

if (!process.env.VITEST) {
  startServer().catch((err) => {
    console.error('[FATAL] Server failed to start:', err);
    process.exit(1);
  });
}

export default app;

