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
import { Router, RouterLink } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { failureMessage, formatAmount, formatDate, formatInstant } from './invoice-format';
import { SELLER_ACTION_FROM, STATUS_INFO, SellerAction, VersionedInvoice } from './invoice.models';
import { StatusBadgeComponent } from './status-badge.component';

interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

interface ActionInfo {
  action: SellerAction;
  label: string;
  busyLabel: string;
  hint: string;
  done: string;
}

const ACTIONS: readonly ActionInfo[] = [
  {
    action: 'submit',
    label: 'Submit for verification',
    busyLabel: 'Submitting…',
    hint: 'For drafts. An administrator then confirms the invoice with the buyer; the details can no longer be edited.',
    done: 'Invoice submitted for verification.',
  },
  {
    action: 'list-for-financing',
    label: 'Make available to financiers',
    busyLabel: 'Opening for financing…',
    hint: 'For verified invoices. Financiers can then see the invoice and make offers.',
    done: 'The invoice is now open for financing.',
  },
];

/** Failures that mean the invoice changed under the seller: show the latest version rather than the stale one. */
const RELOAD_AFTER: ReadonlySet<InvoiceFailure['kind']> = new Set(['stale', 'concurrent', 'invalid-transition', 'not-editable']);

type ViewState = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'error'; message: string; missing: boolean };

@Component({
  selector: 'ifp-invoice-detail',
  imports: [RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.scss',
})
export class InvoiceDetailComponent {
  private readonly api = inject(InvoiceApiService);
  private readonly injector = inject(Injector);

  /** Route parameter, bound by the router. */
  readonly id = input.required<string>();

  protected readonly actions = ACTIONS;
  protected readonly statusInfo = STATUS_INFO;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;

  protected readonly view = signal<ViewState>({ kind: 'loading' });
  protected readonly current = signal<VersionedInvoice | null>(null);
  protected readonly acting = signal<SellerAction | null>(null);
  protected readonly notice = signal<Notice | null>(null);

  protected readonly invoice = computed(() => this.current()?.invoice ?? null);
  protected readonly errorState = computed(() => {
    const view = this.view();
    return view.kind === 'error' ? view : null;
  });
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
      ['Financed', invoice.financedAt],
      ['Settled', invoice.settledAt],
    ];
    return steps.filter((step): step is [string, string] => step[1] !== null);
  });

  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private request = 0;

  constructor() {
    if (inject(Router).currentNavigation()?.extras.state?.['created'] === true) {
      this.notice.set({ tone: 'success', message: 'Invoice saved as a draft. Check the details, then submit it when ready.' });
    }
    effect(() => {
      const id = this.id();
      untracked(() => void this.load(id));
    });
  }

  protected canRun(action: SellerAction): boolean {
    return this.invoice()?.status === SELLER_ACTION_FROM[action] && this.acting() === null;
  }

  protected refresh(): void {
    this.notice.set(null);
    void this.load(this.id());
  }

  protected async run(action: ActionInfo): Promise<void> {
    const current = this.current();
    if (!current || this.acting() !== null || current.invoice.status !== SELLER_ACTION_FROM[action.action]) {
      return;
    }
    this.acting.set(action.action);
    this.notice.set(null);
    try {
      const updated = await this.api.transition(action.action, current.invoice.id, current.etag);
      this.current.set(updated);
      this.showNotice({ tone: 'success', message: action.done });
    } catch (error) {
      const failure: InvoiceFailure = error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' };
      if (failure.kind === 'not-found') {
        this.current.set(null);
        this.view.set({ kind: 'error', message: failureMessage(failure), missing: true });
        return;
      }
      const reload = RELOAD_AFTER.has(failure.kind);
      this.showNotice({ tone: reload ? 'warning' : 'danger', message: failureMessage(failure) });
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
        this.view.set({ kind: 'error', message: failureMessage(failure), missing: failure.kind === 'not-found' });
      } else if (!options.keepNotice) {
        this.showNotice({ tone: 'danger', message: `The latest details could not be loaded. ${failureMessage(failure)}` });
      }
    }
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }
}
