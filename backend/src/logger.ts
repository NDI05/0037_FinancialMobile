import { createHash } from 'node:crypto';

/**
 * One JSON line per request, exactly the field set in contract §7.
 *
 * The entry is built from an explicit allowlist, so a caller cannot accidentally log a body, an
 * amount, a token or an email by spreading a request object. `ownerRef` is a short hash: the raw
 * `sub` (or any other identifier) never reaches the log.
 */

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface RequestLogEntry {
  readonly correlationId: string;
  readonly method: string;
  readonly path: string;
  readonly status: number;
  readonly ms: number;
  /** Short, non-reversible reference (`u_ab12cd34ef`). */
  readonly ownerRef: string | null;
  readonly retry: number;
}

export interface Logger {
  request(entry: RequestLogEntry): void;
  info(message: string, fields?: Record<string, string | number | boolean>): void;
  warn(message: string, fields?: Record<string, string | number | boolean>): void;
  error(message: string, fields?: Record<string, string | number | boolean>): void;
}

/** `u_` + first 10 hex chars of sha256(ownerId): stable within a deployment, not reversible. */
export function ownerRef(ownerId: string | null | undefined): string | null {
  if (!ownerId) return null;
  return `u_${createHash('sha256').update(ownerId).digest('hex').slice(0, 10)}`;
}

const levelOrder: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40 };

export interface JsonLoggerOptions {
  level?: LogLevel;
  /** Tests pass a collector; production writes to stdout. */
  sink?: (line: string) => void;
  now?: () => Date;
}

export function createJsonLogger(options: JsonLoggerOptions = {}): Logger {
  const level = options.level ?? 'info';
  const sink = options.sink ?? ((line: string) => process.stdout.write(`${line}\n`));
  const now = options.now ?? (() => new Date());

  const emit = (
    recordLevel: LogLevel,
    payload: Record<string, unknown>,
  ): void => {
    if (levelOrder[recordLevel] < levelOrder[level]) return;
    sink(JSON.stringify({ ts: now().toISOString(), level: recordLevel, ...payload }));
  };

  return {
    request(entry) {
      // Explicit allowlist: nothing else can leak through this shape.
      emit(entry.status >= 500 ? 'error' : 'info', {
        correlationId: entry.correlationId,
        method: entry.method,
        path: entry.path,
        status: entry.status,
        ms: Math.round(entry.ms),
        ownerRef: entry.ownerRef,
        retry: entry.retry,
      });
    },
    info(message, fields) {
      emit('info', { message, ...fields });
    },
    warn(message, fields) {
      emit('warn', { message, ...fields });
    },
    error(message, fields) {
      emit('error', { message, ...fields });
    },
  };
}
