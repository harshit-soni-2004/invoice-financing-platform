import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { APP_CONFIG } from '../../core/config/app-config';
import { Role } from '../../core/auth/id-token';
import { ROLE_LABELS } from '../../core/auth/role-labels';

interface Profile {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  status: string;
  createdAt: string;
}

/** The signed-in user's profile, read from auth-service through the gateway. */
@Component({
  selector: 'ifp-account',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page narrow">
      <header class="page-header">
        <div>
          <h1>Your account</h1>
          <p class="subtitle">Details held by the sign-in service.</p>
        </div>
      </header>

      @if (profile(); as p) {
        <dl class="card details">
          <dt>Name</dt>
          <dd>{{ p.displayName }}</dd>
          <dt>Email</dt>
          <dd>{{ p.email }}</dd>
          <dt>Role</dt>
          <dd>{{ roleLabels[p.role] }}</dd>
          <dt>Status</dt>
          <dd>{{ p.status === 'ACTIVE' ? 'Active' : p.status }}</dd>
          <dt>Member since</dt>
          <dd>{{ p.createdAt | date: 'longDate' }}</dd>
        </dl>
      } @else if (failed()) {
        <p class="alert alert-danger" role="alert">Your account details could not be loaded. Please try again later.</p>
      } @else {
        <p class="muted" role="status">Loading your account…</p>
      }
    </section>
  `,
  styles: `
    .narrow {
      max-width: 40rem;
    }

    .details {
      display: grid;
      grid-template-columns: minmax(7rem, max-content) 1fr;
      gap: var(--space-3) var(--space-5);
      margin: 0;

      dt {
        font-weight: 600;
        color: var(--color-text-muted);
      }

      dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
    }
  `,
})
export class AccountComponent {
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly profile = signal<Profile | null>(null);
  protected readonly failed = signal(false);

  constructor() {
    inject(HttpClient)
      .get<Profile>(`${inject(APP_CONFIG).apiBaseUrl}/api/users/me`)
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (profile) => this.profile.set(profile),
        error: () => this.failed.set(true),
      });
  }
}
