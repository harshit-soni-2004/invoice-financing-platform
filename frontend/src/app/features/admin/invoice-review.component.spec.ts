import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from '../seller/invoice-api.service';
import { versioned } from '../seller/invoice-testing';
import { VersionedInvoice } from '../seller/invoice.models';
import { InvoiceReviewComponent } from './invoice-review.component';

const ID = 'a0000000-0000-4000-8000-000000000001';

describe('InvoiceReviewComponent', () => {
  let fixture: ComponentFixture<InvoiceReviewComponent>;
  let el: HTMLElement;
  let get: jasmine.Spy;
  let review: jasmine.Spy;

  async function open(first: VersionedInvoice | InvoiceFailure): Promise<void> {
    get = jasmine.createSpy('get');
    review = jasmine.createSpy('review');
    if ('invoice' in first) {
      get.and.resolveTo(first);
    } else {
      get.and.rejectWith(new InvoiceApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [InvoiceReviewComponent],
      providers: [provideRouter([]), { provide: InvoiceApiService, useValue: { get, review } }],
    });
    fixture = TestBed.createComponent(InvoiceReviewComponent);
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

  const confirmButton = () => button(/Record buyer confirmation|Recording confirmation/);
  const verifyButton = () => button(/^\s*(Verify invoice|Verifying…)\s*$/);
  const rejectButton = () => button(/Reject invoice|Rejecting…/);
  const reasonBox = () => el.querySelector<HTMLTextAreaElement>('#reject-reason')!;
  const badge = () => el.querySelector('.page-header .badge')?.textContent?.trim();
  const notice = () => el.querySelector<HTMLElement>('.notice');

  function typeReason(value: string): void {
    reasonBox().value = value;
    reasonBox().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  for (const [status, confirm, verify, reject] of [
    ['SUBMITTED', true, false, true],
    ['BUYER_CONFIRMED', false, true, true],
    ['VERIFIED', false, false, false],
    ['DRAFT', false, false, false],
    ['REJECTED', false, false, false],
  ] as const) {
    it(`enables only the steps invoice-service allows from ${status}`, async () => {
      await open(versioned({ status }));
      expect(confirmButton().disabled).toBe(!confirm);
      expect(verifyButton().disabled).toBe(!verify);
      expect(rejectButton().disabled).toBe(!reject);
      expect(reasonBox().disabled).toBe(!reject);
    });
  }

  it('records buyer confirmation with the ETag, once, and only shows the new status after the server confirms', async () => {
    await open(versioned({ status: 'SUBMITTED', version: 2 }));
    expect(el.querySelector('h1')?.textContent).toContain('INV-2026/014');
    let resolve!: (value: VersionedInvoice) => void;
    review.and.returnValue(new Promise((r) => (resolve = r)));

    confirmButton().click();
    await settle();
    expect(confirmButton().textContent?.trim()).toBe('Recording confirmation…');
    expect(confirmButton().disabled).toBeTrue();
    expect(rejectButton().disabled).toBeTrue();
    confirmButton().click();
    await settle();
    expect(review).toHaveBeenCalledOnceWith('buyer-confirmation', ID, '"2"', undefined);
    expect(badge()).toBe('Submitted');

    resolve(versioned({ status: 'BUYER_CONFIRMED', version: 3, buyerConfirmedAt: '2026-10-29T03:00:00Z' }));
    await settle();

    expect(badge()).toBe('Buyer confirmed');
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Buyer confirmation recorded.');
    expect(document.activeElement).toBe(notice());
    expect(confirmButton().disabled).toBeTrue();
    expect(verifyButton().disabled).toBeFalse();
  });

  it('verifies a buyer-confirmed invoice with the current ETag', async () => {
    await open(versioned({ status: 'BUYER_CONFIRMED', version: 5 }));
    review.and.resolveTo(versioned({ status: 'VERIFIED', version: 6 }));

    verifyButton().click();
    await settle();

    expect(review).toHaveBeenCalledOnceWith('verify', ID, '"5"', undefined);
    expect(badge()).toBe('Verified');
    expect(rejectButton().disabled).toBeTrue();
  });

  it('requires a reason before rejecting, then sends it trimmed', async () => {
    await open(versioned({ status: 'SUBMITTED', version: 1 }));

    typeReason('   ');
    rejectButton().click();
    await settle();
    expect(review).not.toHaveBeenCalled();
    expect(el.querySelector('#reject-reason-error')?.textContent).toContain('Enter the reason');
    expect(reasonBox().getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(reasonBox());

    review.and.resolveTo(versioned({ status: 'REJECTED', version: 2, statusReason: 'Buyer denies the debt' }));
    typeReason('  Buyer denies the debt ');
    expect(el.querySelector('#reject-reason-error')).toBeNull();
    rejectButton().click();
    await settle();

    expect(review).toHaveBeenCalledOnceWith('reject', ID, '"1"', 'Buyer denies the debt');
    expect(badge()).toBe('Rejected');
    expect(el.textContent).toContain('Buyer denies the debt');
    expect(notice()?.textContent).toContain('Invoice rejected.');
    expect(reasonBox().value).toBe('');
    expect(reasonBox().disabled).toBeTrue();
  });

  it('shows the latest version after a 412 instead of applying the step to stale data', async () => {
    await open(versioned({ status: 'SUBMITTED', version: 2 }));
    review.and.rejectWith(new InvoiceApiError({ kind: 'stale' }));
    get.and.resolveTo(versioned({ status: 'CANCELLED', version: 3, statusReason: 'Order withdrawn' }));

    confirmButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(2);
    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('changed since you opened it');
    expect(badge()).toBe('Cancelled');
    expect(confirmButton().disabled).toBeTrue();
    expect(rejectButton().disabled).toBeTrue();
  });

  it('explains a 409 invalid transition with the reported status, then reloads', async () => {
    await open(versioned({ status: 'BUYER_CONFIRMED', version: 4 }));
    review.and.rejectWith(new InvoiceApiError({ kind: 'invalid-transition', currentStatus: 'VERIFIED' }));
    get.and.resolveTo(versioned({ status: 'VERIFIED', version: 5 }));

    verifyButton().click();
    await settle();

    expect(notice()?.textContent).toContain('This invoice is now verified, so that action is no longer possible.');
    expect(badge()).toBe('Verified');
  });

  it('keeps the invoice unchanged on 403 and says why', async () => {
    await open(versioned({ status: 'SUBMITTED' }));
    review.and.rejectWith(new InvoiceApiError({ kind: 'forbidden' }));

    confirmButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(1);
    expect(notice()?.classList).toContain('alert-danger');
    expect(notice()?.textContent).toContain('You do not have permission to do this.');
    expect(badge()).toBe('Submitted');
    expect(confirmButton().disabled).toBeFalse();
  });

  it('maps a 400 on rejection to a reason hint, without echoing the server', async () => {
    await open(versioned({ status: 'SUBMITTED' }));
    review.and.rejectWith(new InvoiceApiError({ kind: 'invalid', fieldErrors: {} }));
    typeReason('Too vague');
    rejectButton().click();
    await settle();
    expect(notice()?.textContent).toContain('Enter a reason for the rejection, up to 500 characters.');
    expect(badge()).toBe('Submitted');
  });

  it('reports an unknown invoice without suggesting it belongs to someone else', async () => {
    await open({ kind: 'not-found' });
    expect(el.querySelector('h1')?.textContent).toBe('Invoice not found');
    expect(el.querySelector('[role="alert"]')?.textContent).not.toContain('another account');
  });

  it('offers a retry when the invoice cannot be loaded', async () => {
    await open({ kind: 'network' });
    expect(el.querySelector('h1')?.textContent).toBe('The invoice could not be loaded');
    get.and.resolveTo(versioned({ status: 'SUBMITTED' }));
    button(/Try again/).click();
    await settle();
    expect(badge()).toBe('Submitted');
  });
});
