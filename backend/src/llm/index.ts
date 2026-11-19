import type { AppConfig } from '../config.js';
import { FakeProvider } from './fake.js';
import { OpenAiCompatibleProvider } from './openai.js';
import type { AnalysisProvider } from './provider.js';

export type { AnalysisProvider, ProviderCall, ProviderResult } from './provider.js';
export { ProviderError } from './provider.js';
export { FakeProvider, formatIdr } from './fake.js';
export { OpenAiCompatibleProvider } from './openai.js';

/** Fake by default: `npm start` needs zero external services (contract §5, DoD). */
export function createAnalysisProvider(config: AppConfig): AnalysisProvider {
  if (config.llm.provider === 'openai') {
    return new OpenAiCompatibleProvider({
      baseUrl: config.llm.baseUrl,
      apiKey: config.llm.apiKey,
      model: config.llm.model,
      timeoutMs: config.llm.timeoutMs,
    });
  }
  return new FakeProvider(config.llm.fakeScenario);
}
