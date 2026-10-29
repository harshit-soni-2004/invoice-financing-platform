import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AccessProblemComponent } from './access-problem.component';
import { FinancierBadgeComponent } from './financier-badge.component';
import { FinancierNavComponent } from './financier-nav.component';
import { failureMessage, formatAmount, formatDate, formatInstant } from './financier-format';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { AGREEMENT_STATUSES, AGREEMENT_STATUS_INFO, Agreement, AgreementStatus, Page } from './financier.models';

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; result: Page<Agreement> }
  | { kind: 'error'; failure: FinancingFailure };

function statusParam(value: string | undefined): AgreementStatus | null {
  return AGREEMENT_STATUSES.find((status) => status === value) ?? null;
}

function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page - 1 : 0;
}

/** Agreements are identified to people by the start of their ID; the API carries no invoice number for them. */
export function shortId(id: string): string {
  return id.slice(0, 8).toUpperCase();
}

/** The signed-in financier's agreements; financing-service limits the list to them. */
@Component({
  selector: 'ifp-agreement-list',
  imports: [RouterLink, AccessProblemComponent, FinancierBadgeComponent, FinancierNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agreement-list.component.html',
  styleUrl: './financier-list.scss',
})
export class AgreementListComponent {
  private readonly api = inject(FinancingApiService);
  private readonly router = inject(Router);

  readonly page = input<string>();
  readonly status = input<string>();

  protected readonly statuses = AGREEMENT_STATUSES;
  protected readonly statusInfo = AGREEMENT_STATUS_INFO;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;
  protected readonly shortId = shortId;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly statusFilter = computed(() => statusParam(this.status()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly loading = computed(() => this.state().kind === 'loading');

  protected readonly result = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.result : null;
  });

  protected readonly failure = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.failure : null;
  });

  protected readonly accessProblem = computed(() => {
    const kind = this.failure()?.kind;
    return kind === 'forbidden' || kind === 'unauthenticated' ? kind : null;
  });

  protected readonly errorMessage = computed(() => {
    const failure = this.failure();
    return failure ? failureMessage(failure) : null;
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
    void this.router.navigate([], { queryParams: { status: statusParam(value), page: null }, queryParamsHandling: 'merge' });
  }

  protected pageLink(index: number): Record<string, number | null> {
    return { page: index === 0 ? null : index + 1 };
  }

  private async load(page: number, status: AgreementStatus | null): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.agreements(page, status);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        void this.router.navigate([], { queryParams: this.pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
        return;
      }
      this.state.set({ kind: 'loaded', result });
    } catch (error) {
      if (request === this.request) {
        this.state.set({ kind: 'error', failure: error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' } });
      }
    }
  }
}
