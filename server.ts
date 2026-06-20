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

const API_KEY = process.env.VITE_NEXION_API_KEY;

if (!API_KEY || API_KEY.length < 32) {
  console.error("FATAL: VITE_NEXION_API_KEY must be set and at least 32 chars");
  process.exit(1);
}

// Simple API Key Middleware
app.use("/api", (req: Request, res: Response, next: NextFunction) => {
  if (req.method === "OPTIONS") return next();
  const authHeader = req.headers.authorization;
  if (!authHeader || authHeader !== `Bearer ${API_KEY}`) {
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
