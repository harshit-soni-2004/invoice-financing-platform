import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SellerFinancingApiService } from './financing-api.service';
import { agreementFixture, offerFixture, viewFixture } from './financing-testing';
import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { InvoiceDetailComponent } from './invoice-detail.component';
import { versioned } from './invoice-testing';
import { VersionedInvoice } from './invoice.models';

const ID = 'a0000000-0000-4000-8000-000000000001';

describe('InvoiceDetailComponent', () => {
  let fixture: ComponentFixture<InvoiceDetailComponent>;
  let el: HTMLElement;
  let get: jasmine.Spy;
  let transition: jasmine.Spy;
  let update: jasmine.Spy;
  let cancel: jasmine.Spy;
  let financing: jasmine.SpyObj<SellerFinancingApiService>;

  async function open(first: VersionedInvoice | InvoiceFailure): Promise<void> {
    get = jasmine.createSpy('get');
    transition = jasmine.createSpy('transition');
    update = jasmine.createSpy('update');
    cancel = jasmine.createSpy('cancel');
    financing = jasmine.createSpyObj<SellerFinancingApiService>('SellerFinancingApiService', [
      'offersForInvoice',
      'acceptOffer',
      'rejectOffer',
      'agreementForInvoice',
      'agreementView',
      'recordRepayment',
    ]);
    financing.offersForInvoice.and.resolveTo({ offers: [], truncated: false });
    financing.agreementForInvoice.and.resolveTo(null);
    if ('invoice' in first) {
      get.and.resolveTo(first);
    } else {
      get.and.rejectWith(new InvoiceApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [InvoiceDetailComponent],
      providers: [
        provideRouter([]),
        { provide: InvoiceApiService, useValue: { get, transition, update, cancel } },
        { provide: SellerFinancingApiService, useValue: financing },
      ],
    });
    fixture = TestBed.createComponent(InvoiceDetailComponent);
    fixture.componentRef.setInput('id', ID);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function button(label: RegExp): HTMLButtonElement {
    return Array.from(el.querySelectorAll('button')).find((b) => label.test(b.textContent ?? ''))!;
  }

  const submitButton = () => button(/^\s*(Submit for verification|Submitting…)\s*$/);
  const listButton = () => button(/Make available to financiers|Opening for financing/);
  const badge = () => el.querySelector('.page-header .badge')?.textContent?.trim();
  const notice = () => el.querySelector<HTMLElement>('.notice');

  it('shows the invoice and only enables actions its status allows', async () => {
    await open(versioned());

    expect(get).toHaveBeenCalledOnceWith(ID);
    expect(el.querySelector('h1')?.textContent).toContain('INV-2026/014');
    expect(badge()).toBe('Draft');
    const details = el.querySelector('dl')!.textContent!;
    expect(details).toContain('Kaveri Textiles Pvt Ltd');
    expect(details).toContain('27AAPFU0939F1ZV');
    expect(details).toContain('₹1,25,000.50');
    expect(details).toContain('20 Oct 2026');
    expect(submitButton().disabled).toBeFalse();
    expect(submitButton().getAttribute('aria-describedby')).toBe('action-hint-submit');
    expect(el.querySelector('#action-hint-submit')?.textContent).toContain('For drafts');
    expect(listButton().disabled).toBeTrue();
  });

  for (const [status, submit, list] of [
    ['SUBMITTED', true, true],
    ['VERIFIED', true, false],
    ['FINANCING_AVAILABLE', true, true],
    ['REJECTED', true, true],
  ] as const) {
    it(`disables the actions that ${status} does not allow`, async () => {
      await open(versioned({ status, statusReason: status === 'REJECTED' ? 'Buyer denies the debt' : null }));
      expect(submitButton().disabled).toBe(submit);
      expect(listButton().disabled).toBe(list);
      if (status === 'REJECTED') {
        expect(el.textContent).toContain('Buyer denies the debt');
      }
    });
  }

  it('submits a draft with its ETag, shows the confirmed state and ignores repeated clicks', async () => {
    await open(versioned({ version: 2 }));
    let resolve!: (value: VersionedInvoice) => void;
    transition.and.returnValue(new Promise((r) => (resolve = r)));

    submitButton().click();
    await settle();
    expect(submitButton().textContent?.trim()).toBe('Submitting…');
    expect(submitButton().disabled).toBeTrue();
    submitButton().click();
    await settle();
    expect(transition).toHaveBeenCalledOnceWith('submit', ID, '"2"');
    // Nothing changes until invoice-service answers.
    expect(badge()).toBe('Draft');

    resolve(versioned({ status: 'SUBMITTED', version: 3, submittedAt: '2026-10-29T03:00:00Z' }));
    await settle();

    expect(badge()).toBe('Submitted');
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Invoice submitted for verification.');
    expect(document.activeElement).toBe(notice());
    expect(submitButton().disabled).toBeTrue();
    expect(el.querySelector('.milestones')?.textContent).toContain('Submitted');
  });

  it('opens a verified invoice for financing with the current ETag', async () => {
    await open(versioned({ status: 'VERIFIED', version: 5 }));
    transition.and.resolveTo(versioned({ status: 'FINANCING_AVAILABLE', version: 6 }));

    listButton().click();
    await settle();

    expect(transition).toHaveBeenCalledOnceWith('list-for-financing', ID, '"5"');
    expect(badge()).toBe('Open for financing');
  });

  it('shows the latest version instead of applying an action to stale data', async () => {
    await open(versioned({ version: 2 }));
    transition.and.rejectWith(new InvoiceApiError({ kind: 'stale' }));
    get.and.resolveTo(versioned({ status: 'CANCELLED', version: 3, statusReason: 'Order withdrawn' }));

    submitButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(2);
    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('changed since you opened it');
    expect(badge()).toBe('Cancelled');
    expect(submitButton().disabled).toBeTrue();
  });

  it('explains a refused transition with the status invoice-service reports, then reloads', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'invalid-transition', currentStatus: 'SUBMITTED' }));
    get.and.resolveTo(versioned({ status: 'SUBMITTED', version: 1 }));

    submitButton().click();
    await settle();

    expect(notice()?.textContent).toContain('This invoice is now submitted, so that action is no longer possible.');
    expect(badge()).toBe('Submitted');
  });

  it('keeps the invoice as it was when an action is refused for other reasons', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'forbidden' }));

    submitButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(1);
    expect(notice()?.classList).toContain('alert-danger');
    expect(notice()?.textContent).toContain('You do not have permission to do this.');
    expect(badge()).toBe('Draft');
    expect(submitButton().disabled).toBeFalse();
  });

  it('shows invoice-service’s reason when a business rule blocks submission', async () => {
    await open(versioned());
    transition.and.rejectWith(
      new InvoiceApiError({ kind: 'rule', field: 'dueDate', message: 'Due date must be at least 7 days away' }),
    );

    submitButton().click();
    await settle();

    expect(notice()?.textContent).toContain('Due date must be at least 7 days away');
    expect(badge()).toBe('Draft');
  });

  it('says when the session has ended during an action', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'unauthenticated' }));
    submitButton().click();
    await settle();
    expect(notice()?.textContent).toContain('Your session has ended');
  });

  it('reports an invoice that does not exist or belongs to someone else', async () => {
    await open({ kind: 'not-found' });

    expect(el.querySelector('h1')?.textContent).toBe('Invoice not found');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('belongs to another account');
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Try again'))).toBeFalse();
  });

  it('offers a retry when the invoice cannot be loaded', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('h1')?.textContent).toBe('The invoice could not be loaded');

    get.and.resolveTo(versioned());
    button(/Try again/).click();
    await settle();
    expect(badge()).toBe('Draft');
  });

  describe('draft edit', () => {
    const editButton = () => button(/^\s*Edit details\s*$/);
    const field = (id: string) => el.querySelector<HTMLInputElement>(`#${id}`)!;

    function type(id: string, value: string): void {
      field(id).value = value;
      field(id).dispatchEvent(new Event('input'));
    }

    it('is offered only for drafts', async () => {
      await open(versioned({ status: 'SUBMITTED' }));
      expect(editButton().disabled).toBeTrue();
    });

    it('saves changed details with the current ETag and shows the server’s version', async () => {
      await open(versioned({ version: 4 }));
      editButton().click();
      await settle();
      expect(field('inv-number').value).toBe('INV-2026/014');
      expect(field('inv-amount').value).toBe('125000.50');
      expect(submitButton().disabled).toBeTrue();

      let resolve!: (value: VersionedInvoice) => void;
      update.and.returnValue(new Promise((r) => (resolve = r)));
      type('inv-number', 'INV-2026/099');
      button(/^\s*Save changes\s*$/).click();
      await settle();
      button(/Saving changes…/).click();
      await settle();

      expect(update).toHaveBeenCalledTimes(1);
      const [id, draft, etag] = update.calls.mostRecent().args;
      expect(id).toBe(ID);
      expect(etag).toBe('"4"');
      expect(draft).toEqual(jasmine.objectContaining({ invoiceNumber: 'INV-2026/099', amount: '125000.50', buyerGstin: '27AAPFU0939F1ZV' }));

      resolve(versioned({ invoiceNumber: 'INV-2026/099', version: 5 }));
      await settle();
      expect(el.querySelector('h1')?.textContent).toContain('INV-2026/099');
      expect(notice()?.textContent).toContain('Changes saved.');
      expect(field('inv-number')).toBeNull();
    });

    it('does not send unchanged details', async () => {
      await open(versioned());
      editButton().click();
      await settle();
      button(/^\s*Save changes\s*$/).click();
      await settle();
      expect(update).not.toHaveBeenCalled();
      expect(field('inv-number')).toBeNull();
    });

    it('checks the fields in the browser first', async () => {
      await open(versioned());
      editButton().click();
      await settle();
      type('inv-amount', '12.345');
      button(/^\s*Save changes\s*$/).click();
      await settle();
      expect(update).not.toHaveBeenCalled();
      expect(el.querySelector('#inv-amount-error')?.textContent).toContain('at most 2 decimal places');
    });

    it('reloads instead of overwriting when the draft changed elsewhere (412)', async () => {
      await open(versioned({ version: 1 }));
      editButton().click();
      await settle();
      update.and.rejectWith(new InvoiceApiError({ kind: 'stale' }));
      get.and.resolveTo(versioned({ status: 'SUBMITTED', version: 2 }));
      type('inv-number', 'INV-2026/100');
      button(/^\s*Save changes\s*$/).click();
      await settle();

      expect(notice()?.textContent).toContain('changed since you opened it');
      expect(badge()).toBe('Submitted');
      expect(field('inv-number')).toBeNull();
      expect(editButton().disabled).toBeTrue();
    });

    it('keeps the form and marks the field for a duplicate number', async () => {
      await open(versioned());
      editButton().click();
      await settle();
      update.and.rejectWith(new InvoiceApiError({ kind: 'duplicate-number' }));
      type('inv-number', 'INV-2026/001');
      button(/^\s*Save changes\s*$/).click();
      await settle();
      expect(field('inv-number').getAttribute('aria-invalid')).toBe('true');
      expect(el.querySelector('#inv-number-error')?.textContent).toContain('already have an invoice with this number');
    });
  });

  describe('cancellation', () => {
    const cancelButton = () => button(/^\s*Cancel invoice\s*$/);
    const dialog = () => el.querySelector<HTMLDialogElement>('dialog[aria-labelledby="cancel-title"]')!;
    const confirmButton = () => dialog().querySelector<HTMLButtonElement>('.btn-danger')!;

    afterEach(() => {
      if (dialog()?.open) {
        dialog().close();
      }
    });

    for (const [status, allowed] of [
      ['DRAFT', true],
      ['SUBMITTED', true],
      ['BUYER_CONFIRMED', false],
      ['VERIFIED', true],
      ['FINANCING_AVAILABLE', false],
      ['FINANCED', false],
      ['CANCELLED', false],
    ] as const) {
      it(`is ${allowed ? 'offered' : 'not offered'} for ${status}`, async () => {
        await open(versioned({ status }));
        expect(cancelButton().disabled).toBe(!allowed);
      });
    }

    it('confirms first, cancels once with If-Match and the reason, and shows the result', async () => {
      await open(versioned({ status: 'VERIFIED', version: 6 }));
      let resolve!: (value: VersionedInvoice) => void;
      cancel.and.returnValue(new Promise((r) => (resolve = r)));

      cancelButton().click();
      await settle();
      expect(dialog().open).toBeTrue();
      const reason = el.querySelector<HTMLTextAreaElement>('#cancel-reason')!;
      reason.value = ' Buyer paid directly ';
      reason.dispatchEvent(new Event('input'));
      confirmButton().click();
      await settle();
      expect(confirmButton().textContent?.trim()).toBe('Cancelling…');
      confirmButton().click();
      await settle();
      expect(cancel).toHaveBeenCalledOnceWith(ID, '"6"', 'Buyer paid directly');
      expect(badge()).toBe('Verified');

      resolve(versioned({ status: 'CANCELLED', version: 7, statusReason: 'Buyer paid directly' }));
      await settle();
      expect(dialog().open).toBeFalse();
      expect(badge()).toBe('Cancelled');
      expect(notice()?.textContent).toContain('Invoice cancelled.');
      expect(cancelButton().disabled).toBeTrue();
    });

    it('closes and shows the latest state when the invoice moved on (conflict)', async () => {
      await open(versioned({ status: 'SUBMITTED' }));
      cancel.and.rejectWith(new InvoiceApiError({ kind: 'invalid-transition', currentStatus: 'BUYER_CONFIRMED' }));
      get.and.resolveTo(versioned({ status: 'BUYER_CONFIRMED', version: 1 }));
      cancelButton().click();
      await settle();
      confirmButton().click();
      await settle();

      expect(dialog().open).toBeFalse();
      expect(notice()?.textContent).toContain('now buyer confirmed');
      expect(badge()).toBe('Buyer confirmed');
    });

    it('keeps the dialog open to retry after a network failure', async () => {
      await open(versioned());
      cancel.and.rejectWith(new InvoiceApiError({ kind: 'network' }));
      cancelButton().click();
      await settle();
      confirmButton().click();
      await settle();
      expect(dialog().open).toBeTrue();
      expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('could not be reached');
      expect(badge()).toBe('Draft');
    });
  });

  describe('financing sections', () => {
    it('shows offers while open for financing', async () => {
      await open(versioned({ status: 'FINANCING_AVAILABLE' }));
      expect(financing.offersForInvoice).toHaveBeenCalledWith(ID);
      expect(el.querySelector('ifp-invoice-offers')).not.toBeNull();
      expect(el.querySelector('ifp-agreement-panel section')).toBeNull();
    });

    it('shows the agreement and no offers once financed', async () => {
      await open(versioned({ status: 'FINANCED' }));
      financing.agreementForInvoice.and.resolveTo(agreementFixture());
      financing.agreementView.and.resolveTo(viewFixture());
      button(/Refresh agreement/).click();
      await settle();
      expect(el.querySelector('ifp-invoice-offers')).toBeNull();
      expect(el.querySelector('ifp-agreement-panel')?.textContent).toContain('Repayment schedule');
    });

    it('shows neither before the invoice is open for financing', async () => {
      await open(versioned({ status: 'VERIFIED' }));
      expect(el.querySelector('ifp-invoice-offers')).toBeNull();
      expect(el.querySelector('ifp-agreement-panel')).toBeNull();
      expect(financing.offersForInvoice).not.toHaveBeenCalled();
    });

    it('reloads the invoice and agreement after an offer decision', async () => {
      await open(versioned({ status: 'FINANCING_AVAILABLE' }));
      financing.offersForInvoice.and.resolveTo({ offers: [offerFixture()], truncated: false });
      el.querySelector<HTMLButtonElement>('ifp-invoice-offers .section-head button')!.click();
      await settle();
      financing.acceptOffer.and.resolveTo(agreementFixture({ status: 'PENDING_APPROVAL' }));
      financing.agreementForInvoice.and.resolveTo(agreementFixture({ status: 'PENDING_APPROVAL' }));
      financing.agreementView.and.resolveTo({
        agreement: agreementFixture({ status: 'PENDING_APPROVAL' }),
        disbursement: null,
        schedule: [],
        repayments: [],
      });
      button(/^\s*Accept\s*$/).click();
      await settle();
      button(/^\s*Accept offer\s*$/).click();
      await settle();

      expect(get).toHaveBeenCalledTimes(2);
      expect(financing.agreementForInvoice).toHaveBeenCalledTimes(2);
      expect(el.querySelector('ifp-agreement-panel')?.textContent).toContain('Awaiting approval');
      el.querySelectorAll('dialog').forEach((d) => d.open && d.close());
    });
  });
});
