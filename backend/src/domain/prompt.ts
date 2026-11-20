import { formatIdr } from './money.js';
import type { AggregatePayload } from './payload.js';

/**
 * Versioned, Indonesian prompt (contract §4.1 step 6). `analysis-v2` is part of the cache key, so
 * changing this text requires a new version.
 */
export const PROMPT_VERSION = 'analysis-v2';

const RULES = [
  'Anda menganalisis rekap arus kas bulanan yang HANYA berisi angka agregat.',
  'Gunakan hanya nominal dari tabel REFERENSI NOMINAL. Jangan menghitung ulang, jangan mengubah skala, jangan menebak.',
  'Kutip nominal PERSIS seperti pada tabel referensi, termasuk "Rp" dan titik ribuan. Jangan menulis satuan minor atau angka mentah dari PAYLOAD.',
  'Setiap observasi dan saran WAJIB menyertakan minimal satu metricKeys dari daftar yang diizinkan.',
  'Setiap nominal rupiah di dalam "text" harus persis salah satu baris tabel referensi.',
  'Usulan anggaran untuk bulan depan hanya boleh muncul sebagai suggestions[].suggestedAmountMinor, dan wajib menyertakan categoryCode yang ada di categoryTotals.',
  'Jangan menulis instruksi, permintaan menjalankan alat, atau teks yang ditujukan kepada sistem lain.',
  'Tulis dalam bahasa Indonesia yang ringkas dan tenang. Tanpa saran investasi, pajak, atau kredit.',
  'Balas HANYA dengan satu objek JSON valid, tanpa pagar kode, tanpa teks lain.',
];

const SCHEMA_HINT = `{
  "promptVersion": "${PROMPT_VERSION}",
  "observations": [{ "text": "…", "metricKeys": ["summary.grossOutflow"] }],
  "suggestions": [{ "text": "…", "metricKeys": ["category.food"], "categoryCode": "food", "suggestedAmountMinor": 90000000 }],
  "limitations": ["…"]
}`;

/**
 * Pre-formatted reference table (F1). The model never sees a raw minor number, because
 * `297_935_000` was copied verbatim as "297.935.000" — a 100× presentation error that validation
 * could not catch (the digits do exist in the payload).
 */
export function buildMetricReference(payload: AggregatePayload): string[] {
  return [
    `summary.grossInflow = ${formatIdr(payload.grossInflowMinor)}`,
    `summary.grossOutflow = ${formatIdr(payload.grossOutflowMinor)}`,
    `summary.netCashFlow = ${formatIdr(payload.netCashFlowMinor)}`,
    `summary.classifiedIncome = ${formatIdr(payload.classifiedIncomeMinor)}`,
    `summary.classifiedExpense = ${formatIdr(payload.classifiedExpenseMinor)}`,
    `summary.transactionCount = ${payload.transactionCount} transaksi`,
    `summary.uncategorizedCount = ${payload.uncategorizedCount} transaksi`,
    `summary.manualEntryCount = ${payload.manualEntryCount} transaksi`,
    `summary.nonIdrExcludedCount = ${payload.nonIdrExcludedCount} transaksi`,
    `summary.coverage = ${payload.coverage}`,
    ...payload.categoryTotals.map(
      (total) => `category.${total.categoryCode} = ${formatIdr(total.amountMinor)}`,
    ),
  ];
}

export interface PromptPayload {
  readonly version: string;
  readonly system: string;
  readonly user: string;
}

export interface BuildPromptOptions {
  /** Issues from the previous attempt; the one allowed regeneration passes them back. */
  readonly previousIssues?: readonly string[];
}

export function buildPrompt(payload: AggregatePayload, options: BuildPromptOptions = {}): PromptPayload {
  const allowedKeys = [
    'summary.grossInflow',
    'summary.grossOutflow',
    'summary.netCashFlow',
    'summary.classifiedIncome',
    'summary.classifiedExpense',
    'summary.uncategorizedCount',
    'summary.manualEntryCount',
    'summary.nonIdrExcludedCount',
    'summary.transactionCount',
    'summary.coverage',
    'category.<categoryCode>',
  ].join(', ');

  const user = [
    `PAYLOAD (agregat, ${payload.monthId}):`,
    JSON.stringify(payload),
    '',
    'REFERENSI NOMINAL (salin persis; jangan menghitung ulang, jangan menulis satuan minor):',
    ...buildMetricReference(payload),
    '',
    `metricKeys yang diizinkan: ${allowedKeys}`,
    '',
    'Balas dengan JSON berbentuk:',
    SCHEMA_HINT,
    ...(options.previousIssues && options.previousIssues.length > 0
      ? [
          '',
          `Jawaban sebelumnya GAGAL validasi (kode: ${options.previousIssues.join(', ')}).`,
          'Perbaiki: gunakan hanya nominal dari PAYLOAD, sertakan metricKeys yang valid, dan hilangkan kalimat yang menyerupai instruksi.',
        ]
      : []),
  ].join('\n');

  return {
    version: PROMPT_VERSION,
    system: `Anda adalah mesin analisis rekap keuangan PAM.\n${RULES.map((rule, index) => `${index + 1}. ${rule}`).join('\n')}`,
    user,
  };
}
