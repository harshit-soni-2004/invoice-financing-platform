import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AccessProblemComponent } from './access-problem.component';
import { FinancierBadgeComponent } from './financier-badge.component';
import { FinancierNavComponent } from './financier-nav.component';
import { failureMessage, formatAmount, formatDate, formatInstant, formatRate } from './financier-format';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { FINANCING_LIMITS, OFFER_STATUS_INFO, Offer, OfferDraft, OfferField, Opportunity } from './financier.models';
import {
  AMOUNT,
  defaultExpiry,
  estimatedFee,
  expiryProblem,
  localDateTimeToInstant,
  normalizeAmount,
  percentToFraction,
} from './offer-terms';

interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

type OpportunityState =
  | { kind: 'loading' }
  | { kind: 'open'; opportunity: Opportunity }
  /** 404: not listed, too close to its due date, already financed, or never existed. */
  | { kind: 'closed' }
  | { kind: 'error'; failure: FinancingFailure };

type OffersState = { kind: 'loading' } | { kind: 'loaded'; offers: Offer[] } | { kind: 'error'; message: string };

/** DOM order, so the first invalid field gets focus. */
const FIELD_IDS: readonly [OfferField, string][] = [
  ['advanceAmount', 'offer-advance'],
  ['annualRate', 'offer-rate'],
  ['processingFee', 'offer-fee'],
  ['installmentCount', 'offer-installments'],
  ['expiresAt', 'offer-expires'],
];

const LABELS: Readonly<Record<OfferField, string>> = {
  advanceAmount: 'Advance',
  annualRate: 'Annual rate',
  processingFee: 'Processing fee',
  installmentCount: 'Installments',
  expiresAt: 'Offer expiry',
};

function required(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

function amountFormat(control: AbstractControl<string>): ValidationErrors | null {
  const value = normalizeAmount(control.value);
  return value === '' || AMOUNT.test(value) ? null : { format: true };
}

/** Failures after which the invoice or the offers may have changed: show the latest state. */
const RELOAD_AFTER_CREATE: ReadonlySet<FinancingFailure['kind']> = new Set([
  'duplicate-offer',
  'already-financed',
  'not-found',
  'concurrent',
  'conflict',
]);

@Component({
  selector: 'ifp-opportunity-detail',
  imports: [ReactiveFormsModule, RouterLink, AccessProblemComponent, FinancierBadgeComponent, FinancierNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './opportunity-detail.component.html',
  styleUrl: './financier-detail.scss',
})
export class OpportunityDetailComponent {
  private readonly api = inject(FinancingApiService);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Route parameter, bound by the router. */
  readonly invoiceId = input.required<string>();

  protected readonly limits = FINANCING_LIMITS;
  protected readonly offerStatusInfo = OFFER_STATUS_INFO;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;
  protected readonly formatRate = formatRate;

  protected readonly opportunityState = signal<OpportunityState>({ kind: 'loading' });
  protected readonly offersState = signal<OffersState>({ kind: 'loading' });
  protected readonly notice = signal<Notice | null>(null);
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly withdrawing = signal<string | null>(null);

  protected readonly opportunity = computed(() => {
    const state = this.opportunityState();
    return state.kind === 'open' ? state.opportunity : null;
  });

  protected readonly loadFailure = computed(() => {
    const state = this.opportunityState();
    return state.kind === 'error' ? state.failure : null;
  });

  protected readonly accessProblem = computed(() => {
    const kind = this.loadFailure()?.kind;
    return kind === 'forbidden' || kind === 'unauthenticated' ? kind : null;
  });

  protected readonly offers = computed(() => {
    const state = this.offersState();
    return state.kind === 'loaded' ? state.offers : null;
  });

  protected readonly offersErrorMessage = computed(() => {
    const state = this.offersState();
    return state.kind === 'error' ? state.message : null;
  });

  protected readonly formatFailure = failureMessage;

  protected readonly openOffer = computed(() => this.offers()?.find((offer) => offer.status === 'OFFERED') ?? null);

  protected readonly maxInstallments = computed(() =>
    Math.max(0, Math.min(FINANCING_LIMITS.maxInstallments, this.opportunity()?.daysToMaturity ?? 0)),
  );

  protected readonly form = this.fb.group({
    advanceAmount: this.fb.control('', [required, amountFormat, this.advanceRule()]),
    annualRate: this.fb.control('', [required, this.rateRule()]),
    processingFee: this.fb.control('', [amountFormat, this.feeRule()]),
    installmentCount: this.fb.control('1', [required, this.installmentRule()]),
    expiresAt: this.fb.control('', [required, this.expiryRule()]),
  });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  /** Fee and total if disbursed today; null until the advance and rate are valid. */
  protected readonly estimate = computed(() => {
    const opportunity = this.opportunity();
    const value = this.formValue();
    const advance = normalizeAmount(value.advanceAmount ?? '');
    const rate = percentToFraction(value.annualRate ?? '');
    const fee = normalizeAmount(value.processingFee ?? '') || '0';
    if (!opportunity || !AMOUNT.test(advance) || rate === null || !AMOUNT.test(fee) || Number(advance) <= 0) {
      return null;
    }
    const feeAmount = estimatedFee(Number(advance), Number(rate), opportunity.daysToMaturity, Number(fee));
    return { fee: feeAmount, total: Math.round((Number(advance) + feeAmount) * 100) / 100, currency: opportunity.currency };
  });

  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');
  private request = 0;

  constructor() {
    const { advanceAmount, processingFee } = this.form.controls;
    advanceAmount.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => processingFee.updateValueAndValidity({ emitEvent: false }));
    effect(() => {
      const id = this.invoiceId();
      untracked(() => void this.load(id));
    });
  }

  protected refresh(): void {
    this.notice.set(null);
    void this.load(this.invoiceId());
  }

  protected reloadOffers(): void {
    void this.loadOffers(this.invoiceId());
  }

  protected error(field: OfferField): string | null {
    const control = this.form.controls[field];
    if (!control.errors || !(control.touched || this.submitted())) {
      return null;
    }
    const errors = control.errors;
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    if (errors['required']) {
      return field === 'expiresAt' ? 'Choose when the offer expires.' : `Enter the ${LABELS[field].toLowerCase()}.`;
    }
    const opportunity = this.opportunity();
    switch (field) {
      case 'advanceAmount':
        if (errors['max'] && opportunity) {
          return `The advance can be at most ${formatAmount(opportunity.maxAdvanceAmount, opportunity.currency)} for this invoice.`;
        }
        return errors['min']
          ? 'The advance must be at least ₹0.01.'
          : 'Enter an amount in rupees with at most 2 decimal places, like 480000.50.';
      case 'annualRate':
        return errors['range']
          ? `The rate must be more than 0% and at most ${FINANCING_LIMITS.maxAnnualRatePercent}% a year.`
          : 'Enter a yearly percentage with at most 2 decimal places, like 12.5.';
      case 'processingFee':
        return errors['notBelowAdvance']
          ? 'The processing fee must be less than the advance.'
          : 'Enter an amount in rupees with at most 2 decimal places, or leave it empty for no fee.';
      case 'installmentCount':
        return this.maxInstallments() < 1
          ? 'This invoice is too close to its due date for any installments.'
          : `Enter a whole number from 1 to ${this.maxInstallments()}. Each installment needs at least one day before the due date.`;
      case 'expiresAt':
        switch (errors['expiry']) {
          case 'past':
            return 'The expiry must be in the future.';
          case 'too-far':
            return `The offer can stay open for at most ${FINANCING_LIMITS.maxOfferValidityDays} days.`;
          case 'after-due':
            return 'The offer must expire before the invoice due date starts (midnight, India time).';
          default:
            return 'Enter a valid date and time.';
        }
    }
  }

  protected describedBy(field: OfferField, hintId?: string): string | null {
    const ids = [hintId, this.error(field) ? `${this.idFor(field)}-error` : undefined].filter(Boolean);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected async submit(): Promise<void> {
    const opportunity = this.opportunity();
    if (this.submitting() || !opportunity || this.openOffer()) {
      return;
    }
    this.submitted.set(true);
    this.formError.set(null);
    this.notice.set(null);
    // Time passes while the form is open, so the expiry is checked again against the current time.
    this.form.controls.expiresAt.updateValueAndValidity();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalid();
      return;
    }

    const value = this.form.getRawValue();
    const fee = normalizeAmount(value.processingFee);
    const draft: OfferDraft = {
      invoiceId: opportunity.invoiceId,
      advanceAmount: normalizeAmount(value.advanceAmount),
      annualRate: percentToFraction(value.annualRate)!,
      processingFee: fee === '' ? '0.00' : fee,
      installmentCount: Number(value.installmentCount),
      expiresAt: localDateTimeToInstant(value.expiresAt)!.toISOString(),
    };

    this.submitting.set(true);
    try {
      const offer = await this.api.createOffer(draft);
      this.offersState.update((state) =>
        state.kind === 'loaded' ? { kind: 'loaded', offers: [offer, ...state.offers.filter((o) => o.id !== offer.id)] } : { kind: 'loaded', offers: [offer] },
      );
      this.resetForm(opportunity);
      this.showNotice({ tone: 'success', message: 'Offer sent. The seller can now accept or reject it.' });
    } catch (error) {
      await this.showCreateFailure(error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' });
    } finally {
      this.submitting.set(false);
    }
  }

  protected async withdraw(offer: Offer): Promise<void> {
    if (this.withdrawing() !== null || offer.status !== 'OFFERED') {
      return;
    }
    this.withdrawing.set(offer.id);
    this.notice.set(null);
    try {
      const updated = await this.api.withdrawOffer(offer.id);
      this.offersState.update((state) =>
        state.kind === 'loaded' ? { kind: 'loaded', offers: state.offers.map((o) => (o.id === updated.id ? updated : o)) } : state,
      );
      this.showNotice({ tone: 'success', message: 'Offer withdrawn. You can make a new offer while the invoice stays open.' });
    } catch (error) {
      const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
      const reload = failure.kind === 'invalid-transition' || failure.kind === 'not-found' || failure.kind === 'concurrent';
      this.showNotice({ tone: reload ? 'warning' : 'danger', message: failureMessage(failure) });
      if (reload) {
        await this.loadOffers(this.invoiceId());
      }
    } finally {
      this.withdrawing.set(null);
    }
  }

  private async load(invoiceId: string): Promise<void> {
    const request = ++this.request;
    if (this.opportunity()?.invoiceId !== invoiceId) {
      this.opportunityState.set({ kind: 'loading' });
      this.offersState.set({ kind: 'loading' });
    }
    await Promise.all([this.loadOpportunity(invoiceId, request), this.loadOffers(invoiceId, request)]);
  }

  private async loadOpportunity(invoiceId: string, request = this.request): Promise<void> {
    const wasOpen = this.opportunity()?.invoiceId === invoiceId;
    try {
      const opportunity = await this.api.opportunity(invoiceId);
      if (request !== this.request) {
        return;
      }
      this.opportunityState.set({ kind: 'open', opportunity });
      if (wasOpen) {
        // The cap and days to maturity may have changed; the user's input stays.
        Object.values(this.form.controls).forEach((control) => control.updateValueAndValidity());
      } else {
        this.resetForm(opportunity);
      }
    } catch (error) {
      if (request !== this.request) {
        return;
      }
      const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
      this.opportunityState.set(failure.kind === 'not-found' ? { kind: 'closed' } : { kind: 'error', failure });
    }
  }

  private async loadOffers(invoiceId: string, request = this.request): Promise<void> {
    if (this.offersState().kind !== 'loaded') {
      this.offersState.set({ kind: 'loading' });
    }
    try {
      const offers = await this.api.offersForInvoice(invoiceId);
      if (request === this.request) {
        this.offersState.set({ kind: 'loaded', offers });
      }
    } catch (error) {
      if (request === this.request) {
        const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
        this.offersState.set({ kind: 'error', message: failureMessage(failure) });
      }
    }
  }

  private async showCreateFailure(failure: FinancingFailure): Promise<void> {
    switch (failure.kind) {
      case 'invalid': {
        const fields = Object.entries(failure.fieldErrors) as [OfferField, string][];
        for (const [field, message] of fields) {
          this.setServerError(field, `${LABELS[field]} ${message}.`);
        }
        if (fields.length > 0) {
          this.formError.set(failureMessage(failure));
          this.focusFirstInvalid();
        } else {
          this.showFormError('The offer was not accepted. Check the terms and try again.');
        }
        return;
      }
      case 'rule': {
        const message = failureMessage(failure);
        if (failure.field) {
          this.setServerError(failure.field, message.endsWith('.') ? message : `${message}.`);
          this.formError.set(message);
          this.focusFirstInvalid();
          return;
        }
        // INVOICE_NOT_ELIGIBLE and similar: the invoice itself changed, and the form may disappear on reload.
        this.showNotice({ tone: 'danger', message });
        await this.load(this.invoiceId());
        return;
      }
      case 'unexpected':
      case 'network':
      case 'unavailable':
        this.showNotice({
          tone: 'warning',
          message: `${failureMessage(failure)} The offer may or may not have been sent; your offers below show the latest state.`,
        });
        await this.loadOffers(this.invoiceId());
        return;
      default:
        if (RELOAD_AFTER_CREATE.has(failure.kind)) {
          this.showNotice({ tone: 'warning', message: failureMessage(failure) });
          await this.load(this.invoiceId());
        } else {
          this.showFormError(failureMessage(failure));
        }
    }
  }

  private resetForm(opportunity: Opportunity): void {
    this.form.reset({
      advanceAmount: '',
      annualRate: '',
      processingFee: '',
      installmentCount: '1',
      expiresAt: defaultExpiry(opportunity.dueDate, Date.now()),
    });
    this.submitted.set(false);
    this.formError.set(null);
  }

  private advanceRule(): ValidatorFn {
    return (control: AbstractControl<string>) => {
      const value = normalizeAmount(control.value);
      if (!AMOUNT.test(value)) {
        return null;
      }
      if (Number(value) < 0.01) {
        return { min: true };
      }
      const cap = this.opportunity()?.maxAdvanceAmount;
      return cap !== undefined && Number(value) > cap ? { max: true } : null;
    };
  }

  private rateRule(): ValidatorFn {
    return (control: AbstractControl<string>) => {
      if (control.value.trim() === '') {
        return null;
      }
      const fraction = percentToFraction(control.value);
      if (fraction === null) {
        return { format: true };
      }
      const percent = Number(fraction) * 100;
      return percent <= 0 || percent > FINANCING_LIMITS.maxAnnualRatePercent ? { range: true } : null;
    };
  }

  private feeRule(): ValidatorFn {
    return (control: AbstractControl<string>) => {
      const fee = normalizeAmount(control.value);
      const advance = normalizeAmount(control.parent?.get('advanceAmount')?.value ?? '');
      if (fee === '' || !AMOUNT.test(fee) || !AMOUNT.test(advance)) {
        return null;
      }
      return Number(fee) >= Number(advance) ? { notBelowAdvance: true } : null;
    };
  }

  private installmentRule(): ValidatorFn {
    return (control: AbstractControl<string>) => {
      const value = control.value.trim();
      if (value === '') {
        return null;
      }
      const count = Number(value);
      return /^\d+$/.test(value) && count >= 1 && count <= this.maxInstallments() ? null : { range: true };
    };
  }

  private expiryRule(): ValidatorFn {
    return (control: AbstractControl<string>) => {
      if (control.value === '') {
        return null;
      }
      const at = localDateTimeToInstant(control.value);
      const opportunity = this.opportunity();
      if (!at) {
        return { expiry: 'format' };
      }
      const problem = opportunity ? expiryProblem(at, opportunity.dueDate, Date.now()) : null;
      return problem ? { expiry: problem } : null;
    };
  }

  private setServerError(field: OfferField, message: string): void {
    const control = this.form.controls[field];
    control.setErrors({ ...control.errors, server: message });
    control.markAsTouched();
  }

  private showFormError(message: string): void {
    this.formError.set(message);
    afterNextRender(() => this.errorSummary()?.nativeElement.focus(), { injector: this.injector });
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }

  private focusFirstInvalid(): void {
    const first = FIELD_IDS.find(([field]) => this.form.controls[field].invalid);
    if (first) {
      afterNextRender(() => this.document.getElementById(first[1])?.focus(), { injector: this.injector });
    }
  }

  private idFor(field: OfferField): string {
    return FIELD_IDS.find(([name]) => name === field)?.[1] ?? field;
  }
}
