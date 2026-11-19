import {
  analysisResponseSchema,
  type AnalysisResponse,
} from './analysis.js';
import {
  allowedAmounts,
  type AggregatePayload,
} from './payload.js';
import { isKnownMetricKey } from './metricKeys.js';

/**
 * Mirrors the app's `AnalysisValidator` (FR-36/FR-37) and contract §4.1 step 7.
 *
 * Rejection reasons are technical codes only; the HTTP layer turns them into
 * `422 invalid_analysis_output` and never echoes provider prose.
 */
export const ISSUE_CODES = {
  schemaInvalid: 'schema_invalid',
  promptVersionMismatch: 'prompt_version_mismatch',
  noObservations: 'no_observations',
  blankText: 'blank_text',
  missingEvidence: 'missing_evidence',
  unknownMetric: 'unknown_metric_key',
  inventedAmount: 'invented_amount',
  untrustedInstruction: 'untrusted_instruction',
  suggestionAmountInvalid: 'suggestion_amount_invalid',
  missingLimitations: 'missing_limitations',
} as const;

export type IssueCode = (typeof ISSUE_CODES)[keyof typeof ISSUE_CODES];

export interface ValidationSuccess {
  readonly ok: true;
  readonly analysis: AnalysisResponse;
  readonly issues: readonly [];
}

export interface ValidationFailure {
  readonly ok: false;
  readonly issues: readonly string[];
}

export type ValidationResult = ValidationSuccess | ValidationFailure;

const RUPIAH_PATTERN = /Rp\s?([0-9][0-9.,]*)/g;

/** Reads the same shape `IdrFormatter` produces: `Rp` + `.` grouping + optional `,` cents. */
export function extractRupiahAmounts(text: string): number[] {
  const amounts: number[] = [];
  for (const match of text.matchAll(RUPIAH_PATTERN)) {
    const parsed = parseMinorUnits(match[1] ?? '');
    if (parsed !== null) amounts.push(parsed);
  }
  return amounts;
}

function parseMinorUnits(raw: string): number | null {
  const compact = raw.trim();
  if (!compact) return null;
  const commaIndex = compact.lastIndexOf(',');
  const majorPart = commaIndex >= 0 ? compact.slice(0, commaIndex) : compact;
  const centsPart = commaIndex >= 0 ? compact.slice(commaIndex + 1) : '00';
  const majorDigits = majorPart.replace(/\D/g, '');
  if (!majorDigits) return null;
  const centsDigits = centsPart.replace(/\D/g, '').padEnd(2, '0').slice(0, 2);
  const major = Number(majorDigits);
  const cents = Number(centsDigits);
  if (!Number.isFinite(major) || !Number.isFinite(cents)) return null;
  return major * 100 + cents;
}

/**
 * Multilingual instruction markers (NFR-13). Provider prose is untrusted data: anything that reads
 * like a directive is rejected rather than rendered.
 */
const INSTRUCTION_MARKERS = [
  'abaikan',
  'instruksi sebelumnya',
  'ignore previous',
  'ignore all previous',
  'ignore the above',
  'disregard previous',
  'system:',
  'assistant:',
  'you are now',
  'kamu sekarang',
  'lupakan aturan',
  'override the',
  'tool_calls',
  'jalankan alat',
  'panggil fungsi',
];

function containsInstruction(text: string): boolean {
  const lower = text.toLowerCase();
  return INSTRUCTION_MARKERS.some((marker) => lower.includes(marker));
}

function checkMetricKeys(keys: readonly string[], issues: string[]): void {
  if (keys.length === 0) {
    issues.push(ISSUE_CODES.missingEvidence);
    return;
  }
  if (keys.some((key) => !isKnownMetricKey(key))) {
    issues.push(ISSUE_CODES.unknownMetric);
  }
}

export interface ValidateOptions {
  /** The prompt version the server actually sent; a provider cannot answer for another version. */
  readonly expectedPromptVersion: string;
}

export function validateAnalysis(
  raw: unknown,
  payload: AggregatePayload,
  options: ValidateOptions,
): ValidationResult {
  const parsed = analysisResponseSchema.safeParse(raw);
  if (!parsed.success) {
    return { ok: false, issues: [ISSUE_CODES.schemaInvalid] };
  }
  const analysis = parsed.data;
  const issues: string[] = [];

  if (analysis.promptVersion !== options.expectedPromptVersion) {
    issues.push(ISSUE_CODES.promptVersionMismatch);
  }
  if (analysis.observations.length === 0) {
    issues.push(ISSUE_CODES.noObservations);
  }
  if (analysis.limitations.length === 0) {
    issues.push(ISSUE_CODES.missingLimitations);
  }

  const allowed = allowedAmounts(
    payload,
    analysis.suggestions.map((suggestion) => suggestion.suggestedAmountMinor ?? null),
  );

  const inspectText = (text: string): void => {
    if (!text.trim()) {
      issues.push(ISSUE_CODES.blankText);
      return;
    }
    if (containsInstruction(text)) issues.push(ISSUE_CODES.untrustedInstruction);
    if (extractRupiahAmounts(text).some((amount) => !allowed.has(amount))) {
      issues.push(ISSUE_CODES.inventedAmount);
    }
  };

  for (const observation of analysis.observations) {
    checkMetricKeys(observation.metricKeys, issues);
    inspectText(observation.text);
  }

  for (const suggestion of analysis.suggestions) {
    checkMetricKeys(suggestion.metricKeys, issues);
    inspectText(suggestion.text);
    const amount = suggestion.suggestedAmountMinor;
    if (amount !== null && amount !== undefined) {
      const exceedsInflow = payload.grossInflowMinor > 0 && amount > payload.grossInflowMinor;
      if (amount <= 0 || exceedsInflow) issues.push(ISSUE_CODES.suggestionAmountInvalid);
    }
    if (suggestion.categoryCode && !payload.categoryTotals.some((total) => total.categoryCode === suggestion.categoryCode)) {
      // A suggestion may only target a category the payload actually contains.
      issues.push(ISSUE_CODES.unknownMetric);
    }
  }

  const distinct = [...new Set(issues)];
  if (distinct.length > 0) return { ok: false, issues: distinct };
  return { ok: true, analysis, issues: [] };
}
