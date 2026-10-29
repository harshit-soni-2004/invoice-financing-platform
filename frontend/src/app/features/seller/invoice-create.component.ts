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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { failureMessage } from './invoice-format';
import { InvoiceField } from './invoice.models';
import { DESCRIPTION_MAX, FIELD_IDS, LABELS, draftFrom, fieldError, idFor, invoiceForm } from './invoice-validators';

export { normalizeAmount, normalizeGstin, DESCRIPTION_MAX } from './invoice-validators';

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

  protected readonly descriptionMax = DESCRIPTION_MAX;

  protected readonly form = invoiceForm(inject(NonNullableFormBuilder));

  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);

  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');

  constructor() {
    const { issueDate, dueDate } = this.form.controls;
    issueDate.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => dueDate.updateValueAndValidity({ emitEvent: false }));
  }

  protected error(field: InvoiceField): string | null {
    return fieldError(this.form, field, this.submitted());
  }

  protected describedBy(field: InvoiceField, hintId?: string): string | null {
    const ids = [hintId, this.error(field) ? `${idFor(field)}-error` : undefined].filter(Boolean);
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

    this.submitting.set(true);
    try {
      const { invoice } = await this.api.create(draftFrom(this.form));
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
}
