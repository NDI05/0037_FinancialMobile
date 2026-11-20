import { randomUUID } from 'node:crypto';
import express, { type NextFunction, type Request, type Response, Router } from 'express';
import { ApiError, badRequest, databaseUnavailable, isDatabaseUnavailableError, notFound } from './errors.js';
import { ownerRef, type Logger } from './logger.js';
import { accountsRouter } from './routes/accounts.js';
import { analysesRouter } from './routes/analyses.js';
import { budgetPlansRouter } from './routes/budgetPlans.js';
import { consentsRouter } from './routes/consents.js';
import { deletionRouter } from './routes/deletion.js';
import { healthRouter } from './routes/health.js';
import { internalRouter } from './routes/internal.js';
import { meRouter } from './routes/me.js';
import { sessionsRouter } from './routes/sessions.js';
import { summariesRouter } from './routes/summaries.js';
import { requireSession } from './auth/session.js';
import type { RouteDeps } from './routes/deps.js';

/**
 * App factory (contract §1). Every dependency is injected, so tests build the whole HTTP surface
 * without a network, a real Google key or a real LLM.
 */
export type AppDeps = RouteDeps;

function correlationMiddleware(request: Request, response: Response, next: NextFunction): void {
  // Always server-generated: an inbound value could inject newlines into the log.
  const correlationId = randomUUID();
  request.correlationId = correlationId;
  request.retryCount = 0;
  response.setHeader('X-Correlation-Id', correlationId);
  next();
}

function requestLogger(logger: Logger) {
  return (request: Request, response: Response, next: NextFunction): void => {
    const startedAt = performance.now();
    response.on('finish', () => {
      logger.request({
        correlationId: request.correlationId,
        method: request.method,
        path: request.path,
        status: response.statusCode,
        ms: performance.now() - startedAt,
        ownerRef: ownerRef(request.auth?.ownerId),
        retry: request.retryCount ?? 0,
      });
    });
    next();
  };
}

function errorHandler(logger: Logger) {
  return (error: unknown, request: Request, response: Response, _next: NextFunction): void => {
    let apiError: ApiError;
    if (error instanceof ApiError) {
      apiError = error;
    } else if (error instanceof SyntaxError && 'body' in error) {
      apiError = badRequest('Request body is not valid JSON');
    } else {
      logger.error('unhandled_error', {
        correlationId: request.correlationId,
        name: error instanceof Error ? error.name : 'Unknown',
      });
      apiError = isDatabaseUnavailableError(error)
        ? databaseUnavailable()
        : new ApiError('internal_error', 'Unexpected error');
    }
    if (response.headersSent) return;
    response.status(apiError.status).json({
      code: apiError.code,
      message: apiError.message,
      retryable: apiError.retryable,
      correlationId: request.correlationId,
    });
  };
}

export function createApp(deps: AppDeps): express.Express {
  const app = express();
  app.disable('x-powered-by');
  app.use(correlationMiddleware);
  app.use(requestLogger(deps.logger));
  app.use(express.json({ limit: '256kb' }));

  const authenticated = requireSession(deps.store, deps.config.sessionPepper, deps.logger);

  app.use(healthRouter(Date.now()));
  app.use(sessionsRouter(deps, authenticated));

  // Auth is attached per route (not mounted on a scope): a scope-level middleware would run for
  // paths that belong to another router, turning a 404 or the internal sweep into a 401.
  app.use(accountsRouter(deps));
  app.use(meRouter(deps, authenticated));
  app.use(consentsRouter(deps, authenticated));
  app.use(summariesRouter(deps, authenticated));
  app.use(budgetPlansRouter(deps, authenticated));
  app.use(deletionRouter(deps, authenticated));
  app.use(analysesRouter(deps, authenticated));

  app.use(internalRouter(deps));

  app.use((_request, _response, next) => next(notFound('Route not found')));
  app.use(errorHandler(deps.logger));

  return app;
}
