import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { AdminNavComponent } from './admin-nav.component';

@Component({
  selector: 'ifp-admin-home',
  imports: [RouterLink, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <h1>Admin console</h1>
          <p class="subtitle">Signed in as {{ name() }}.</p>
        </div>
      </header>
      <ifp-admin-nav />
      <div class="sections">
        <div class="card stack">
          <h2>Invoice queue</h2>
          <p class="muted">Confirm submitted invoices with the buyer, verify them, or reject them with a reason.</p>
          <div><a class="btn btn-primary" routerLink="/admin/invoices">Open the invoice queue</a></div>
        </div>
        <div class="card stack">
          <h2>Financier approvals</h2>
          <p class="muted">Approve new financier accounts so they can sign in and make offers.</p>
          <div><a class="btn btn-secondary" routerLink="/admin/financiers">Review pending financiers</a></div>
        </div>
        <div class="card stack">
          <h2>Audit log</h2>
          <p class="muted">Search the read-only record of everything that happened on the platform.</p>
          <div><a class="btn btn-secondary" routerLink="/admin/audit">View the audit log</a></div>
        </div>
      </div>
    </section>
  `,
  styles: `
    .sections {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
    }

    h2,
    p {
      margin: 0;
    }

    h2 {
      font-size: var(--text-lg);
    }
  `,
})
export class AdminHomeComponent {
  private readonly auth = inject(AuthService);
  protected readonly name = computed(() => this.auth.user()?.name ?? '');
}
