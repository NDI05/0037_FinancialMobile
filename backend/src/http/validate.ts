import type { ZodType } from 'zod';
import { badRequest } from '../errors.js';

/**
 * Parses exactly one body with zod. Issues are reported by path and message only — never the
 * offending value, because a rejected body may contain something we must not echo (NFR-18).
 */
export function parseBody<T>(schema: ZodType<T>, body: unknown): T {
  const result = schema.safeParse(body);
  if (result.success) return result.data;
  const detail = result.error.issues
    .slice(0, 5)
    .map((issue) => `${issue.path.join('.') || '(body)'}: ${issue.message}`)
    .join('; ');
  throw badRequest(`Invalid request body — ${detail}`);
}
