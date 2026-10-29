import { Injectable } from '@angular/core';

/**
 * One Idempotency-Key per pending repayment (agreement and amount), kept until financing-service confirms it.
 *
 * financing-service hashes the agreement and amount with the key, so a retry of the same repayment either replays the
 * original record (200, `Idempotent-Replayed: true`) or, if the first attempt rolled back, records it once. A different
 * amount gets a new key, because reusing a key for another amount is refused (409 IDEMPOTENCY_KEY_REUSED). Keys live in
 * memory only: they survive closing the dialog and navigating within the app, but not a reload.
 */
@Injectable({ providedIn: 'root' })
export class RepaymentKeysService {
  private readonly pending = new Map<string, { amount: string; key: string }>();

  keyFor(agreementId: string, amount: string): string {
    const current = this.pending.get(agreementId);
    if (current && current.amount === amount) {
      return current.key;
    }
    // 36 characters of hex digits and '-': inside financing-service's `[A-Za-z0-9_-]{8,100}`.
    const key = crypto.randomUUID();
    this.pending.set(agreementId, { amount, key });
    return key;
  }

  /** Call once the repayment is confirmed, so the next repayment of the same amount is a new one. */
  forget(agreementId: string): void {
    this.pending.delete(agreementId);
  }
}
