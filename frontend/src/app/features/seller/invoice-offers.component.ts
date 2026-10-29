import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';

import { FinancingFailure, SellerFinancingApiService, financingFailureOf } from './financing-api.service';
import { badgeClass, financingFailureMessage, formatRate } from './financing-format';
import { OFFER_STATUS_INFO, Offer, REASON_MAX } from './financing.models';
import { formatAmount, formatInstant } from './invoice-format';

interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

type OffersState =
  | { kind: 'loading' }
  | { kind: 'loaded'; offers: Offer[]; truncated: boolean }
  | { kind: 'error'; message: string };

type Decision = 'accept' | 'reject';

/**
 * Failures after which the outcome is unknown or can simply be retried: the dialog stays open so the seller can try
 * again. Anything else is a definite answer about the offer's state, so the dialog closes and the offers reload.
 */
const RETRY_IN_DIALOG: ReadonlySet<FinancingFailure['kind']> = new Set(['network', 'unavailable', 'unexpected', 'rate-limited', 'invalid']);

/** Offers on an invoice that is open for financing, with the seller's accept and reject decisions. */
@Component({
  selector: 'ifp-invoice-offers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-offers.component.html',
  styleUrls: ['./financing-panel.scss', './seller-dialog.scss'],
})
export class InvoiceOffersComponent {
  private readonly api = inject(SellerFinancingApiService);
  private readonly injector = inject(Injector);

  readonly invoiceId = input.required<string>();
  /** Emitted after a decision, or a refusal that shows the offers or the invoice changed. */
  readonly changed = output<void>();

  protected readonly statusInfo = OFFER_STATUS_INFO;
  protected readonly badgeClass = badgeClass;
  protected readonly formatAmount = formatAmount;
  protected readonly formatInstant = formatInstant;
  protected readonly formatRate = formatRate;
  protected readonly reasonMax = REASON_MAX;

  protected readonly state = signal<OffersState>({ kind: 'loading' });
  protected readonly notice = signal<Notice | null>(null);
  protected readonly selected = signal<Offer | null>(null);
  protected readonly busy = signal<Decision | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly now = signal(Date.now());

  private readonly acceptDialog = viewChild.required<ElementRef<HTMLDialogElement>>('acceptDialog');
  private readonly rejectDialog = viewChild.required<ElementRef<HTMLDialogElement>>('rejectDialog');
  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private readonly dialogAlert = viewChild<ElementRef<HTMLElement>>('dialogAlert');
  private request = 0;

  constructor() {
    effect(() => {
      const id = this.invoiceId();
      untracked(() => void this.load(id));
    });
  }

  protected isExpired(offer: Offer): boolean {
    return Date.parse(offer.expiresAt) <= this.now();
  }

  /** OfferService only lets the seller decide on OFFERED offers that have not expired. */
  protected canDecide(offer: Offer): boolean {
    return offer.status === 'OFFERED' && !this.isExpired(offer) && this.busy() === null;
  }

  protected reload(): void {
    this.notice.set(null);
    void this.load(this.invoiceId());
  }

  protected open(decision: Decision, offer: Offer): void {
    this.now.set(Date.now());
    if (!this.canDecide(offer)) {
      return;
    }
    this.selected.set(offer);
    this.dialogError.set(null);
    this.reason.set('');
    const dialog = this.dialogFor(decision);
    dialog.showModal();
    // The dialog's content renders after showModal(), so its autofocus attribute alone does not take effect.
    afterNextRender(() => dialog.querySelector<HTMLElement>('[autofocus]')?.focus(), { injector: this.injector });
  }

  protected close(decision: Decision): void {
    if (this.busy() === null) {
      this.dialogFor(decision).close();
    }
  }

  /** Escape must not close the dialog while its request is in flight. */
  protected onCancel(event: Event): void {
    if (this.busy() !== null) {
      event.preventDefault();
    }
  }

  protected onReason(event: Event): void {
    this.reason.set((event.target as HTMLTextAreaElement).value);
  }

  protected async confirm(decision: Decision): Promise<void> {
    const offer = this.selected();
    if (!offer || this.busy() !== null) {
      return;
    }
    const reason = this.reason().trim();
    if (decision === 'reject' && reason.length > REASON_MAX) {
      this.showDialogError(`The reason must be at most ${REASON_MAX} characters.`);
      return;
    }
    this.busy.set(decision);
    this.dialogError.set(null);
    try {
      if (decision === 'accept') {
        await this.api.acceptOffer(offer.id);
      } else {
        await this.api.rejectOffer(offer.id, reason === '' ? null : reason);
      }
      this.busy.set(null);
      this.dialogFor(decision).close();
      this.showNotice({
        tone: 'success',
        message:
          decision === 'accept'
            ? 'Offer accepted. A financing agreement was created and now waits for the financier’s approval; the other open offers on this invoice were closed.'
            : 'Offer rejected.',
      });
      await this.load(offer.invoiceId, { keepNotice: true });
      this.changed.emit();
    } catch (error) {
      const failure = financingFailureOf(error);
      this.busy.set(null);
      if (RETRY_IN_DIALOG.has(failure.kind)) {
        const uncertain = failure.kind === 'network' || failure.kind === 'unexpected' || failure.kind === 'unavailable';
        this.showDialogError(
          uncertain
            ? `${financingFailureMessage(failure)} If the offer was already ${decision === 'accept' ? 'accepted' : 'rejected'}, trying again shows its current status.`
            : financingFailureMessage(failure),
        );
        return;
      }
      this.dialogFor(decision).close();
      this.showNotice({ tone: 'warning', message: financingFailureMessage(failure) });
      await this.load(offer.invoiceId, { keepNotice: true });
      this.changed.emit();
    }
  }

  private dialogFor(decision: Decision): HTMLDialogElement {
    return (decision === 'accept' ? this.acceptDialog() : this.rejectDialog()).nativeElement;
  }

  private async load(id: string, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    if (this.state().kind !== 'loaded') {
      this.state.set({ kind: 'loading' });
    }
    try {
      const { offers, truncated } = await this.api.offersForInvoice(id);
      if (request === this.request) {
        this.now.set(Date.now());
        this.state.set({ kind: 'loaded', offers, truncated });
      }
    } catch (error) {
      if (request !== this.request) {
        return;
      }
      const message = financingFailureMessage(financingFailureOf(error));
      if (this.state().kind !== 'loaded') {
        this.state.set({ kind: 'error', message });
      } else if (!options.keepNotice) {
        this.showNotice({ tone: 'danger', message: `The latest offers could not be loaded. ${message}` });
      }
    }
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }

  private showDialogError(message: string): void {
    this.dialogError.set(message);
    afterNextRender(() => this.dialogAlert()?.nativeElement.focus(), { injector: this.injector });
  }
}
