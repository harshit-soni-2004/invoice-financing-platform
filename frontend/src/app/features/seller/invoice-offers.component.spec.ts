import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FinancingApiError, FinancingFailure, SellerFinancingApiService } from './financing-api.service';
import { INVOICE_ID, agreementFixture, inDays, offerFixture } from './financing-testing';
import { Offer } from './financing.models';
import { InvoiceOffersComponent } from './invoice-offers.component';

describe('InvoiceOffersComponent', () => {
  let fixture: ComponentFixture<InvoiceOffersComponent>;
  let el: HTMLElement;
  let offersForInvoice: jasmine.Spy;
  let acceptOffer: jasmine.Spy;
  let rejectOffer: jasmine.Spy;
  let changed: jasmine.Spy;

  async function open(first: Offer[] | FinancingFailure): Promise<void> {
    offersForInvoice = jasmine.createSpy('offersForInvoice');
    acceptOffer = jasmine.createSpy('acceptOffer');
    rejectOffer = jasmine.createSpy('rejectOffer');
    if (Array.isArray(first)) {
      offersForInvoice.and.resolveTo({ offers: first, truncated: false });
    } else {
      offersForInvoice.and.rejectWith(new FinancingApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [InvoiceOffersComponent],
      providers: [{ provide: SellerFinancingApiService, useValue: { offersForInvoice, acceptOffer, rejectOffer } }],
    });
    fixture = TestBed.createComponent(InvoiceOffersComponent);
    fixture.componentRef.setInput('invoiceId', INVOICE_ID);
    changed = jasmine.createSpy('changed');
    fixture.componentInstance.changed.subscribe(changed);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  const buttons = (label: RegExp) => Array.from(el.querySelectorAll('button')).filter((b) => label.test(b.textContent ?? ''));
  const button = (label: RegExp) => buttons(label)[0];
  const dialog = (id: string) => el.querySelector<HTMLDialogElement>(`dialog[aria-labelledby="${id}"]`)!;
  const rows = () => Array.from(el.querySelectorAll('tbody tr'));
  const notice = () => el.querySelector<HTMLElement>('.notice');

  afterEach(() => {
    el.querySelectorAll('dialog').forEach((d) => d.open && d.close());
  });

  it('lists offers with the fields financing-service returns and only lets open, unexpired offers be decided', async () => {
    await open([
      offerFixture(),
      offerFixture({ id: 'expired', expiresAt: inDays(-1) }),
      offerFixture({ id: 'withdrawn', status: 'WITHDRAWN', statusReason: 'Changed our mind' }),
    ]);

    expect(offersForInvoice).toHaveBeenCalledOnceWith(INVOICE_ID);
    expect(rows().length).toBe(3);
    const first = rows()[0].textContent!;
    expect(first).toContain('₹1,00,000.00');
    expect(first).toContain('12%');
    expect(first).toContain('₹500.00');
    expect(first).toContain('Open');

    const accepts = buttons(/^\s*Accept\s*$/);
    expect(accepts.length).toBe(2);
    expect(accepts[0].disabled).toBeFalse();
    expect(accepts[0].getAttribute('aria-label')).toBe('Accept offer of ₹1,00,000.00');
    expect(accepts[1].disabled).toBeTrue();
    expect(rows()[1].textContent).toContain('Validity has ended');
    expect(rows()[2].textContent).toContain('Withdrawn');
    expect(rows()[2].textContent).toContain('Changed our mind');
    expect(rows()[2].querySelector('button')).toBeNull();
  });

  it('says when there are no offers yet', async () => {
    await open([]);
    expect(el.textContent).toContain('No offers yet');
    expect(el.querySelector('table')).toBeNull();
  });

  it('offers a retry when the offers cannot be loaded', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('temporarily unavailable');
    offersForInvoice.and.resolveTo({ offers: [offerFixture()], truncated: false });
    button(/Try again/).click();
    await settle();
    expect(rows().length).toBe(1);
  });

  it('asks for confirmation, accepts once despite repeated clicks, and only then shows the new state', async () => {
    await open([offerFixture(), offerFixture({ id: 'other', advanceAmount: 90000 })]);
    let resolve!: (value: unknown) => void;
    acceptOffer.and.returnValue(new Promise((r) => (resolve = r)));

    button(/^\s*Accept\s*$/).click();
    await settle();
    const confirm = dialog('accept-title');
    expect(confirm.open).toBeTrue();
    expect(confirm.textContent).toContain('closes every other open offer');
    expect(acceptOffer).not.toHaveBeenCalled();

    button(/^\s*Accept offer\s*$/).click();
    await settle();
    expect(button(/Accepting…/).disabled).toBeTrue();
    button(/Accepting…/).click();
    await settle();
    expect(acceptOffer).toHaveBeenCalledOnceWith('f0000000-0000-4000-8000-000000000001');
    // Nothing changes until financing-service answers.
    expect(rows()[0].textContent).toContain('Open');
    expect(changed).not.toHaveBeenCalled();

    offersForInvoice.and.resolveTo({
      offers: [offerFixture({ status: 'ACCEPTED' }), offerFixture({ id: 'other', status: 'CLOSED', statusReason: 'Another offer for this invoice was accepted' })],
      truncated: false,
    });
    resolve(agreementFixture({ status: 'PENDING_APPROVAL' }));
    await settle();

    expect(confirm.open).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Offer accepted');
    expect(rows()[0].textContent).toContain('Accepted');
    expect(rows()[1].textContent).toContain('Closed');
    expect(buttons(/^\s*Accept\s*$/).length).toBe(0);
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('does not accept when the seller backs out of the confirmation', async () => {
    await open([offerFixture()]);
    button(/^\s*Accept\s*$/).click();
    await settle();
    button(/Keep reviewing/).click();
    await settle();
    expect(dialog('accept-title').open).toBeFalse();
    expect(acceptOffer).not.toHaveBeenCalled();
  });

  it('closes the dialog and shows the real state when the offer was already decided (conflict)', async () => {
    await open([offerFixture()]);
    acceptOffer.and.rejectWith(new FinancingApiError({ kind: 'conflict', code: 'INVALID_STATUS_TRANSITION', currentStatus: 'WITHDRAWN' }));
    offersForInvoice.and.resolveTo({ offers: [offerFixture({ status: 'WITHDRAWN' })], truncated: false });

    button(/^\s*Accept\s*$/).click();
    await settle();
    button(/^\s*Accept offer\s*$/).click();
    await settle();

    expect(dialog('accept-title').open).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('This is now withdrawn');
    expect(rows()[0].textContent).toContain('Withdrawn');
    expect(offersForInvoice).toHaveBeenCalledTimes(2);
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('keeps the dialog open after a lost response, and a retry that finds the offer accepted shows that truthfully', async () => {
    await open([offerFixture()]);
    acceptOffer.and.rejectWith(new FinancingApiError({ kind: 'network' }));

    button(/^\s*Accept\s*$/).click();
    await settle();
    button(/^\s*Accept offer\s*$/).click();
    await settle();

    const confirm = dialog('accept-title');
    expect(confirm.open).toBeTrue();
    expect(confirm.querySelector('[role="alert"]')?.textContent).toContain('trying again shows its current status');
    expect(rows()[0].textContent).toContain('Open');

    acceptOffer.and.rejectWith(new FinancingApiError({ kind: 'conflict', code: 'INVALID_STATUS_TRANSITION', currentStatus: 'ACCEPTED' }));
    offersForInvoice.and.resolveTo({ offers: [offerFixture({ status: 'ACCEPTED' })], truncated: false });
    button(/^\s*Accept offer\s*$/).click();
    await settle();

    expect(acceptOffer).toHaveBeenCalledTimes(2);
    expect(confirm.open).toBeFalse();
    expect(notice()?.textContent).toContain('This is now accepted');
    expect(rows()[0].textContent).toContain('Accepted');
  });

  it('explains an expired offer refused by financing-service', async () => {
    await open([offerFixture()]);
    acceptOffer.and.rejectWith(new FinancingApiError({ kind: 'conflict', code: 'OFFER_EXPIRED', currentStatus: null }));
    button(/^\s*Accept\s*$/).click();
    await settle();
    button(/^\s*Accept offer\s*$/).click();
    await settle();
    expect(notice()?.textContent).toContain('This offer has expired');
  });

  it('shows financing-service’s rule when the invoice can no longer be financed', async () => {
    await open([offerFixture()]);
    acceptOffer.and.rejectWith(new FinancingApiError({ kind: 'rule', code: 'INVOICE_NOT_ELIGIBLE', message: 'The invoice can no longer be financed' }));
    button(/^\s*Accept\s*$/).click();
    await settle();
    button(/^\s*Accept offer\s*$/).click();
    await settle();
    expect(notice()?.textContent).toContain('The invoice can no longer be financed');
    expect(changed).toHaveBeenCalled();
  });

  it('rejects an offer with an optional reason', async () => {
    await open([offerFixture()]);
    rejectOffer.and.resolveTo(offerFixture({ status: 'REJECTED' }));
    offersForInvoice.and.resolveTo({ offers: [offerFixture({ status: 'REJECTED', statusReason: 'Rate too high' })], truncated: false });

    button(/^\s*Reject\s*$/).click();
    await settle();
    const reason = el.querySelector<HTMLTextAreaElement>('#reject-reason')!;
    reason.value = '  Rate too high ';
    reason.dispatchEvent(new Event('input'));
    button(/^\s*Reject offer\s*$/).click();
    await settle();

    expect(rejectOffer).toHaveBeenCalledOnceWith('f0000000-0000-4000-8000-000000000001', 'Rate too high');
    expect(notice()?.textContent).toContain('Offer rejected.');
    expect(rows()[0].textContent).toContain('Rate too high');
  });

  it('says when the session ended during a decision', async () => {
    await open([offerFixture()]);
    rejectOffer.and.rejectWith(new FinancingApiError({ kind: 'unauthenticated' }));
    button(/^\s*Reject\s*$/).click();
    await settle();
    button(/^\s*Reject offer\s*$/).click();
    await settle();
    expect(notice()?.textContent).toContain('Your session has ended');
  });
});
