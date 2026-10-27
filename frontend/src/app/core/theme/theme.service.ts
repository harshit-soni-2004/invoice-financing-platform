
import { Injectable, effect, inject, signal, DOCUMENT } from '@angular/core';

export type ThemePreference = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'ifp.theme';
const ORDER: readonly ThemePreference[] = ['system', 'light', 'dark'];

/** Only the display preference is stored in localStorage; nothing sensitive. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  readonly preference = signal<ThemePreference>(this.read());

  constructor() {
    effect(() => {
      const preference = this.preference();
      const root = this.document.documentElement;
      if (preference === 'system') {
        delete root.dataset['theme'];
      } else {
        root.dataset['theme'] = preference;
      }
      try {
        this.document.defaultView?.localStorage.setItem(STORAGE_KEY, preference);
      } catch {
        // Storage can be unavailable (private mode); the preference then lasts for this page only.
      }
    });
  }

  cycle(): void {
    const next = ORDER[(ORDER.indexOf(this.preference()) + 1) % ORDER.length];
    this.preference.set(next);
  }

  private read(): ThemePreference {
    try {
      const stored = this.document.defaultView?.localStorage.getItem(STORAGE_KEY);
      return ORDER.includes(stored as ThemePreference) ? (stored as ThemePreference) : 'system';
    } catch {
      return 'system';
    }
  }
}
