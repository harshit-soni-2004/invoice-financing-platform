import { AbstractControl, NonNullableFormBuilder, ValidationErrors } from '@angular/forms';

import { Invoice, InvoiceDraft, InvoiceField } from './invoice.models';

/** DOM order, so the first invalid field gets focus. */
export const FIELD_IDS: readonly [InvoiceField, string][] = [
  ['invoiceNumber', 'inv-number'],
  ['buyerName', 'inv-buyer'],
  ['buyerGstin', 'inv-gstin'],
  ['amount', 'inv-amount'],
  ['issueDate', 'inv-issue-date'],
  ['dueDate', 'inv-due-date'],
  ['description', 'inv-description'],
];

export const LABELS: Readonly<Record<InvoiceField, string>> = {
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
export const AMOUNT = /^\d{1,17}(\.\d{1,2})?$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export const DESCRIPTION_MAX = 500;

/** People type "1,25,000.50"; the API wants "125000.50". */
export function normalizeAmount(value: string): string {
  return value.replace(/[\s,]/g, '');
}

export function normalizeGstin(value: string): string {
  return value.trim().toUpperCase();
}

export function required(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

export function maxTrimmed(max: number) {
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

/** The invoice form, empty or prefilled from an existing draft. */
export function invoiceForm(fb: NonNullableFormBuilder, invoice?: Invoice) {
  return fb.group({
    invoiceNumber: fb.control(invoice?.invoiceNumber ?? '', [required, maxTrimmed(50), invoiceNumber]),
    buyerName: fb.control(invoice?.buyerName ?? '', [required, maxTrimmed(200)]),
    buyerGstin: fb.control(invoice?.buyerGstin ?? '', gstin),
    amount: fb.control(invoice ? invoice.amount.toFixed(2) : '', [required, amount]),
    issueDate: fb.control(invoice?.issueDate ?? '', [required, isoDate]),
    dueDate: fb.control(invoice?.dueDate ?? '', [required, isoDate, dueAfterIssue]),
    description: fb.control(invoice?.description ?? '', maxTrimmed(DESCRIPTION_MAX)),
  });
}

export type InvoiceFormGroup = ReturnType<typeof invoiceForm>;

/** The request body for the form's current values. */
export function draftFrom(form: InvoiceFormGroup): InvoiceDraft {
  const value = form.getRawValue();
  const gstinValue = normalizeGstin(value.buyerGstin);
  const description = value.description.trim();
  return {
    invoiceNumber: value.invoiceNumber.trim(),
    buyerName: value.buyerName.trim(),
    buyerGstin: gstinValue === '' ? null : gstinValue,
    description: description === '' ? null : description,
    amount: normalizeAmount(value.amount),
    issueDate: value.issueDate,
    dueDate: value.dueDate,
  };
}

/** The message for a field's first error, or null when it has none worth showing yet. */
export function fieldError(form: InvoiceFormGroup, field: InvoiceField, submitted: boolean): string | null {
  const control = form.controls[field];
  if (!control.errors || !(control.touched || submitted)) {
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

export function idFor(field: InvoiceField): string {
  return FIELD_IDS.find(([name]) => name === field)?.[1] ?? field;
}
