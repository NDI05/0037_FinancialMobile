/**
 * Inspect the PAM database the backend is configured against — local file or Turso.
 *
 *   npm run db:inspect            # ringkasan semua tabel
 *   npm run db:inspect -- --full  # termasuk payload terdekripsi
 *
 * Read-only: hanya SELECT. Kredensial tidak pernah dicetak.
 */
import { createClient } from '@libsql/client';
import { readFileSync } from 'node:fs';
import { aadFor, openJson } from '../src/crypto.js';

type Row = Record<string, unknown>;

function loadEnv(): Record<string, string> {
  try {
    return Object.fromEntries(
      readFileSync(new URL('../.env', import.meta.url), 'utf8')
        .split('\n')
        .filter((line) => /^[A-Z_]+=/.test(line))
        .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
    );
  } catch {
    return {};
  }
}

function decodeKey(raw: string | undefined): Buffer | null {
  if (!raw) return null;
  const key = Buffer.from(raw, 'base64');
  return key.length === 32 ? key : null;
}

const env = { ...loadEnv(), ...process.env } as Record<string, string>;
const full = process.argv.includes('--full');
const keyVersion = Number(env.DATA_KEY_VERSION ?? 1);
const dataKey = decodeKey(env.DATA_ENCRYPTION_KEY);

/** Pakai kunci pengembangan hanya bila env tidak menyediakannya (mode development). */
const effectiveKey = dataKey ?? (env.NODE_ENV === 'production' ? null : Buffer.alloc(32, 7));

const client = createClient({
  url: env.DATABASE_URL ?? 'file:./data/pam.db',
  authToken: env.DATABASE_AUTH_TOKEN || undefined,
});

const mask = (value: unknown) => (typeof value === 'string' && value.length > 12 ? `${value.slice(0, 6)}…` : '•••');

function openSealed(purpose: string, ownerId: string, row: Row): unknown {
  const ciphertext = row.ciphertext;
  if (!(ciphertext instanceof ArrayBuffer) && !(ciphertext instanceof Uint8Array)) return '‹tanpa ciphertext›';
  if (!effectiveKey) return '‹DATA_ENCRYPTION_KEY tidak ada›';
  try {
    const version = Number(row.key_version ?? keyVersion);
    return openJson(
      {
        ciphertext: Buffer.from(ciphertext as ArrayBuffer),
        iv: Buffer.from(row.iv as ArrayBuffer),
        tag: Buffer.from(row.tag as ArrayBuffer),
        keyVersion: version,
      },
      effectiveKey,
      aadFor(ownerId, purpose, version),
    );
  } catch {
    return '‹gagal dekripsi: kunci berbeda?›';
  }
}

const tables = await client.execute(
  "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '__turso%' ORDER BY name",
);

console.log(`\nDATABASE : ${(env.DATABASE_URL ?? 'file:./data/pam.db').replace(/\/\/.*@/, '//•••@')}`);
console.log(`MODE     : ${full ? 'lengkap (payload didekripsi)' : 'ringkasan'}\n`);

for (const table of tables.rows) {
  const name = String(table.name);
  const count = await client.execute(`SELECT COUNT(*) AS n FROM ${name}`);
  console.log(`${name.padEnd(20)} ${String(count.rows[0].n).padStart(4)} baris`);
}

const users = await client.execute('SELECT * FROM users ORDER BY created_at');
if (users.rows.length) {
  console.log('\n=== users ===');
  for (const row of users.rows) {
    console.log(`- ${row.email}  (${row.display_name ?? '—'})  kdf=${row.kdf}  hash=${mask(row.password_hash)}`);
  }
}

const consents = await client.execute('SELECT * FROM consents ORDER BY created_at');
if (consents.rows.length) {
  console.log('\n=== consents ===');
  for (const row of consents.rows) {
    console.log(`- ${row.purpose} = ${row.decision}  notice=${row.notice_version}  ${row.created_at}`);
  }
}

const summaries = await client.execute('SELECT * FROM summaries ORDER BY month_id');
if (summaries.rows.length) {
  console.log('\n=== summaries ===');
  for (const row of summaries.rows) {
    const payload = openSealed('summary', String(row.owner_id), row as Row) as Record<string, unknown> | string;
    if (typeof payload === 'string') {
      console.log(`- ${row.month_id} rev=${row.input_revision} ${payload}`);
    } else {
      console.log(
        `- ${row.month_id} rev=${row.input_revision} coverage=${row.coverage} ` +
          `masuk=${payload.grossInflowMinor} keluar=${payload.grossOutflowMinor} bersih=${payload.netCashFlowMinor}`,
      );
      if (full) console.log('  ', JSON.stringify(payload));
    }
  }
}

const analyses = await client.execute('SELECT * FROM analyses ORDER BY created_at');
if (analyses.rows.length) {
  console.log('\n=== analyses ===');
  for (const row of analyses.rows) {
    const payload = openSealed('analysis', String(row.owner_id), row as Row) as Record<string, unknown> | string;
    if (typeof payload === 'string') {
      console.log(`- ${row.month_id} rev=${row.input_revision} ${payload}`);
      continue;
    }
    const observations = (payload.observations ?? []) as Array<{ text?: string }>;
    console.log(
      `- ${row.month_id} rev=${row.input_revision} model=${row.model ?? '—'} ` +
        `prompt=${row.prompt_version ?? '—'} obs=${observations.length} regenerasi=${row.regeneration_count ?? 0}`,
    );
    if (full) for (const observation of observations) console.log(`   • ${observation.text}`);
  }
}

const plans = await client.execute('SELECT * FROM budget_plans ORDER BY month_id');
if (plans.rows.length) {
  console.log('\n=== budget_plans ===');
  for (const row of plans.rows) {
    const payload = openSealed('budget_plan', String(row.owner_id), row as Row) as Record<string, unknown> | string;
    console.log(`- ${row.month_id} ${typeof payload === 'string' ? payload : JSON.stringify(payload)}`);
  }
}

const deletions = await client.execute('SELECT * FROM deletion_requests ORDER BY requested_at');
if (deletions.rows.length) {
  console.log('\n=== deletion_requests ===');
  for (const row of deletions.rows) console.log(`- ${row.id} state=${row.state} at=${row.requested_at}`);
}

console.log('');
