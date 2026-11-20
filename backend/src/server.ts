import { loadConfig, ConfigError, type AppConfig } from './config.js';
import { createAnalysisProvider } from './llm/index.js';
import { createJsonLogger, type Logger } from './logger.js';
import { openDatabase } from './store/db.js';
import { Store } from './store/store.js';
import { createApp } from './app.js';

/**
 * Production/dev bootstrap. Order matters: config first (fail fast), then storage + migrations,
 * then the boot-time deletion sweep (FR-47), then the HTTP surface.
 */
function bootstrapLogger(config: AppConfig): Logger {
  return createJsonLogger({ level: config.logLevel });
}

export function buildServer(config: AppConfig) {
  const logger = bootstrapLogger(config);
  for (const warning of config.warnings) {
    logger.warn('config_warning', { message: warning });
  }

  const db = openDatabase(config.databasePath);
  const store = new Store({ db, dataKey: config.dataKey, keyVersion: config.dataKeyVersion });

  const swept = store.sweepDeletions(new Date().toISOString());
  if (swept > 0) logger.info('deletion_sweep_on_boot', { completed: swept });

  const provider = createAnalysisProvider(config);

  const app = createApp({
    config,
    store,
    logger,
    provider,
    now: () => new Date(),
  });

  return { app, store, logger, provider, config };
}

function main(): void {
  let config: AppConfig;
  try {
    config = loadConfig();
  } catch (error) {
    if (error instanceof ConfigError) {
      process.stderr.write(`${error.message}\n`);
      process.exit(1);
    }
    throw error;
  }

  const { app, store, logger, provider } = buildServer(config);
  const server = app.listen(config.port, () => {
    const address = server.address();
    const port = typeof address === 'object' && address ? address.port : config.port;
    logger.info('server_started', {
      port,
      provider: provider.id,
      model: provider.model,
      env: config.nodeEnv,
    });
  });

  const shutdown = (signal: string): void => {
    logger.info('server_stopping', { signal });
    server.close(() => {
      store.close();
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 10_000).unref();
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main();
