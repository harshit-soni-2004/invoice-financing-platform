import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { INVOICE_ID, offerFixture, opportunityFixture } from './financier-testing';
import { Offer, Opportunity } from './financier.models';
import { OpportunityDetailComponent } from './opportunity-detail.component';

describe('OpportunityDetailComponent', () => {
  let fixture: ComponentFixture<OpportunityDetailComponent>;
  let el: HTMLElement;
  let api: Record<'opportunity' | 'offersForInvoice' | 'createOffer' | 'withdrawOffer', jasmine.Spy>;

  async function open(opportunity: Opportunity | FinancingFailure = opportunityFixture(), offers: Offer[] = []): Promise<void> {
    api = {
      opportunity: jasmine.createSpy('opportunity'),
      offersForInvoice: jasmine.createSpy('offersForInvoice').and.resolveTo(offers),
      createOffer: jasmine.createSpy('createOffer'),
      withdrawOffer: jasmine.createSpy('withdrawOffer'),
    };
    if ('invoiceId' in opportunity) {
      api.opportunity.and.resolveTo(opportunity);
    } else {
      api.opportunity.and.rejectWith(new FinancingApiError(opportunity));
    }
    TestBed.configureTestingModule({
      imports: [OpportunityDetailComponent],
      providers: [provideRouter([]), { provide: FinancingApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(OpportunityDetailComponent);
    fixture.componentRef.setInput('invoiceId', INVOICE_ID);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function input(id: string): HTMLInputElement {
    return el.querySelector<HTMLInputElement>(`#${id}`)!;
  }

  function type(id: string, value: string): void {
    const field = input(id);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
  }

  function button(label: RegExp): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => label.test(b.textContent ?? ''));
  }

  const errorText = (id: string) => el.querySelector(`#${id}-error`)?.textContent?.trim();
  const notice = () => el.querySelector<HTMLElement>('.notice');

  async function fillValidOffer(): Promise<void> {
    type('offer-advance', '4,80,000');
    type('offer-rate', '12.5');
    type('offer-fee', '1000');
    type('offer-installments', '2');
    await settle();
  }

  it('shows the invoice facts and an offer form with a valid default expiry', async () => {
    await open();

    expect(api.opportunity).toHaveBeenCalledOnceWith(INVOICE_ID);
    expect(api.offersForInvoice).toHaveBeenCalledOnceWith(INVOICE_ID);
    expect(el.querySelector('h1')?.textContent).toContain('INV-2026/014');
    const details = el.querySelector('dl.details')!.textContent!;
    expect(details).toContain('Kaveri Textiles Pvt Ltd');
    expect(details).toContain('₹5,00,000.00');
    expect(details).toContain('₹4,90,000.00');
    expect(input('offer-expires').value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(el.textContent).toContain('You have not made an offer on this invoice.');
  });

  it('checks the terms against the invoice and financing-service’s bounds before sending', async () => {
    await open(opportunityFixture({ daysToMaturity: 9 }));
    type('offer-advance', '495000');
    type('offer-rate', '40');
    type('offer-fee', '495000');
    type('offer-installments', '10');
    await settle();

    button(/^\s*Send offer\s*$/)!.click();
    await settle();

    expect(api.createOffer).not.toHaveBeenCalled();
    expect(errorText('offer-advance')).toBe('The advance can be at most ₹4,90,000.00 for this invoice.');
    expect(errorText('offer-rate')).toBe('The rate must be more than 0% and at most 36% a year.');
    expect(errorText('offer-fee')).toBe('The processing fee must be less than the advance.');
    expect(errorText('offer-installments')).toContain('from 1 to 9');
    expect(document.activeElement).toBe(input('offer-advance'));
    expect(input('offer-advance').getAttribute('aria-invalid')).toBe('true');
  });

  it('refuses an expiry in the past', async () => {
    await open();
    await fillValidOffer();
    type('offer-expires', '2020-01-01T10:00');
    await settle();
    button(/^\s*Send offer\s*$/)!.click();
    await settle();
    expect(errorText('offer-expires')).toBe('The expiry must be in the future.');
    expect(api.createOffer).not.toHaveBeenCalled();
  });

  it('sends one offer however often the button is clicked, then shows it with a withdraw action', async () => {
    await open();
    let resolve!: (offer: Offer) => void;
    api.createOffer.and.returnValue(new Promise((r) => (resolve = r)));
    await fillValidOffer();

    button(/^\s*Send offer\s*$/)!.click();
    await settle();
    const sending = button(/Sending offer…/)!;
    expect(sending.disabled).toBeTrue();
    sending.click();
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();

    expect(api.createOffer).toHaveBeenCalledTimes(1);
    const draft = api.createOffer.calls.mostRecent().args[0];
    expect(draft).toEqual({
      invoiceId: INVOICE_ID,
      advanceAmount: '480000',
      annualRate: '0.1250',
      processingFee: '1000',
      installmentCount: 2,
      expiresAt: jasmine.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z$/),
    });

    resolve(offerFixture({ annualRate: 0.125 }));
    await settle();

    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Offer sent.');
    expect(el.querySelector('.offers')?.textContent).toContain('12.5% p.a.');
    expect(button(/Withdraw offer/)).toBeDefined();
    expect(el.querySelector('form')).toBeNull();
    expect(el.textContent).toContain('You already have an open offer on this invoice.');
  });

  it('puts financing-service’s rule answer on the field it concerns', async () => {
    await open();
    api.createOffer.and.rejectWith(
      new FinancingApiError({ kind: 'rule', code: 'EXPIRY_INVALID', field: 'expiresAt', message: 'expiresAt must be in the future, within 14 days, and before the invoice due date' }),
    );
    await fillValidOffer();
    button(/^\s*Send offer\s*$/)!.click();
    await settle();

    expect(errorText('offer-expires')).toContain('expiresAt must be in the future');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('expiresAt must be in the future');
    expect(document.activeElement).toBe(input('offer-expires'));
  });

  it('reloads after a conflict such as a duplicate open offer', async () => {
    await open();
    api.createOffer.and.rejectWith(new FinancingApiError({ kind: 'duplicate-offer' }));
    api.offersForInvoice.and.resolveTo([offerFixture()]);
    await fillValidOffer();
    button(/^\s*Send offer\s*$/)!.click();
    await settle();

    expect(api.offersForInvoice).toHaveBeenCalledTimes(2);
    expect(api.opportunity).toHaveBeenCalledTimes(2);
    expect(el.querySelector('.notice')?.textContent).toContain('You already have an open offer on this invoice. Withdraw it first');
    expect(el.querySelector('form')).toBeNull();
    expect(button(/Withdraw offer/)).toBeDefined();
  });

  it('says the outcome is unknown when the answer is lost, and shows the latest offers', async () => {
    await open();
    api.createOffer.and.rejectWith(new FinancingApiError({ kind: 'network' }));
    await fillValidOffer();
    button(/^\s*Send offer\s*$/)!.click();
    await settle();

    expect(el.querySelector('.notice')?.textContent).toContain('may or may not have been sent');
    expect(api.offersForInvoice).toHaveBeenCalledTimes(2);
  });

  it('withdraws the open offer once, even when clicked twice', async () => {
    await open(opportunityFixture(), [offerFixture()]);
    let resolve!: (offer: Offer) => void;
    api.withdrawOffer.and.returnValue(new Promise((r) => (resolve = r)));

    button(/Withdraw offer/)!.click();
    await settle();
    const busy = button(/Withdrawing…/)!;
    expect(busy.disabled).toBeTrue();
    busy.click();
    await settle();
    expect(api.withdrawOffer).toHaveBeenCalledOnceWith('f0000000-0000-4000-8000-000000000001');

    resolve(offerFixture({ status: 'WITHDRAWN' }));
    await settle();
    expect(notice()?.textContent).toContain('Offer withdrawn.');
    expect(el.querySelector('.offers .badge')?.textContent?.trim()).toBe('Withdrawn');
    expect(el.querySelector('form')).not.toBeNull();
  });

  it('reloads when the offer was decided meanwhile', async () => {
    await open(opportunityFixture(), [offerFixture()]);
    api.withdrawOffer.and.rejectWith(new FinancingApiError({ kind: 'invalid-transition', currentStatus: 'ACCEPTED' }));
    api.offersForInvoice.and.resolveTo([offerFixture({ status: 'ACCEPTED' })]);

    button(/Withdraw offer/)!.click();
    await settle();

    expect(notice()?.classList).toContain('alert-warning');
    expect(notice()?.textContent).toContain('This is now accepted');
    expect(el.querySelector('.offers .badge')?.textContent?.trim()).toBe('Accepted');
    expect(el.textContent).toContain('Review the agreement awaiting your approval');
  });

  it('explains an invoice that is no longer open, and still lists the financier’s offers on it', async () => {
    await open({ kind: 'not-found' }, [offerFixture({ status: 'CLOSED', statusReason: 'Another offer for this invoice was accepted' })]);

    expect(el.querySelector('h1')?.textContent).toBe('This invoice is not open for offers');
    expect(el.querySelector('form')).toBeNull();
    expect(el.textContent).toContain('Another offer for this invoice was accepted');
  });

  it('shows the approval explanation when financing-service refuses the account', async () => {
    await open({ kind: 'forbidden' });
    expect(el.querySelector('h2')?.textContent).toBe('Your financier account is not active');
    expect(el.textContent).toContain('administrator approves them');
    expect(el.querySelector('form')).toBeNull();
  });

  it('offers a retry when the opportunity cannot be loaded', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('h1')?.textContent).toBe('The opportunity could not be loaded');

    api.opportunity.and.resolveTo(opportunityFixture());
    button(/Try again/)!.click();
    await settle();
    expect(el.querySelector('h1')?.textContent).toContain('INV-2026/014');
  });
});
