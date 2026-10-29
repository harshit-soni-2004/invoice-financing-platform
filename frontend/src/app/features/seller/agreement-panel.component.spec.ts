import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AgreementPanelComponent } from './agreement-panel.component';
import { FinancingApiError, FinancingFailure, SellerFinancingApiService } from './financing-api.service';
import {
  AGREEMENT_ID,
  INVOICE_ID,
  agreementFixture,
  installmentFixture,
  repaymentFixture,
  viewFixture,
} from './financing-testing';
import { AgreementView, RepaymentResult } from './financing.models';
import { InvoiceStatus } from './invoice.models';

describe('AgreementPanelComponent', () => {
  let fixture: ComponentFixture<AgreementPanelComponent>;
  let el: HTMLElement;
  let agreementForInvoice: jasmine.Spy;
  let agreementView: jasmine.Spy;
  let recordRepayment: jasmine.Spy;
  let repaid: jasmine.Spy;

  async function open(view: AgreementView | null | FinancingFailure, status: InvoiceStatus = 'FINANCED'): Promise<void> {
    agreementForInvoice = jasmine.createSpy('agreementForInvoice');
    agreementView = jasmine.createSpy('agreementView');
    recordRepayment = jasmine.createSpy('recordRepayment');
    if (view === null) {
      agreementForInvoice.and.resolveTo(null);
    } else if ('agreement' in view) {
      agreementForInvoice.and.resolveTo(view.agreement);
      agreementView.and.resolveTo(view);
    } else {
      agreementForInvoice.and.rejectWith(new FinancingApiError(view));
    }
    TestBed.configureTestingModule({
      imports: [AgreementPanelComponent],
      providers: [{ provide: SellerFinancingApiService, useValue: { agreementForInvoice, agreementView, recordRepayment } }],
    });
    fixture = TestBed.createComponent(AgreementPanelComponent);
    fixture.componentRef.setInput('invoiceId', INVOICE_ID);
    fixture.componentRef.setInput('invoiceStatus', status);
    repaid = jasmine.createSpy('repaid');
    fixture.componentInstance.repaid.subscribe(repaid);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  const button = (label: RegExp) => Array.from(el.querySelectorAll('button')).find((b) => label.test(b.textContent ?? ''))!;
  const findRecordButton = () =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('section button')).find((b) => /^\s*Record repayment\s*$/.test(b.textContent ?? ''));
  const recordButton = () => findRecordButton()!;
  const submitButton = () => el.querySelector<HTMLButtonElement>('dialog button[type="submit"]')!;
  const dialog = () => el.querySelector<HTMLDialogElement>('dialog')!;
  const notice = () => el.querySelector<HTMLElement>('.notice');
  const amountInput = () => el.querySelector<HTMLInputElement>('#repay-amount')!;

  function typeAmount(value: string): void {
    amountInput().value = value;
    amountInput().dispatchEvent(new Event('input'));
  }

  async function openDialogWith(value: string): Promise<void> {
    recordButton().click();
    await settle();
    typeAmount(value);
    fixture.detectChanges();
  }

  afterEach(() => {
    if (dialog()?.open) {
      dialog().close();
    }
  });

  it('shows the agreement, payout, schedule with penalty and overdue fields, and repayment history', async () => {
    await open(
      viewFixture({
        agreement: agreementFixture({
          status: 'REPAYMENT_IN_PROGRESS',
          principalRepaid: 8763.7,
          feeRepaid: 1236.3,
          penaltyAccrued: 150,
          penaltyRepaid: 20,
          penaltyOutstanding: 130,
          outstanding: 92602.6,
          overdue: true,
          overdueAmount: 41366.3,
        }),
        schedule: [
          installmentFixture({
            status: 'OVERDUE',
            principalPaid: 8763.7,
            feePaid: 1236.3,
            penaltyAccrued: 150,
            penaltyPaid: 20,
            penaltyRemaining: 130,
            penaltyAccruedThrough: '2026-10-28',
            remaining: 41366.3,
          }),
          installmentFixture({ sequenceNo: 2, dueDate: '2026-12-19' }),
        ],
        repayments: [repaymentFixture({ penaltyComponent: 20, amount: 10020 })],
      }),
    );

    expect(agreementForInvoice).toHaveBeenCalledOnceWith(INVOICE_ID);
    expect(agreementView).toHaveBeenCalledOnceWith(AGREEMENT_ID);
    const text = el.textContent!;
    expect(el.querySelector('.section-head .badge')?.textContent).toBe('Repaying');
    expect(text).toContain('₹41,366.30 is overdue');
    expect(text).toContain('Paid out (simulated)');
    expect(text).toContain('SIM-0011223344556677');
    expect(text).toContain('₹92,602.60');

    const scheduleRows = el.querySelectorAll('table')[0].querySelectorAll('tbody tr');
    expect(scheduleRows.length).toBe(2);
    expect(scheduleRows[0].textContent).toContain('Overdue');
    expect(scheduleRows[0].textContent).toContain('₹130.00 unpaid');
    expect(scheduleRows[0].textContent).toContain('to 28 Oct 2026');
    expect(scheduleRows[1].textContent).toContain('Due');

    const historyRows = el.querySelectorAll('table')[1].querySelectorAll('tbody tr');
    expect(historyRows.length).toBe(1);
    expect(historyRows[0].textContent).toContain('₹10,020.00');
    expect(historyRows[0].textContent).toContain('₹20.00');
    expect(recordButton().disabled).toBeFalse();
  });

  it('renders nothing while open for financing without an agreement', async () => {
    await open(null, 'FINANCING_AVAILABLE');
    expect(el.querySelector('section')).toBeNull();
  });

  it('says so when a financed invoice has no agreement among the recent ones', async () => {
    await open(null, 'FINANCED');
    expect(el.textContent).toContain('No financing agreement for this invoice was found');
  });

  it('shows an agreement awaiting approval without a schedule or repayment action', async () => {
    await open(
      { agreement: agreementFixture({ status: 'PENDING_APPROVAL', feeAmount: null, totalRepayable: null }), disbursement: null, schedule: [], repayments: [] },
      'FINANCING_AVAILABLE',
    );
    expect(el.textContent).toContain('Awaiting approval');
    expect(el.textContent).toContain('Fixed at payout');
    expect(el.textContent).toContain('Not paid out yet.');
    expect(findRecordButton()).toBeUndefined();
  });

  it('disables recording a repayment once the agreement is settled', async () => {
    await open(viewFixture({ agreement: agreementFixture({ status: 'SETTLED', outstanding: 0 }) }), 'SETTLED');
    expect(recordButton().disabled).toBeTrue();
  });

  it('offers a retry when the agreement cannot be loaded', async () => {
    await open({ kind: 'network' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('could not be reached');
    agreementForInvoice.and.resolveTo(agreementFixture());
    agreementView.and.resolveTo(viewFixture());
    button(/Try again/).click();
    await settle();
    expect(findRecordButton()).toBeDefined();
  });

  it('validates the amount like the backend DTO before sending anything', async () => {
    await open(viewFixture());
    await openDialogWith('10.555');
    submitButton().click();
    await settle();
    expect(el.querySelector('#repay-amount-error')?.textContent).toContain('at most 2 decimal places');
    expect(amountInput().getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(amountInput());

    typeAmount('200000');
    submitButton().click();
    await settle();
    expect(el.querySelector('#repay-amount-error')?.textContent).toContain('more than what is outstanding');

    typeAmount('0');
    submitButton().click();
    await settle();
    expect(el.querySelector('#repay-amount-error')?.textContent).toContain('at least ₹0.01');
    expect(recordRepayment).not.toHaveBeenCalled();
  });

  it('records a repayment once despite repeated clicks, with an Idempotency-Key, then reloads the real state', async () => {
    await open(viewFixture());
    let resolve!: (value: RepaymentResult) => void;
    recordRepayment.and.returnValue(new Promise((r) => (resolve = r)));

    await openDialogWith('10,000');
    submitButton().click();
    await settle();
    expect(submitButton().textContent?.trim()).toBe('Recording…');
    expect(submitButton().disabled).toBeTrue();
    submitButton().click();
    await settle();
    expect(recordRepayment).toHaveBeenCalledTimes(1);
    const [agreementId, amount, key] = recordRepayment.calls.mostRecent().args;
    expect(agreementId).toBe(AGREEMENT_ID);
    expect(amount).toBe('10000.00');
    expect(key).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(repaid).not.toHaveBeenCalled();

    agreementView.and.resolveTo(
      viewFixture({ agreement: agreementFixture({ status: 'REPAYMENT_IN_PROGRESS', outstanding: 92472.6 }), repayments: [repaymentFixture()] }),
    );
    resolve({ repayment: repaymentFixture(), replayed: false });
    await settle();

    expect(dialog().open).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Repayment of ₹10,000.00 recorded.');
    expect(el.querySelector('.section-head .badge')?.textContent).toBe('Repaying');
    expect(el.textContent).toContain('₹92,472.60');
    expect(repaid).toHaveBeenCalledTimes(1);
  });

  it('retries a repayment whose outcome is unknown with the same key, and reports an idempotent replay', async () => {
    await open(viewFixture());
    recordRepayment.and.rejectWith(new FinancingApiError({ kind: 'network' }));
    await openDialogWith('10000');
    submitButton().click();
    await settle();

    expect(dialog().open).toBeTrue();
    expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('it will not be recorded twice');
    const firstKey = recordRepayment.calls.mostRecent().args[2];

    // Typed differently, same amount: still the same repayment and the same key.
    typeAmount('10000.00');
    recordRepayment.and.resolveTo({ repayment: repaymentFixture(), replayed: true });
    submitButton().click();
    await settle();

    expect(recordRepayment.calls.mostRecent().args[2]).toBe(firstKey);
    expect(notice()?.textContent).toContain('was already recorded');
    expect(notice()?.textContent).toContain('It was not recorded a second time.');

    // A later, separate repayment of the same amount gets a new key.
    await openDialogWith('10000');
    recordRepayment.and.resolveTo({ repayment: repaymentFixture({ id: 'r2' }), replayed: false });
    submitButton().click();
    await settle();
    expect(recordRepayment.calls.mostRecent().args[2]).not.toBe(firstKey);
  });

  it('shows financing-service’s rule on the amount field when the balance changed', async () => {
    await open(viewFixture());
    recordRepayment.and.rejectWith(
      new FinancingApiError({ kind: 'rule', code: 'REPAYMENT_EXCEEDS_OUTSTANDING', message: 'The repayment is larger than the amount outstanding' }),
    );
    await openDialogWith('100000');
    submitButton().click();
    await settle();
    expect(dialog().open).toBeTrue();
    expect(el.querySelector('#repay-amount-error')?.textContent).toContain('larger than the amount outstanding');
    expect(agreementView).toHaveBeenCalledTimes(2);
  });

  it('closes the dialog and shows the settled agreement when it was settled meanwhile (conflict)', async () => {
    await open(viewFixture());
    recordRepayment.and.rejectWith(new FinancingApiError({ kind: 'conflict', code: 'AGREEMENT_SETTLED', currentStatus: null }));
    agreementView.and.resolveTo(viewFixture({ agreement: agreementFixture({ status: 'SETTLED', outstanding: 0 }) }));
    await openDialogWith('100');
    submitButton().click();
    await settle();

    expect(dialog().open).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('already fully repaid');
    expect(el.querySelector('.section-head .badge')?.textContent).toBe('Settled');
    expect(recordButton().disabled).toBeTrue();
  });

  it('starts over with a new key when financing-service says the key was used for another amount', async () => {
    await open(viewFixture());
    recordRepayment.and.rejectWith(new FinancingApiError({ kind: 'conflict', code: 'IDEMPOTENCY_KEY_REUSED', currentStatus: null }));
    await openDialogWith('100');
    submitButton().click();
    await settle();
    const firstKey = recordRepayment.calls.mostRecent().args[2];
    expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('different amount');

    recordRepayment.and.resolveTo({ repayment: repaymentFixture({ amount: 100 }), replayed: false });
    submitButton().click();
    await settle();
    expect(recordRepayment.calls.mostRecent().args[2]).not.toBe(firstKey);
  });

  it('explains rate limiting and keeps the entered amount', async () => {
    await open(viewFixture());
    recordRepayment.and.rejectWith(new FinancingApiError({ kind: 'rate-limited', retryAfterSeconds: 7 }));
    await openDialogWith('100');
    submitButton().click();
    await settle();
    expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('Wait 7 seconds');
    expect(amountInput().value).toBe('100');
  });

  it('fills in the full outstanding amount on request', async () => {
    await open(viewFixture());
    recordButton().click();
    await settle();
    button(/Use the full outstanding amount/).click();
    await settle();
    expect(amountInput().value).toBe('102472.60');
  });
});
