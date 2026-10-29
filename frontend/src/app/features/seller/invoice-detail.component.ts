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

import { AgreementPanelComponent } from './agreement-panel.component';
import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { InvoiceEditFormComponent } from './invoice-edit-form.component';
import { failureMessage, formatAmount, formatDate, formatInstant } from './invoice-format';
import {
  CANCELLABLE_FROM,
  CANCEL_REASON_MAX,
  EDITABLE_FROM,
  InvoiceStatus,
  SELLER_ACTION_FROM,
  STATUS_INFO,
  SellerAction,
  VersionedInvoice,
} from './invoice.models';
import { InvoiceOffersComponent } from './invoice-offers.component';
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

/** Invoice statuses with a financing agreement to show (once an offer is accepted, while open for financing). */
const AGREEMENT_FROM: readonly InvoiceStatus[] = ['FINANCING_AVAILABLE', 'FINANCED', 'SETTLED'];

type ViewState = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'error'; message: string; missing: boolean };

@Component({
  selector: 'ifp-invoice-detail',
  imports: [RouterLink, StatusBadgeComponent, InvoiceOffersComponent, AgreementPanelComponent, InvoiceEditFormComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-detail.component.html',
  styleUrls: ['./invoice-detail.component.scss', './seller-dialog.scss'],
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
  protected readonly editing = signal(false);
  protected readonly cancelling = signal(false);
  protected readonly cancelReason = signal('');
  protected readonly cancelError = signal<string | null>(null);
  protected readonly financingRefresh = signal(0);
  protected readonly cancelReasonMax = CANCEL_REASON_MAX;

  protected readonly invoice = computed(() => this.current()?.invoice ?? null);
  protected readonly busy = computed(() => this.acting() !== null || this.cancelling() || this.editing());
  protected readonly canEdit = computed(() => {
    const status = this.invoice()?.status;
    return !!status && EDITABLE_FROM.includes(status) && !this.busy();
  });
  protected readonly canCancel = computed(() => {
    const status = this.invoice()?.status;
    return !!status && CANCELLABLE_FROM.includes(status) && !this.busy();
  });
  protected readonly showsAgreement = computed(() => {
    const status = this.invoice()?.status;
    return !!status && AGREEMENT_FROM.includes(status);
  });
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
  private readonly cancelDialog = viewChild<ElementRef<HTMLDialogElement>>('cancelDialog');
  private readonly cancelAlert = viewChild<ElementRef<HTMLElement>>('cancelAlert');
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
    return this.invoice()?.status === SELLER_ACTION_FROM[action] && !this.busy();
  }

  protected startEdit(): void {
    if (this.canEdit()) {
      this.notice.set(null);
      this.editing.set(true);
    }
  }

  protected onSaved(updated: VersionedInvoice): void {
    this.current.set(updated);
    this.editing.set(false);
    this.showNotice({ tone: 'success', message: 'Changes saved.' });
  }

  protected onDiscarded(): void {
    this.editing.set(false);
  }

  protected async onEditConflict(failure: InvoiceFailure): Promise<void> {
    this.editing.set(false);
    await this.handleFailure(failure, this.id());
  }

  protected openCancel(): void {
    if (!this.canCancel()) {
      return;
    }
    this.cancelReason.set('');
    this.cancelError.set(null);
    this.cancelDialog()?.nativeElement.showModal();
  }

  protected closeCancel(): void {
    if (!this.cancelling()) {
      this.cancelDialog()?.nativeElement.close();
    }
  }

  protected onCancelDialogCancel(event: Event): void {
    if (this.cancelling()) {
      event.preventDefault();
    }
  }

  protected onCancelReason(event: Event): void {
    this.cancelReason.set((event.target as HTMLTextAreaElement).value);
  }

  protected async confirmCancel(): Promise<void> {
    const current = this.current();
    if (!current || this.cancelling() || !CANCELLABLE_FROM.includes(current.invoice.status)) {
      return;
    }
    const reason = this.cancelReason().trim();
    if (reason.length > CANCEL_REASON_MAX) {
      this.showCancelError(`The reason must be at most ${CANCEL_REASON_MAX} characters.`);
      return;
    }
    this.cancelling.set(true);
    this.cancelError.set(null);
    try {
      const updated = await this.api.cancel(current.invoice.id, current.etag, reason === '' ? null : reason);
      this.cancelling.set(false);
      this.cancelDialog()?.nativeElement.close();
      this.current.set(updated);
      this.showNotice({ tone: 'success', message: 'Invoice cancelled.' });
    } catch (error) {
      this.cancelling.set(false);
      const failure: InvoiceFailure = error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' };
      if (failure.kind === 'network' || failure.kind === 'unavailable' || failure.kind === 'rate-limited' || failure.kind === 'unexpected') {
        this.showCancelError(failureMessage(failure));
        return;
      }
      this.cancelDialog()?.nativeElement.close();
      await this.handleFailure(failure, current.invoice.id);
    }
  }

  /** An offer decision may have changed the invoice and its agreement: reload both. */
  protected onOffersChanged(): void {
    this.financingRefresh.update((n) => n + 1);
    void this.load(this.id(), { keepNotice: true });
  }

  /** A repayment can settle the agreement; invoice-service follows through financing events. */
  protected onRepaid(): void {
    void this.load(this.id(), { keepNotice: true });
  }

  protected refresh(): void {
    this.notice.set(null);
    void this.load(this.id());
  }

  protected async run(action: ActionInfo): Promise<void> {
    const current = this.current();
    if (!current || this.busy() || current.invoice.status !== SELLER_ACTION_FROM[action.action]) {
      return;
    }
    this.acting.set(action.action);
    this.notice.set(null);
    try {
      const updated = await this.api.transition(action.action, current.invoice.id, current.etag);
      this.current.set(updated);
      this.showNotice({ tone: 'success', message: action.done });
    } catch (error) {
      await this.handleFailure(error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' }, current.invoice.id);
    } finally {
      this.acting.set(null);
    }
  }

  private async handleFailure(failure: InvoiceFailure, id: string): Promise<void> {
    if (failure.kind === 'not-found') {
      this.current.set(null);
      this.view.set({ kind: 'error', message: failureMessage(failure), missing: true });
      return;
    }
    const reload = RELOAD_AFTER.has(failure.kind);
    this.showNotice({ tone: reload ? 'warning' : 'danger', message: failureMessage(failure) });
    if (reload) {
      await this.load(id, { keepNotice: true });
    }
  }

  private showCancelError(message: string): void {
    this.cancelError.set(message);
    afterNextRender(() => this.cancelAlert()?.nativeElement.focus(), { injector: this.injector });
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
