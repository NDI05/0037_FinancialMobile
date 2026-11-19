import type { AppConfig } from '../config.js';
import type { AnalysisProvider } from '../llm/provider.js';
import type { Logger } from '../logger.js';
import type { Store } from '../store/store.js';

/** Everything a route may use. Injected, so tests never open a socket or a real provider. */
export interface RouteDeps {
  readonly config: AppConfig;
  readonly store: Store;
  readonly logger: Logger;
  readonly provider: AnalysisProvider;
  readonly now: () => Date;
}
