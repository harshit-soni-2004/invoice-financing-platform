import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { InvoiceApiError, InvoiceApiService } from './invoice-api.service';
import { failureMessage, formatAmount, formatDate, formatInstant } from './invoice-format';
import { INVOICE_STATUSES, InvoicePage, InvoiceStatus, STATUS_INFO } from './invoice.models';
import { StatusBadgeComponent } from './status-badge.component';

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; result: InvoicePage }
  | { kind: 'error'; message: string };

function statusParam(value: string | undefined): InvoiceStatus | null {
  return INVOICE_STATUSES.find((status) => status === value) ?? null;
}

/** `?page=` is one-based for people; the API is zero-based. */
function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page - 1 : 0;
}

/** The signed-in seller's invoices. invoice-service limits the list to them, whatever the browser asks for. */
@Component({
  selector: 'ifp-invoice-list',
  imports: [RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-list.component.html',
  styleUrl: './invoice-list.component.scss',
})
export class InvoiceListComponent {
  private readonly api = inject(InvoiceApiService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  /** Query parameters, bound by the router. */
  readonly page = input<string>();
  readonly status = input<string>();

  protected readonly statuses = INVOICE_STATUSES;
  protected readonly statusInfo = STATUS_INFO;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly statusFilter = computed(() => statusParam(this.status()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly name = computed(() => this.auth.user()?.name ?? '');
  protected readonly loading = computed(() => this.state().kind === 'loading');

  protected readonly result = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.result : null;
  });

  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });

  protected readonly range = computed(() => {
    const result = this.result();
    if (!result || result.content.length === 0) {
      return null;
    }
    const { page, size, totalElements, content } = result;
    return { from: page * size + 1, to: page * size + content.length, total: totalElements };
  });

  private request = 0;

  constructor() {
    effect(() => {
      const page = this.pageIndex();
      const status = this.statusFilter();
      untracked(() => void this.load(page, status));
    });
  }

  protected refresh(): void {
    void this.load(this.pageIndex(), this.statusFilter());
  }

  protected filterBy(value: string): void {
    void this.router.navigate([], {
      queryParams: { status: statusParam(value), page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected pageLink(index: number): Record<string, number | null> {
    return { page: index === 0 ? null : index + 1 };
  }

  private async load(page: number, status: InvoiceStatus | null): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.list(page, status);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        // The page no longer exists (for example, a stale link): show the last one instead.
        void this.router.navigate([], { queryParams: this.pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
        return;
      }
      this.state.set({ kind: 'loaded', result });
    } catch (error) {
      if (request === this.request) {
        this.state.set({
          kind: 'error',
          message: failureMessage(error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' }),
        });
      }
    }
  }
}
