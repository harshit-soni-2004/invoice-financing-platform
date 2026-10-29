import { Injectable } from '@angular/core';

/**
 * One Idempotency-Key per agreement for the "disburse" action, kept until financing-service confirms the payout.
 *
 * Reusing the key is always safe for the same agreement: financing-service hashes the operation and agreement ID, so a
 * retry either replays the original disbursement (200, `Idempotent-Replayed: true`) or, if the first attempt failed and
 * rolled back, runs it again. That makes a retry after a timeout or a lost response unable to pay out twice. The key
 * lives in memory only, so it survives navigating within the app but not a reload.
 */
@Injectable({ providedIn: 'root' })
export class DisburseKeysService {
  private readonly keys = new Map<string, string>();

  keyFor(agreementId: string): string {
    let key = this.keys.get(agreementId);
    if (!key) {
      // 36 characters of hex digits and '-': inside financing-service's `[A-Za-z0-9_-]{8,100}`.
      key = crypto.randomUUID();
      this.keys.set(agreementId, key);
    }
    return key;
  }

  /** Call once the disbursement is confirmed, or when the agreement can no longer be disbursed. */
  forget(agreementId: string): void {
    this.keys.delete(agreementId);
  }
}
