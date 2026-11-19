import { createRemoteJWKSet, jwtVerify, errors as joseErrors, type JWTVerifyGetKey } from 'jose';
import { unauthorized } from '../errors.js';

/**
 * Google ID-token verification (contract §2.1). Every check is mandatory:
 * signature against Google JWKS, `iss` in the two accepted spellings, `aud` equals the Web client
 * ID, `exp` not passed, `iat` not in the future (60 s tolerance), and a non-empty `sub`.
 *
 * The JWKS is fetched with jose's own cache (1 h, keyed by `kid`), so tests can inject a local key
 * set and production can point at Google without any other code path changing.
 */
export interface VerifiedIdentity {
  readonly sub: string;
  readonly name: string | null;
}

export interface GoogleVerifier {
  verify(idToken: string): Promise<VerifiedIdentity>;
}

const GOOGLE_ISSUERS = ['accounts.google.com', 'https://accounts.google.com'] as const;
const CLOCK_TOLERANCE_SECONDS = 60;

export interface GoogleVerifierOptions {
  readonly clientId: string;
  readonly jwks: JWTVerifyGetKey;
  readonly now?: () => number;
}

export function createGoogleVerifier(options: GoogleVerifierOptions): GoogleVerifier {
  const now = options.now ?? (() => Date.now());

  return {
    async verify(idToken: string): Promise<VerifiedIdentity> {
      if (!idToken || idToken.length > 6_000) {
        throw unauthorized('Google ID token is missing or malformed');
      }
      let payload: Record<string, unknown>;
      try {
        const verified = await jwtVerify(idToken, options.jwks, {
          issuer: [...GOOGLE_ISSUERS],
          audience: options.clientId,
          clockTolerance: CLOCK_TOLERANCE_SECONDS,
        });
        payload = verified.payload as Record<string, unknown>;
      } catch (error) {
        throw unauthorized(describeJoseError(error));
      }

      const sub = payload.sub;
      if (typeof sub !== 'string' || sub.trim() === '') {
        throw unauthorized('Google ID token has no subject');
      }

      const issuedAt = payload.iat;
      if (typeof issuedAt === 'number' && issuedAt * 1000 > now() + CLOCK_TOLERANCE_SECONDS * 1000) {
        throw unauthorized('Google ID token was issued in the future');
      }

      const name = typeof payload.name === 'string' && payload.name.trim() !== '' ? payload.name : null;
      return { sub, name };
    },
  };
}

/** Technical reason only — never the token or its claims. */
function describeJoseError(error: unknown): string {
  if (error instanceof joseErrors.JWTExpired) return 'Google ID token has expired';
  if (error instanceof joseErrors.JWTClaimValidationFailed) return 'Google ID token claim check failed';
  if (error instanceof joseErrors.JWSSignatureVerificationFailed) return 'Google ID token signature is invalid';
  if (error instanceof joseErrors.JWKSNoMatchingKey) return 'Google ID token signing key is unknown';
  if (error instanceof joseErrors.JOSEError) return 'Google ID token could not be verified';
  return 'Google ID token could not be verified';
}

export function createRemoteGoogleJwks(url: string): JWTVerifyGetKey {
  return createRemoteJWKSet(new URL(url), {
    cacheMaxAge: 60 * 60 * 1000,
    timeoutDuration: 5_000,
    cooldownDuration: 30_000,
  });
}

/**
 * Development-only verifier for `AUTH_DEV_FAKE_TOKENS=true`: `dev:<sub>[:<name>]`.
 * Config refuses this flag in production, and the app factory refuses it outside development.
 */
export function createDevTokenVerifier(): GoogleVerifier {
  return {
    async verify(idToken: string): Promise<VerifiedIdentity> {
      const [prefix, sub, ...rest] = idToken.split(':');
      if (prefix !== 'dev' || !sub || sub.trim() === '') {
        throw unauthorized('Development token must look like dev:<subject>[:<name>]');
      }
      return { sub: sub.trim(), name: rest.join(':').trim() || null };
    },
  };
}

/** Chooses the verifier for this configuration; production can never pick the dev one. */
export function createVerifierForConfig(config: {
  clientId: string;
  jwksUrl: string;
  devFakeTokens: boolean;
  nodeEnv: 'development' | 'test' | 'production';
}): GoogleVerifier {
  if (config.nodeEnv !== 'production' && config.devFakeTokens) {
    return {
      async verify(idToken: string): Promise<VerifiedIdentity> {
        if (idToken.startsWith('dev:')) return createDevTokenVerifier().verify(idToken);
        return createGoogleVerifier({
          clientId: config.clientId,
          jwks: createRemoteGoogleJwks(config.jwksUrl),
        }).verify(idToken);
      },
    };
  }
  return createGoogleVerifier({
    clientId: config.clientId,
    jwks: createRemoteGoogleJwks(config.jwksUrl),
  });
}
