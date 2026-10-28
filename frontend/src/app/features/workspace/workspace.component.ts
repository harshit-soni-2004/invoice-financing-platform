import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Role } from '../../core/auth/id-token';

const AREAS: Readonly<Record<Role, { title: string; summary: string }>> = {
  SELLER: {
    title: 'Seller workspace',
    summary: 'Your invoices, the offers financiers make on them, and your repayments will appear here.',
  },
  FINANCIER: {
    title: 'Financier workspace',
    summary: 'Invoices open for financing, your offers and your agreements will appear here.',
  },
  ADMIN: {
    title: 'Admin console',
    summary: 'Invoice verification, financier approval and the audit trail will appear here.',
  },
};

/** Role home page; each role's workflows are added to it in later steps. */
@Component({
  selector: 'ifp-workspace',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <header class="page-header">
        <div>
          <h1>{{ content().title }}</h1>
          <p class="subtitle">Signed in as {{ name() }}.</p>
        </div>
      </header>
      <div class="card stack">
        <p class="muted">{{ content().summary }}</p>
        <div class="cluster">
          <a class="btn btn-secondary" routerLink="/account">View your account</a>
        </div>
      </div>
    </section>
  `,
})
export class WorkspaceComponent {
  private readonly auth = inject(AuthService);

  readonly area = input.required<Role>();

  protected readonly content = computed(() => AREAS[this.area()]);
  protected readonly name = computed(() => this.auth.user()?.name ?? '');
}
