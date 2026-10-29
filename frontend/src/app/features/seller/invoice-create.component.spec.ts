import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { InvoiceCreateComponent, normalizeAmount } from './invoice-create.component';
import { versioned } from './invoice-testing';
import { VersionedInvoice } from './invoice.models';

describe('InvoiceCreateComponent', () => {
  let fixture: ComponentFixture<InvoiceCreateComponent>;
  let el: HTMLElement;
  let create: jasmine.Spy;
  let navigate: jasmine.Spy;

  beforeEach(async () => {
    create = jasmine.createSpy('create');
    TestBed.configureTestingModule({
      imports: [InvoiceCreateComponent],
      providers: [provideRouter([]), { provide: InvoiceApiService, useValue: { create } }],
    });
    navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(InvoiceCreateComponent);
    el = fixture.nativeElement;
    await settle();
  });

  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function field(id: string): HTMLInputElement {
    return el.querySelector<HTMLInputElement>(`#${id}`)!;
  }

  function type(id: string, value: string): void {
    const input = field(id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  async function fill(values: Partial<Record<string, string>> = {}): Promise<void> {
    const all: Record<string, string> = {
      'inv-number': ' INV-2026/014 ',
      'inv-buyer': '  Kaveri Textiles Pvt Ltd ',
      'inv-gstin': ' 27aapfu0939f1zv ',
      'inv-amount': '1,25,000.50',
      'inv-issue-date': '2026-10-20',
      'inv-due-date': '2026-12-19',
      'inv-description': '   ',
      ...values,
    };
    for (const [id, value] of Object.entries(all)) {
      type(id, value ?? '');
    }
    await settle();
  }

  async function submit(): Promise<void> {
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await settle();
  }

  function errorText(id: string): string | undefined {
    return el.querySelector(`#${id}-error`)?.textContent?.trim();
  }

  async function failWith(failure: InvoiceFailure): Promise<void> {
    create.and.rejectWith(new InvoiceApiError(failure));
    await fill();
    await submit();
    await settle();
  }

  it('labels every field and marks the optional ones', () => {
    for (const id of ['inv-number', 'inv-buyer', 'inv-gstin', 'inv-amount', 'inv-issue-date', 'inv-due-date', 'inv-description']) {
      expect(el.querySelector(`label[for="${id}"]`)).withContext(id).not.toBeNull();
    }
    expect(el.querySelector('label[for="inv-gstin"]')?.textContent).toContain('(optional)');
    expect(el.querySelector('label[for="inv-description"]')?.textContent).toContain('(optional)');
    expect(field('inv-issue-date').type).toBe('date');
    expect(field('inv-amount').inputMode).toBe('decimal');
    expect(field('inv-number').getAttribute('aria-describedby')).toBe('inv-number-hint');
  });

  it('explains every missing required field, focuses the first and sends nothing', async () => {
    await submit();
    await settle();

    expect(create).not.toHaveBeenCalled();
    expect(errorText('inv-number')).toBe('Enter the invoice number.');
    expect(errorText('inv-buyer')).toBe('Enter the buyer name.');
    expect(errorText('inv-amount')).toBe('Enter the amount.');
    expect(errorText('inv-issue-date')).toBe('Enter the issue date.');
    expect(errorText('inv-due-date')).toBe('Enter the due date.');
    expect(errorText('inv-gstin')).toBeUndefined();
    expect(field('inv-number').getAttribute('aria-invalid')).toBe('true');
    expect(field('inv-number').getAttribute('aria-describedby')).toBe('inv-number-hint inv-number-error');
    expect(document.activeElement).toBe(field('inv-number'));
  });

  it('checks formats and dates the way invoice-service does', async () => {
    await fill({
      'inv-number': '-bad number',
      'inv-gstin': 'NOT-A-GSTIN',
      'inv-amount': '12.345',
      'inv-issue-date': '2026-10-20',
      'inv-due-date': '2026-10-20',
    });
    await submit();

    expect(create).not.toHaveBeenCalled();
    expect(errorText('inv-number')).toContain('letters, digits');
    expect(errorText('inv-gstin')).toContain('valid 15-character GSTIN');
    expect(errorText('inv-amount')).toContain('at most 2 decimal places');
    expect(errorText('inv-due-date')).toBe('The due date must be after the issue date.');

    type('inv-amount', '0.00');
    await settle();
    expect(errorText('inv-amount')).toBe('The amount must be at least ₹0.01.');

    // Moving the issue date re-checks the due date.
    type('inv-issue-date', '2026-10-01');
    await settle();
    expect(errorText('inv-due-date')).toBeUndefined();
  });

  it('sends a clean draft, without any seller, and opens the new invoice', async () => {
    create.and.resolveTo(versioned({ id: 'new-id' }));
    await fill({ 'inv-description': '  Cotton rolls  ' });
    await submit();

    expect(create).toHaveBeenCalledOnceWith({
      invoiceNumber: 'INV-2026/014',
      buyerName: 'Kaveri Textiles Pvt Ltd',
      buyerGstin: '27AAPFU0939F1ZV',
      description: 'Cotton rolls',
      amount: '125000.50',
      issueDate: '2026-10-20',
      dueDate: '2026-12-19',
    });
    expect(navigate).toHaveBeenCalledWith(['/seller/invoices', 'new-id'], { state: { created: true } });
  });

  it('sends empty optional fields as null', async () => {
    create.and.resolveTo(versioned());
    await fill({ 'inv-gstin': '  ', 'inv-description': '' });
    await submit();
    expect(create.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({ buyerGstin: null, description: null }));
  });

  it('holds a single request while saving and ignores repeated clicks', async () => {
    let resolve!: (value: VersionedInvoice) => void;
    create.and.returnValue(new Promise((r) => (resolve = r)));
    await fill();
    await submit();

    const button = el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(button.disabled).toBeTrue();
    expect(button.textContent?.trim()).toBe('Saving invoice…');
    expect(el.querySelector('form')?.getAttribute('aria-busy')).toBe('true');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
    expect(create).toHaveBeenCalledTimes(1);

    resolve(versioned());
    await settle();
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('shows invoice-service’s field errors next to the fields', async () => {
    await failWith({ kind: 'invalid', fieldErrors: { buyerGstin: 'must be a valid 15-character GSTIN' } });

    expect(errorText('inv-gstin')).toBe('Buyer GSTIN must be a valid 15-character GSTIN.');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Check the highlighted fields');
    expect(document.activeElement).toBe(field('inv-gstin'));
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows a business-rule refusal on the field it concerns', async () => {
    await failWith({ kind: 'rule', field: 'dueDate', message: 'Due date must be within 180 days of the issue date' });

    expect(errorText('inv-due-date')).toBe('Due date must be within 180 days of the issue date.');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('within 180 days');
  });

  it('reports a duplicate invoice number on that field', async () => {
    await failWith({ kind: 'duplicate-number' });

    expect(errorText('inv-number')).toBe('You already have an invoice with this number.');
    expect(document.activeElement).toBe(field('inv-number'));
  });

  it('keeps the entered details when the request fails and says what happened', async () => {
    await failWith({ kind: 'network' });

    const alert = el.querySelector<HTMLElement>('[role="alert"]')!;
    expect(alert.textContent).toContain('could not be reached');
    expect(document.activeElement).toBe(alert);
    expect(field('inv-number').value).toBe(' INV-2026/014 ');
    expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBeFalse();
  });

  it('warns that an unexpected failure may still have saved the invoice', async () => {
    await failWith({ kind: 'unexpected' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('may or may not have been saved');
  });

  it('accepts Indian digit grouping in amounts', () => {
    expect(normalizeAmount(' 1,25,000.50 ')).toBe('125000.50');
  });
});
