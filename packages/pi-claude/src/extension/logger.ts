import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import type { Logger } from "./claude-hooks.js";

const LOG_DIR = "/tmp/pi-claude";

function ensureLogDir(): void {
	try {
		mkdirSync(LOG_DIR, { recursive: true });
	} catch {
		// ignore
	}
}

export function createLogger(getSessionId: () => string | null, prefix: string): Logger {
	ensureLogDir();

	function write(level: string, args: any[]): void {
		try {
			const sessionId = getSessionId();
			const fileName = sessionId ? `${sessionId}.log` : "pi-claude.log";
			const logPath = join(LOG_DIR, fileName);
			const msg = args.map((a) => (typeof a === "object" ? JSON.stringify(a) : String(a))).join(" ");
			appendFileSync(logPath, `[${new Date().toISOString()}] ${prefix} ${level} ${msg}\n`);
		} catch {
			// ignore logging failures
		}
	}

	return {
		log: (...args) => write("INFO", args),
		logWarn: (...args) => write("WARN", args),
		logError: (...args) => write("ERROR", args),
	};
}
