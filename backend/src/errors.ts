/**
 * The single error shape (contract §3). Every response body is exactly
 * `{ code, message, retryable, correlationId }`.
 */

export type ApiErrorCode =
  | 'invalid_request'
  | 'unauthorized'
  | 'consent_required'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'deletion_in_progress'
  | 'invalid_analysis_output'
  | 'rate_limited'
  | 'daily_ceiling_reached'
  | 'provider_unavailable'
  | 'internal_error';

const statusForCode: Record<ApiErrorCode, number> = {
  invalid_request: 400,
  unauthorized: 401,
  consent_required: 403,
  forbidden: 403,
  not_found: 404,
  conflict: 409,
  deletion_in_progress: 409,
  invalid_analysis_output: 422,
  rate_limited: 429,
  daily_ceiling_reached: 429,
  provider_unavailable: 502,
  internal_error: 500,
};

const retryableForCode: Record<ApiErrorCode, boolean> = {
  invalid_request: false,
  unauthorized: false,
  consent_required: false,
  forbidden: false,
  not_found: false,
  conflict: false,
  deletion_in_progress: false,
  invalid_analysis_output: false,
  rate_limited: true,
  daily_ceiling_reached: true,
  provider_unavailable: true,
  internal_error: true,
};

export class ApiError extends Error {
  readonly status: number;
  readonly retryable: boolean;

  constructor(
    readonly code: ApiErrorCode,
    message: string,
    options: { status?: number; retryable?: boolean; cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'ApiError';
    this.status = options.status ?? statusForCode[code];
    this.retryable = options.retryable ?? retryableForCode[code];
  }
}

export const badRequest = (message: string): ApiError => new ApiError('invalid_request', message);
export const unauthorized = (message = 'Missing or invalid access token'): ApiError =>
  new ApiError('unauthorized', message);
export const consentRequired = (): ApiError =>
  new ApiError('consent_required', 'AI consent has not been granted');
export const forbidden = (message = 'Not allowed'): ApiError => new ApiError('forbidden', message);
export const notFound = (message = 'Not found'): ApiError => new ApiError('not_found', message);
export const conflict = (message: string): ApiError => new ApiError('conflict', message);
export const deletionInProgress = (): ApiError =>
  new ApiError('deletion_in_progress', 'A deletion request is in progress for this account');
export const invalidAnalysisOutput = (): ApiError =>
  new ApiError('invalid_analysis_output', 'The analysis provider returned output that failed validation');
export const rateLimited = (message: string): ApiError => new ApiError('rate_limited', message);
export const dailyCeilingReached = (): ApiError =>
  new ApiError('daily_ceiling_reached', 'The daily AI budget ceiling has been reached');
export const providerUnavailable = (message = 'The analysis provider is unavailable'): ApiError =>
  new ApiError('provider_unavailable', message);
