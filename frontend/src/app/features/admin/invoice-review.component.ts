import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from '../seller/invoice-api.service';
import { failureMessage, formatAmount, formatDate, formatInstant } from '../seller/invoice-format';
import { REJECT_REASON_MAX, REVIEW_ACTION_FROM, ReviewAction, VersionedInvoice } from '../seller/invoice.models';
import { StatusBadgeComponent } from '../seller/status-badge.component';
import { AdminNavComponent } from './admin-nav.component';
import { Notice } from './admin-shared';

interface ActionInfo {
  action: Exclude<ReviewAction, 'reject'>;
  label: string;
  busyLabel: string;
  hint: string;
  done: string;
}

const ACTIONS: readonly ActionInfo[] = [
  {
    action: 'buyer-confirmation',
    label: 'Record buyer confirmation',
    busyLabel: 'Recording confirmation…',
    hint: 'For submitted invoices, once the buyer has confirmed they owe this amount.',
    done: 'Buyer confirmation recorded. The invoice now waits for verification.',
  },
  {
    action: 'verify',
    label: 'Verify invoice',
    busyLabel: 'Verifying…',
    hint: 'For buyer-confirmed invoices. The seller can then open it for financing.',
    done: 'Invoice verified. The seller can now open it for financing.',
  },
];

/** Failures that mean the invoice changed under the administrator: show the latest version rather than the stale one. */
const RELOAD_AFTER: ReadonlySet<InvoiceFailure['kind']> = new Set(['stale', 'concurrent', 'invalid-transition', 'not-editable']);

const MISSING_MESSAGE = 'This invoice does not exist. It may have been removed.';

type ViewState = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'error'; message: string; missing: boolean };

/** Admins see every invoice, so "belongs to another account" never applies to them. */
function reviewFailureMessage(failure: InvoiceFailure): string {
  switch (failure.kind) {
    case 'not-found':
      return MISSING_MESSAGE;
    case 'invalid':
      return `Enter a reason for the rejection, up to ${REJECT_REASON_MAX} characters.`;
    default:
      return failureMessage(failure);
  }
}

/** One invoice under review, with the verification steps invoice-service allows from its current status. */
@Component({
  selector: 'ifp-invoice-review',
  imports: [RouterLink, StatusBadgeComponent, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-review.component.html',
  styleUrls: ['./admin.scss', './invoice-review.component.scss'],
})
export class InvoiceReviewComponent {
  private readonly api = inject(InvoiceApiService);
  private readonly injector = inject(Injector);

  /** Route parameter, bound by the router. */
  readonly id = input.required<string>();

  protected readonly actions = ACTIONS;
  protected readonly reasonMax = REJECT_REASON_MAX;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;

  protected readonly view = signal<ViewState>({ kind: 'loading' });
  protected readonly current = signal<VersionedInvoice | null>(null);
  protected readonly acting = signal<ReviewAction | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly reason = signal('');
  protected readonly reasonError = signal<string | null>(null);

  protected readonly invoice = computed(() => this.current()?.invoice ?? null);
  protected readonly errorState = computed(() => {
    const view = this.view();
    return view.kind === 'error' ? view : null;
  });
  protected readonly stageHint = computed(() => {
    switch (this.invoice()?.status) {
      case 'SUBMITTED':
        return 'Confirm with the buyer that they owe this amount, then record it here, or reject the invoice.';
      case 'BUYER_CONFIRMED':
        return 'The buyer has confirmed this invoice. Check the details and verify it, or reject it.';
      default:
        return 'This invoice is no longer waiting for review, so no review steps are available.';
    }
  });
  protected readonly queueLink = computed(() => ({
    stage: this.invoice()?.status === 'BUYER_CONFIRMED' ? 'verification' : null,
  }));
  protected readonly milestones = computed(() => {
    const invoice = this.invoice();
    if (!invoice) {
      return [];
    }
    const steps: [string, string | null][] = [
      ['Created', invoice.createdAt],
      ['Submitted', invoice.submittedAt],
      ['Buyer confirmed', invoice.buyerConfirmedAt],
      ['Verified', invoice.verifiedAt],
    ];
    return steps.filter((step): step is [string, string] => step[1] !== null);
  });

  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private readonly reasonBox = viewChild<ElementRef<HTMLTextAreaElement>>('reasonBox');
  private request = 0;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => void this.load(id));
    });
  }

  protected allows(action: ReviewAction): boolean {
    const status = this.invoice()?.status;
    return status !== undefined && REVIEW_ACTION_FROM[action].includes(status);
  }

  protected canRun(action: ReviewAction): boolean {
    return this.allows(action) && this.acting() === null;
  }

  protected refresh(): void {
    this.notice.set(null);
    void this.load(this.id());
  }

  protected updateReason(value: string): void {
    this.reason.set(value);
    if (this.reasonError() && value.trim().length > 0) {
      this.reasonError.set(null);
    }
  }

  protected run(action: ActionInfo): Promise<void> {
    return this.perform(action.action, action.done);
  }

  protected reject(event: Event): Promise<void> {
    event.preventDefault();
    const reason = this.reason().trim();
    if (!this.canRun('reject')) {
      return Promise.resolve();
    }
    if (reason.length === 0 || reason.length > REJECT_REASON_MAX) {
      this.reasonError.set(
        reason.length === 0
          ? 'Enter the reason for rejecting this invoice. The seller will see it.'
          : `Keep the reason to ${REJECT_REASON_MAX} characters or fewer.`,
      );
      this.reasonBox()?.nativeElement.focus();
      return Promise.resolve();
    }
    return this.perform('reject', 'Invoice rejected. The seller can see your reason.', reason);
  }

  private async perform(action: ReviewAction, done: string, reason?: string): Promise<void> {
    const current = this.current();
    if (!current || !this.canRun(action)) {
      return;
    }
    this.acting.set(action);
    this.notice.set(null);
    try {
      const updated = await this.api.review(action, current.invoice.id, current.etag, reason);
      this.current.set(updated);
      if (action === 'reject') {
        this.reason.set('');
      }
      this.showNotice({ tone: 'success', message: done });
    } catch (error) {
      const failure: InvoiceFailure = error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' };
      if (failure.kind === 'not-found') {
        this.current.set(null);
        this.view.set({ kind: 'error', message: MISSING_MESSAGE, missing: true });
        return;
      }
      const reload = RELOAD_AFTER.has(failure.kind);
      this.showNotice({ tone: reload ? 'warning' : 'danger', message: reviewFailureMessage(failure) });
      if (reload) {
        await this.load(current.invoice.id, { keepNotice: true });
      }
    } finally {
      this.acting.set(null);
    }
  }

  private async load(id: string, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    if (!this.current()) {
      this.view.set({ kind: 'loading' });
    }
    try {
      const result = await this.api.get(id);
      if (request === this.request) {
        this.current.set(result);
        this.view.set({ kind: 'loaded' });
      }
    } catch (error) {
      if (request !== this.request) {
        return;
      }
      const failure: InvoiceFailure = error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' };
      if (failure.kind === 'not-found' || !this.current()) {
        this.current.set(null);
        this.view.set({ kind: 'error', message: reviewFailureMessage(failure), missing: failure.kind === 'not-found' });
      } else if (!options.keepNotice) {
        this.showNotice({ tone: 'danger', message: `The latest details could not be loaded. ${reviewFailureMessage(failure)}` });
      }
    }
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }
}
