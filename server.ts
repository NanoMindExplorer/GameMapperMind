import express, { Request, Response, NextFunction } from "express";
import path from "path";
import cors from "cors";
import fs from "fs/promises";
import crypto from "crypto";
import { z } from "zod";

const app = express();
const PORT: number = parseInt(process.env.PORT ?? "3000", 10);

const ALLOWED_ORIGINS = ["http://localhost:3000", "capacitor://localhost", "http://localhost"];

app.use(cors({
  origin: (origin, callback) => {
    if (!origin || ALLOWED_ORIGINS.includes(origin) || origin.endsWith('.run.app')) {
      callback(null, true);
    } else {
      callback(new Error("Not allowed by CORS"));
    }
  },
  credentials: true
}));

/**
 * API Key untuk autentikasi endpoint /api/*.
 *
 * Fix untuk BUG-N04 (regression dari fix BUG-H01):
 * - Sebelumnya API_KEY punya fallback default 'dev-secret-key-123' yang hardcoded.
 * - Karena kode di-commit ke public repository GitHub, secret fallback ter-expose.
 * - Attacker yang membaca repo tahu API key default dan dapat akses endpoint.
 *
 * Fix:
 * - Hapus fallback default sepenuhnya.
 * - Jika env var VITE_NEXION_API_KEY tidak di-set atau kurang dari 32 karakter,
 *   server REFUSE TO START dengan error message yang jelas.
 * - Tidak ada default secret di source code.
 *
 * Invariant:
 * - Jika server berjalan, API_KEY pasti ter-set dan >= 32 karakter.
 * - Tidak ada cara untuk server berjalan tanpa API key yang valid.
 *
 * Cara generate API key yang kuat:
 *   openssl rand -hex 32
 *
 * Cara set env var:
 *   export VITE_NEXION_API_KEY=$(openssl rand -hex 32)
 *   atau via .env file (jangan commit .env ke repo)
 */
const API_KEY: string | undefined = process.env.VITE_NEXION_API_KEY;

if (!API_KEY || API_KEY.length < 32) {
  console.error(
    "FATAL: VITE_NEXION_API_KEY environment variable must be set and at least 32 characters long.\n" +
    "Generate a strong key with: openssl rand -hex 32\n" +
    "Set it with: export VITE_NEXION_API_KEY=<your-key>\n" +
    "Do NOT commit the key to source control."
  );
  process.exit(1);
}

// Type assertion: setelah check di atas, API_KEY pasti string (TypeScript narrowing).
const API_KEY_VALID: string = API_KEY;

// Simple API Key Middleware
app.use("/api", (req: Request, res: Response, next: NextFunction) => {
  if (req.method === "OPTIONS") return next();
  const authHeader = req.headers.authorization;
  if (!authHeader || authHeader !== `Bearer ${API_KEY_VALID}`) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
});

app.use(express.json({ limit: "1mb" }));

// Bug #21: trimLog
function trimLog<T>(arr: T[], maxSize: number = 50): T[] {
  return arr.length > maxSize ? arr.slice(arr.length - maxSize) : arr;
}

// Bug #14: State persistensi
const STATE_FILE = path.join(process.cwd(), "state.json");
interface AppState {
  logs: string[];
}
let appState: AppState = { logs: [] };

async function loadState() {
  try {
    const data = await fs.readFile(STATE_FILE, "utf-8");
    appState = JSON.parse(data);
  } catch (err: unknown) {
    console.log("No existing state file found or invalid JSON. Initializing new state.");
  }
}

async function saveState() {
  try {
    await fs.writeFile(STATE_FILE, JSON.stringify(appState, null, 2), "utf-8");
  } catch (err: unknown) {
    console.error("Failed to save state.");
  }
}

app.get("/api/health", (req: Request, res: Response) => {
  res.json({ status: "ok" });
});

// Bug #16: typo instruksi
const LogSchema = z.object({
  message: z.string(),
  instruksi: z.string().optional()
});

app.post("/api/log", async (req: Request, res: Response) => {
  try {
    const parsed = LogSchema.parse(req.body);
    const clientIp = req.ip || ""; // Bug #10
    
    appState.logs.push(`[${clientIp}] ${parsed.message} ${parsed.instruksi ? '- ' + parsed.instruksi : ''}`);
    appState.logs = trimLog(appState.logs);
    
    await saveState();
    res.json({ success: true, count: appState.logs.length });
  } catch (error: unknown) {
    res.status(400).json({ error: "Invalid request payload" });
  }
});

app.get("/api/logs", (req: Request, res: Response) => {
    res.json({ logs: appState.logs });
});

// Vite middleware for development
async function startServer() {
  await loadState();

  if (process.env.NODE_ENV !== "production") {
    // Bug #3: Dynamic import
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

  // Bug #17: Global error handler
  app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
    console.error("Global Error:", err.message);
    res.status(500).json({ error: "Internal Server Error" });
  });

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on port ${PORT}`);
  });
}

// Bug #20: Catch start pattern
startServer().catch(err => {
  console.error("Failed to start server", err);
  process.exit(1);
});
