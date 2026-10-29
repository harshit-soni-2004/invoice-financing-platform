import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { InvoiceApiError, InvoiceApiService } from '../seller/invoice-api.service';
import { failureMessage, formatAmount, formatDate, formatInstant } from '../seller/invoice-format';
import { InvoicePage, InvoiceStatus } from '../seller/invoice.models';
import { StatusBadgeComponent } from '../seller/status-badge.component';
import { AdminNavComponent } from './admin-nav.component';
import { pageLink, pageParam } from './admin-shared';

export type QueueStage = 'confirmation' | 'verification';

interface StageInfo {
  stage: QueueStage;
  status: InvoiceStatus;
  label: string;
  empty: string;
}

export const STAGES: readonly StageInfo[] = [
  {
    stage: 'confirmation',
    status: 'SUBMITTED',
    label: 'Awaiting buyer confirmation',
    empty: 'No submitted invoices are waiting for buyer confirmation.',
  },
  {
    stage: 'verification',
    status: 'BUYER_CONFIRMED',
    label: 'Awaiting verification',
    empty: 'No buyer-confirmed invoices are waiting for verification.',
  },
];

type ListState = { kind: 'loading' } | { kind: 'loaded'; result: InvoicePage } | { kind: 'error'; message: string };

function stageParam(value: string | undefined): StageInfo {
  return STAGES.find((info) => info.stage === value) ?? STAGES[0];
}

/** Invoices waiting for an administrator, one review stage at a time. Actions happen on the review page. */
@Component({
  selector: 'ifp-invoice-queue',
  imports: [RouterLink, StatusBadgeComponent, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-queue.component.html',
  styleUrl: './admin.scss',
})
export class InvoiceQueueComponent {
  private readonly api = inject(InvoiceApiService);
  private readonly router = inject(Router);

  /** Query parameters, bound by the router. */
  readonly page = input<string>();
  readonly stage = input<string>();

  protected readonly stages = STAGES;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly current = computed(() => stageParam(this.stage()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
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
      const status = this.current().status;
      untracked(() => void this.load(page, status));
    });
  }

  protected refresh(): void {
    void this.load(this.pageIndex(), this.current().status);
  }

  protected stageLink(stage: QueueStage): Record<string, string | null> {
    return { stage: stage === STAGES[0].stage ? null : stage, page: null };
  }

  protected pageLink(index: number): Record<string, number | null> {
    return pageLink(index);
  }

  private async load(page: number, status: InvoiceStatus): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.list(page, status);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        void this.router.navigate([], { queryParams: pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
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
