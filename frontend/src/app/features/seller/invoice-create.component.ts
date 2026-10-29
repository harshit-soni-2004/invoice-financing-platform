import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { failureMessage } from './invoice-format';
import { InvoiceDraft, InvoiceField } from './invoice.models';

/** DOM order, so the first invalid field gets focus. */
const FIELD_IDS: readonly [InvoiceField, string][] = [
  ['invoiceNumber', 'inv-number'],
  ['buyerName', 'inv-buyer'],
  ['buyerGstin', 'inv-gstin'],
  ['amount', 'inv-amount'],
  ['issueDate', 'inv-issue-date'],
  ['dueDate', 'inv-due-date'],
  ['description', 'inv-description'],
];

const LABELS: Readonly<Record<InvoiceField, string>> = {
  invoiceNumber: 'Invoice number',
  buyerName: 'Buyer name',
  buyerGstin: 'Buyer GSTIN',
  description: 'Description',
  amount: 'Amount',
  issueDate: 'Issue date',
  dueDate: 'Due date',
};

/** The same patterns invoice-service's InvoiceRequest enforces. */
const INVOICE_NUMBER = /^[A-Za-z0-9][A-Za-z0-9/-]*$/;
const GSTIN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;
/** At most 17 integer digits and 2 decimals, like `@Digits(integer = 17, fraction = 2)`. */
const AMOUNT = /^\d{1,17}(\.\d{1,2})?$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export const DESCRIPTION_MAX = 500;

/** People type "1,25,000.50"; the API wants "125000.50". */
export function normalizeAmount(value: string): string {
  return value.replace(/[\s,]/g, '');
}

export function normalizeGstin(value: string): string {
  return value.trim().toUpperCase();
}

function required(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

function maxTrimmed(max: number) {
  return (control: AbstractControl<string>): ValidationErrors | null =>
    control.value.trim().length > max ? { maxlength: max } : null;
}

function invoiceNumber(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value.trim();
  return value === '' || INVOICE_NUMBER.test(value) ? null : { pattern: true };
}

function gstin(control: AbstractControl<string>): ValidationErrors | null {
  const value = normalizeGstin(control.value);
  return value === '' || GSTIN.test(value) ? null : { pattern: true };
}

function amount(control: AbstractControl<string>): ValidationErrors | null {
  const value = normalizeAmount(control.value);
  if (value === '') {
    return null;
  }
  if (!AMOUNT.test(value)) {
    return { format: true };
  }
  return Number(value) < 0.01 ? { min: true } : null;
}

function isoDate(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value;
  if (value === '') {
    return null;
  }
  return ISO_DATE.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) ? null : { date: true };
}

function dueAfterIssue(control: AbstractControl<string>): ValidationErrors | null {
  const issue = control.parent?.get('issueDate');
  if (!control.value || !issue?.value || issue.invalid || isoDate(control)) {
    return null;
  }
  return control.value > issue.value ? null : { notAfterIssue: true };
}

@Component({
  selector: 'ifp-invoice-create',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-create.component.html',
  styleUrl: './invoice-form.scss',
})
export class InvoiceCreateComponent {
  private readonly api = inject(InvoiceApiService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly descriptionMax = DESCRIPTION_MAX;

  protected readonly form = this.fb.group({
    invoiceNumber: this.fb.control('', [required, maxTrimmed(50), invoiceNumber]),
    buyerName: this.fb.control('', [required, maxTrimmed(200)]),
    buyerGstin: this.fb.control('', gstin),
    amount: this.fb.control('', [required, amount]),
    issueDate: this.fb.control('', [required, isoDate]),
    dueDate: this.fb.control('', [required, isoDate, dueAfterIssue]),
    description: this.fb.control('', maxTrimmed(DESCRIPTION_MAX)),
  });

  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);

  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');

  constructor() {
    const { issueDate, dueDate } = this.form.controls;
    issueDate.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => dueDate.updateValueAndValidity({ emitEvent: false }));
  }

  protected error(field: InvoiceField): string | null {
    const control = this.form.controls[field];
    if (!control.errors || !(control.touched || this.submitted())) {
      return null;
    }
    const errors = control.errors;
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    if (errors['required']) {
      return `Enter the ${LABELS[field].toLowerCase()}.`;
    }
    if (errors['maxlength']) {
      return `${LABELS[field]} must be at most ${errors['maxlength']} characters.`;
    }
    switch (field) {
      case 'invoiceNumber':
        return 'Use only letters, digits, "/" and "-", starting with a letter or digit.';
      case 'buyerGstin':
        return 'Enter a valid 15-character GSTIN, like 27AAPFU0939F1ZV, or leave it empty.';
      case 'amount':
        return errors['min']
          ? 'The amount must be at least ₹0.01.'
          : 'Enter an amount in rupees with at most 2 decimal places, like 125000.50.';
      case 'dueDate':
        return errors['notAfterIssue'] ? 'The due date must be after the issue date.' : 'Enter a valid date.';
      default:
        return 'Enter a valid date.';
    }
  }

  protected describedBy(field: InvoiceField, hintId?: string): string | null {
    const ids = [hintId, this.error(field) ? `${this.idFor(field)}-error` : undefined].filter(Boolean);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.submitted.set(true);
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalid();
      return;
    }

    const value = this.form.getRawValue();
    const gstinValue = normalizeGstin(value.buyerGstin);
    const description = value.description.trim();
    const draft: InvoiceDraft = {
      invoiceNumber: value.invoiceNumber.trim(),
      buyerName: value.buyerName.trim(),
      buyerGstin: gstinValue === '' ? null : gstinValue,
      description: description === '' ? null : description,
      amount: normalizeAmount(value.amount),
      issueDate: value.issueDate,
      dueDate: value.dueDate,
    };

    this.submitting.set(true);
    try {
      const { invoice } = await this.api.create(draft);
      await this.router.navigate(['/seller/invoices', invoice.id], { state: { created: true } });
    } catch (error) {
      this.showFailure(error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' });
    } finally {
      this.submitting.set(false);
    }
  }

  private showFailure(failure: InvoiceFailure): void {
    switch (failure.kind) {
      case 'invalid': {
        const fields = Object.entries(failure.fieldErrors) as [InvoiceField, string][];
        for (const [field, message] of fields) {
          this.setServerError(field, `${LABELS[field]} ${message}.`);
        }
        if (fields.length > 0) {
          this.formError.set(failureMessage(failure));
          this.focusFirstInvalid();
          return;
        }
        this.showFormError('The invoice was not accepted. Check the details and try again.');
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
        this.showFormError(message);
        return;
      }
      case 'duplicate-number':
        this.setServerError('invoiceNumber', 'You already have an invoice with this number.');
        this.formError.set(failureMessage(failure));
        this.focusFirstInvalid();
        return;
      case 'unexpected':
        this.showFormError(
          'Something went wrong, and the invoice may or may not have been saved. Check your invoice list before trying again.',
        );
        return;
      default:
        this.showFormError(failureMessage(failure));
    }
  }

  private setServerError(field: InvoiceField, message: string): void {
    const control = this.form.controls[field];
    control.setErrors({ ...control.errors, server: message });
    control.markAsTouched();
  }

  private showFormError(message: string): void {
    this.formError.set(message);
    afterNextRender(() => this.errorSummary()?.nativeElement.focus(), { injector: this.injector });
  }

  private focusFirstInvalid(): void {
    const first = FIELD_IDS.find(([field]) => this.form.controls[field].invalid);
    if (first) {
      afterNextRender(() => this.document.getElementById(first[1])?.focus(), { injector: this.injector });
    }
  }

  private idFor(field: InvoiceField): string {
    return FIELD_IDS.find(([name]) => name === field)?.[1] ?? field;
  }
}
