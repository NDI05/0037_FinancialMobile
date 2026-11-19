import { Router } from 'express';

export function healthRouter(startedAt: number): Router {
  const router = Router();
  router.get('/healthz', (_request, response) => {
    response.status(200).json({
      status: 'ok',
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
    });
  });
  return router;
}
