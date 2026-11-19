import type { Request } from 'express';

/** Resolved by the bearer middleware; `ownerId` is the only identity routes may trust. */
export interface AuthContext {
  readonly ownerId: string;
  readonly sessionId: string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      correlationId: string;
      auth?: AuthContext;
      /** Number of regenerations this request performed (observability §7). */
      retryCount: number;
    }
  }
}

export type AuthedRequest = Request & { auth: AuthContext };
