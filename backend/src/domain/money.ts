/**
 * Indonesian rupiah rendering shared by the prompt reference table, the fake provider and the
 * validator's tests: `Rp` + dot grouping, cents only when present (the app's `IdrFormatter` shape).
 *
 * The analysis pipeline only ever *displays* minor units through this function; a model must never
 * be handed the raw minor number without a scale hint (that is what produced the 100× error).
 */
export function formatIdr(minor: number): string {
  const negative = minor < 0;
  const absolute = Math.abs(Math.trunc(minor));
  const major = Math.trunc(absolute / 100);
  const cents = absolute % 100;
  const grouped = major.toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const body = cents === 0 ? grouped : `${grouped},${cents.toString().padStart(2, '0')}`;
  return `Rp${negative ? '-' : ''}${body}`;
}

/** `297935000` → `2.979.350` (no `Rp`): used by tests to assert an ambiguous form is absent. */
export function formatMinorDigits(minor: number): string {
  return Math.abs(Math.trunc(minor))
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}
