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
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';

import { SellerFinancingApiService, financingFailureOf } from './financing-api.service';
import { badgeClass, checkRepaymentAmount, financingFailureMessage, formatRate } from './financing-format';
import {
  ACCEPTS_REPAYMENTS,
  AGREEMENT_STATUS_INFO,
  AgreementView,
  INSTALLMENT_STATUS_INFO,
} from './financing.models';
import { formatAmount, formatDate, formatInstant } from './invoice-format';
import { InvoiceStatus } from './invoice.models';
import { RepaymentKeysService } from './repayment-keys.service';

interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

type PanelState =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'loaded'; view: AgreementView }
  | { kind: 'error'; message: string };

/** Refusals that are definite answers about the agreement: close the dialog and show its current state. */
const CLOSE_ON: ReadonlySet<string> = new Set(['AGREEMENT_SETTLED', 'REPAYMENT_NOT_ALLOWED', 'INVALID_STATUS_TRANSITION']);

/** The financing agreement for an invoice: terms, payout, schedule, repayments and recording a repayment. */
@Component({
  selector: 'ifp-agreement-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agreement-panel.component.html',
  styleUrls: ['./financing-panel.scss', './seller-dialog.scss'],
})
export class AgreementPanelComponent {
  private readonly api = inject(SellerFinancingApiService);
  private readonly keys = inject(RepaymentKeysService);
  private readonly injector = inject(Injector);

  readonly invoiceId = input.required<string>();
  readonly invoiceStatus = input.required<InvoiceStatus>();
  /** Bump to reload, e.g. after an offer was accepted. */
  readonly refresh = input(0);
  /** Emitted after a repayment, which may settle the agreement and, through financing events, the invoice. */
  readonly repaid = output<void>();

  protected readonly statusInfo = AGREEMENT_STATUS_INFO;
  protected readonly installmentInfo = INSTALLMENT_STATUS_INFO;
  protected readonly badgeClass = badgeClass;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;
  protected readonly formatRate = formatRate;

  protected readonly state = signal<PanelState>({ kind: 'loading' });
  protected readonly notice = signal<Notice | null>(null);
  protected readonly amount = signal('');
  protected readonly amountError = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly submitting = signal(false);

  protected readonly view = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.view : null;
  });
  protected readonly canRepay = computed(() => {
    const agreement = this.view()?.agreement;
    return !!agreement && ACCEPTS_REPAYMENTS.includes(agreement.status) && agreement.outstanding > 0 && !this.submitting();
  });
  protected readonly repaidTotal = computed(() => {
    const agreement = this.view()?.agreement;
    return agreement ? agreement.principalRepaid + agreement.feeRepaid + agreement.penaltyRepaid : 0;
  });

  private readonly repayDialog = viewChild.required<ElementRef<HTMLDialogElement>>('repayDialog');
  private readonly amountInput = viewChild<ElementRef<HTMLInputElement>>('amountInput');
  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private readonly dialogAlert = viewChild<ElementRef<HTMLElement>>('dialogAlert');
  private request = 0;

  constructor() {
    effect(() => {
      const id = this.invoiceId();
      this.refresh();
      untracked(() => void this.load(id));
    });
  }

  protected reload(): void {
    this.notice.set(null);
    void this.load(this.invoiceId());
  }

  protected openRepay(): void {
    if (!this.canRepay()) {
      return;
    }
    this.amountError.set(null);
    this.dialogError.set(null);
    this.repayDialog().nativeElement.showModal();
  }

  protected closeRepay(): void {
    if (!this.submitting()) {
      this.repayDialog().nativeElement.close();
    }
  }

  protected onCancel(event: Event): void {
    if (this.submitting()) {
      event.preventDefault();
    }
  }

  protected onAmount(event: Event): void {
    this.amount.set((event.target as HTMLInputElement).value);
    this.amountError.set(null);
  }

  protected fillOutstanding(): void {
    const agreement = this.view()?.agreement;
    if (agreement && !this.submitting()) {
      this.amount.set(agreement.outstanding.toFixed(2));
      this.amountError.set(null);
    }
  }

  protected async submitRepayment(): Promise<void> {
    const agreement = this.view()?.agreement;
    if (!agreement || this.submitting()) {
      return;
    }
    this.dialogError.set(null);
    const check = checkRepaymentAmount(this.amount(), agreement.outstanding);
    if ('error' in check) {
      this.amountError.set(check.error);
      this.amountInput()?.nativeElement.focus();
      return;
    }
    const key = this.keys.keyFor(agreement.id, check.amount);
    this.submitting.set(true);
    try {
      const { repayment, replayed } = await this.api.recordRepayment(agreement.id, check.amount, key);
      this.keys.forget(agreement.id);
      this.submitting.set(false);
      this.amount.set('');
      this.repayDialog().nativeElement.close();
      const paid = formatAmount(repayment.amount, repayment.currency);
      this.showNotice({
        tone: 'success',
        message: replayed
          ? `This repayment of ${paid} was already recorded on ${formatInstant(repayment.receivedAt)}. It was not recorded a second time.`
          : `Repayment of ${paid} recorded.`,
      });
      await this.load(this.invoiceId(), { keepNotice: true });
      this.repaid.emit();
    } catch (error) {
      this.submitting.set(false);
      const failure = financingFailureOf(error);
      const message = financingFailureMessage(failure);
      if (failure.kind === 'rule') {
        // Nothing was recorded; the outstanding amount may have changed (penalty accrues daily), so refresh it.
        this.amountError.set(message);
        this.amountInput()?.nativeElement.focus();
        void this.load(this.invoiceId(), { keepNotice: true });
        return;
      }
      if (failure.kind === 'conflict' && failure.code !== null && CLOSE_ON.has(failure.code)) {
        this.keys.forget(agreement.id);
        this.repayDialog().nativeElement.close();
        this.showNotice({ tone: 'warning', message });
        await this.load(this.invoiceId(), { keepNotice: true });
        this.repaid.emit();
        return;
      }
      if (failure.kind === 'conflict' && failure.code === 'IDEMPOTENCY_KEY_REUSED') {
        this.keys.forget(agreement.id);
        this.showDialogError(message);
        void this.load(this.invoiceId(), { keepNotice: true });
        return;
      }
      if (failure.kind === 'network' || failure.kind === 'unavailable' || failure.kind === 'unexpected') {
        this.showDialogError(
          `${message} It is not known whether the repayment was recorded. Trying again with the same amount is safe: it will not be recorded twice.`,
        );
        return;
      }
      this.showDialogError(message);
    }
  }

  private async load(invoiceId: string, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    if (this.state().kind !== 'loaded') {
      this.state.set({ kind: 'loading' });
    }
    try {
      const agreement = await this.api.agreementForInvoice(invoiceId);
      const view = agreement ? await this.api.agreementView(agreement.id) : null;
      if (request === this.request) {
        this.state.set(view ? { kind: 'loaded', view } : { kind: 'none' });
      }
    } catch (error) {
      if (request !== this.request) {
        return;
      }
      const message = financingFailureMessage(financingFailureOf(error));
      if (this.state().kind !== 'loaded') {
        this.state.set({ kind: 'error', message });
      } else if (!options.keepNotice) {
        this.showNotice({ tone: 'danger', message: `The latest agreement details could not be loaded. ${message}` });
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
