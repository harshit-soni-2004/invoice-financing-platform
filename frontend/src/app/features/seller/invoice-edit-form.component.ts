import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { failureMessage } from './invoice-format';
import { InvoiceDraft, InvoiceField, VersionedInvoice } from './invoice.models';
import {
  DESCRIPTION_MAX,
  FIELD_IDS,
  InvoiceFormGroup,
  LABELS,
  draftFrom,
  fieldError,
  idFor,
  invoiceForm,
} from './invoice-validators';

/** Failures that mean the draft changed or is no longer a draft: the page reloads it instead of keeping the form. */
const HAND_BACK: ReadonlySet<InvoiceFailure['kind']> = new Set([
  'stale',
  'concurrent',
  'not-editable',
  'invalid-transition',
  'not-found',
]);

function sameDraft(a: InvoiceDraft, b: InvoiceDraft): boolean {
  return (Object.keys(a) as InvoiceField[]).every((field) => a[field] === b[field]);
}

/** Edits a DRAFT invoice's details in place, with the ETag the page loaded. */
@Component({
  selector: 'ifp-invoice-edit-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-edit-form.component.html',
  styleUrl: './invoice-form.scss',
  styles: ['h2 { margin: 0; font-size: var(--text-lg); }'],
})
export class InvoiceEditFormComponent implements OnInit {
  private readonly api = inject(InvoiceApiService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly current = input.required<VersionedInvoice>();
  readonly saved = output<VersionedInvoice>();
  readonly discarded = output<void>();
  /** The draft changed elsewhere or can no longer be edited. */
  readonly conflict = output<InvoiceFailure>();

  protected readonly descriptionMax = DESCRIPTION_MAX;
  protected form!: InvoiceFormGroup;
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);

  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');

  ngOnInit(): void {
    this.form = invoiceForm(this.fb, this.current().invoice);
    const { issueDate, dueDate } = this.form.controls;
    issueDate.valueChanges.subscribe(() => dueDate.updateValueAndValidity({ emitEvent: false }));
    afterNextRender(() => this.document.getElementById('inv-number')?.focus(), { injector: this.injector });
  }

  protected error(field: InvoiceField): string | null {
    return fieldError(this.form, field, this.submitted());
  }

  protected describedBy(field: InvoiceField, hintId?: string): string | null {
    const ids = [hintId, this.error(field) ? `${idFor(field)}-error` : undefined].filter(Boolean);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected discard(): void {
    if (!this.submitting()) {
      this.discarded.emit();
    }
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
    const { invoice, etag } = this.current();
    const draft = draftFrom(this.form);
    const unchanged = draftFrom(invoiceForm(this.fb, invoice));
    if (sameDraft(draft, unchanged)) {
      this.discarded.emit();
      return;
    }

    this.submitting.set(true);
    try {
      this.saved.emit(await this.api.update(invoice.id, draft, etag));
    } catch (error) {
      const failure: InvoiceFailure = error instanceof InvoiceApiError ? error.failure : { kind: 'unexpected' };
      if (HAND_BACK.has(failure.kind)) {
        this.conflict.emit(failure);
        return;
      }
      this.showFailure(failure);
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
        this.showFormError('The changes were not accepted. Check the details and try again.');
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
        this.showFormError('Something went wrong, and the changes may or may not have been saved. Reload the invoice to check.');
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
}
