import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AccessProblemComponent } from './access-problem.component';
import { FinancierNavComponent } from './financier-nav.component';
import { failureMessage, formatAmount, formatDate } from './financier-format';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { Opportunity, OpportunitySort, Page } from './financier.models';

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; result: Page<Opportunity> }
  | { kind: 'error'; failure: FinancingFailure };

interface SortOption {
  value: string;
  label: string;
  sort: OpportunitySort;
}

/** Every sort financing-service accepts for opportunities (`dueDate`, `amount`, `invoiceNumber`, either direction). */
export const SORT_OPTIONS: readonly SortOption[] = [
  { value: 'dueDate,asc', label: 'Due date, soonest first', sort: { field: 'dueDate', direction: 'asc' } },
  { value: 'dueDate,desc', label: 'Due date, latest first', sort: { field: 'dueDate', direction: 'desc' } },
  { value: 'amount,desc', label: 'Amount, largest first', sort: { field: 'amount', direction: 'desc' } },
  { value: 'amount,asc', label: 'Amount, smallest first', sort: { field: 'amount', direction: 'asc' } },
  { value: 'invoiceNumber,asc', label: 'Invoice number, A to Z', sort: { field: 'invoiceNumber', direction: 'asc' } },
  { value: 'invoiceNumber,desc', label: 'Invoice number, Z to A', sort: { field: 'invoiceNumber', direction: 'desc' } },
];

const DEFAULT_SORT = SORT_OPTIONS[0];

function sortParam(value: string | undefined): SortOption {
  return SORT_OPTIONS.find((option) => option.value === value) ?? DEFAULT_SORT;
}

/** `?page=` is one-based for people; the API is zero-based. */
function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page - 1 : 0;
}

/** Invoices open for offers: listed by their seller, far enough from their due date, and not yet financed. */
@Component({
  selector: 'ifp-opportunity-list',
  imports: [RouterLink, AccessProblemComponent, FinancierNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './opportunity-list.component.html',
  styleUrl: './financier-list.scss',
})
export class OpportunityListComponent {
  private readonly api = inject(FinancingApiService);
  private readonly router = inject(Router);

  /** Query parameters, bound by the router. */
  readonly page = input<string>();
  readonly sort = input<string>();

  protected readonly sortOptions = SORT_OPTIONS;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly sortOption = computed(() => sortParam(this.sort()));
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
      const sort = this.sortOption();
      untracked(() => void this.load(page, sort));
    });
  }

  protected refresh(): void {
    void this.load(this.pageIndex(), this.sortOption());
  }

  protected sortBy(value: string): void {
    const option = sortParam(value);
    void this.router.navigate([], {
      queryParams: { sort: option === DEFAULT_SORT ? null : option.value, page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected pageLink(index: number): Record<string, number | null> {
    return { page: index === 0 ? null : index + 1 };
  }

  private async load(page: number, sort: SortOption): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.opportunities(page, sort.sort);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        // The page no longer exists (for example, invoices were financed meanwhile): show the last one instead.
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
