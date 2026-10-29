import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AgreementDetailComponent } from './agreement-detail.component';
import { DisburseKeysService } from './disburse-keys.service';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import {
  AGREEMENT_ID,
  agreementFixture,
  disbursedAgreement,
  disbursementFixture,
  installmentFixture,
} from './financier-testing';
import { Agreement, DisbursementResult } from './financier.models';

describe('AgreementDetailComponent', () => {
  let fixture: ComponentFixture<AgreementDetailComponent>;
  let el: HTMLElement;
  let api: Record<'agreement' | 'approve' | 'disburse' | 'schedule' | 'disbursement', jasmine.Spy>;

  async function open(first: Agreement | FinancingFailure): Promise<void> {
    api = {
      agreement: jasmine.createSpy('agreement'),
      approve: jasmine.createSpy('approve'),
      disburse: jasmine.createSpy('disburse'),
      schedule: jasmine.createSpy('schedule').and.resolveTo([]),
      disbursement: jasmine.createSpy('disbursement').and.resolveTo(null),
    };
    if ('id' in first) {
      api.agreement.and.resolveTo(first);
    } else {
      api.agreement.and.rejectWith(new FinancingApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [AgreementDetailComponent],
      providers: [provideRouter([]), { provide: FinancingApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(AgreementDetailComponent);
    fixture.componentRef.setInput('id', AGREEMENT_ID);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function button(label: RegExp): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => label.test(b.textContent ?? ''));
  }

  const approveButton = () => button(/Approve agreement|Approving…/)!;
  const disburseButton = () => button(/^\s*Disburse advance\s*$/);
  const confirmButton = () => button(/Confirm disbursement|Disbursing…/)!;
  const badge = () => el.querySelector('.page-header .badge')?.textContent?.trim();
  const notice = () => el.querySelector<HTMLElement>('.notice');

  for (const [status, approve, disburse] of [
    ['PENDING_APPROVAL', true, false],
    ['APPROVED', false, true],
    ['DISBURSED', false, false],
    ['REPAYMENT_IN_PROGRESS', false, false],
    ['SETTLED', false, false],
    ['REJECTED', false, false],
    ['CANCELLED', false, false],
  ] as const) {
    it(`offers only the actions ${status} allows`, async () => {
      await open(agreementFixture({ status }));
      expect(approveButton().disabled).toBe(!approve);
      expect(disburseButton()!.disabled).toBe(!disburse);
    });
  }

  it('approves once, shows the approved state and enables disbursement', async () => {
    await open(agreementFixture());
    let resolve!: (value: Agreement) => void;
    api.approve.and.returnValue(new Promise((r) => (resolve = r)));

    approveButton().click();
    await settle();
    expect(approveButton().textContent?.trim()).toBe('Approving…');
    approveButton().click();
    await settle();
    expect(api.approve).toHaveBeenCalledOnceWith(AGREEMENT_ID);
    expect(badge()).toBe('Awaiting your approval');

    api.agreement.and.resolveTo(agreementFixture({ status: 'APPROVED', approvedAt: '2026-10-28T10:00:00Z' }));
    resolve(agreementFixture({ status: 'APPROVED', overdue: null, overdueAmount: null }));
    await settle();

    expect(badge()).toBe('Approved');
    expect(notice()?.textContent).toContain('Agreement approved.');
    expect(document.activeElement).toBe(notice());
    expect(disburseButton()!.disabled).toBeFalse();
    expect(el.querySelector('.milestones')?.textContent).toContain('Approved');
  });

  it('asks for confirmation, disburses once with an Idempotency-Key, then shows the schedule', async () => {
    await open(agreementFixture({ status: 'APPROVED' }));
    disburseButton()!.click();
    await settle();
    expect(el.querySelector('.confirm')?.textContent).toContain('Disburse ₹4,80,000.00 to the seller now?');
    expect(document.activeElement).toBe(confirmButton());
    expect(api.disburse).not.toHaveBeenCalled();

    let resolve!: (value: DisbursementResult) => void;
    api.disburse.and.returnValue(new Promise((r) => (resolve = r)));
    confirmButton().click();
    await settle();
    expect(confirmButton().textContent?.trim()).toBe('Disbursing…');
    expect(confirmButton().disabled).toBeTrue();
    confirmButton().click();
    await settle();
    expect(api.disburse).toHaveBeenCalledTimes(1);
    const [id, key] = api.disburse.calls.mostRecent().args;
    expect(id).toBe(AGREEMENT_ID);
    expect(key).toMatch(/^[A-Za-z0-9_-]{8,100}$/);

    api.agreement.and.resolveTo(disbursedAgreement());
    api.schedule.and.resolveTo([installmentFixture(), installmentFixture({ sequenceNo: 2, status: 'PENDING' })]);
    api.disbursement.and.resolveTo(disbursementFixture());
    resolve({ disbursement: disbursementFixture(), replayed: false });
    await settle();

    expect(notice()?.textContent).toContain('Advance of ₹4,80,000.00 disbursed (simulated). Reference SIM-DISB-0001.');
    expect(badge()).toBe('Disbursed');
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(el.textContent).toContain('₹4,90,468.49');
    expect(el.textContent).toContain('SIM-DISB-0001');
    expect(disburseButton()!.disabled).toBeTrue();
  });

  it('retries a disbursement whose answer was lost with the same key', async () => {
    await open(agreementFixture({ status: 'APPROVED' }));
    api.disburse.and.rejectWith(new FinancingApiError({ kind: 'network' }));

    disburseButton()!.click();
    await settle();
    confirmButton().click();
    await settle();
    expect(notice()?.textContent).toContain('Retrying is safe');
    expect(api.agreement).toHaveBeenCalledTimes(2);
    const firstKey = api.disburse.calls.mostRecent().args[1];

    api.disburse.and.resolveTo({ disbursement: disbursementFixture(), replayed: true });
    api.agreement.and.resolveTo(disbursedAgreement());
    disburseButton()!.click();
    await settle();
    confirmButton().click();
    await settle();

    expect(api.disburse.calls.mostRecent().args[1]).toBe(firstKey);
    expect(notice()?.textContent).toContain('had already been disbursed');
    expect(notice()?.textContent).toContain('Nothing was paid twice.');
  });

  it('uses a new key for a later, separate disbursement once the first is confirmed', async () => {
    await open(agreementFixture({ status: 'APPROVED' }));
    const keys = TestBed.inject(DisburseKeysService);
    api.disburse.and.resolveTo({ disbursement: disbursementFixture(), replayed: false });
    api.agreement.and.resolveTo(disbursedAgreement());
    disburseButton()!.click();
    await settle();
    confirmButton().click();
    await settle();
    const used = api.disburse.calls.mostRecent().args[1];
    expect(keys.keyFor(AGREEMENT_ID)).not.toBe(used);
  });

  it('shows the latest state when the agreement changed meanwhile', async () => {
    await open(agreementFixture({ status: 'APPROVED' }));
    api.disburse.and.rejectWith(new FinancingApiError({ kind: 'invalid-transition', currentStatus: 'CANCELLED' }));
    api.agreement.and.resolveTo(agreementFixture({ status: 'CANCELLED', statusReason: 'Seller withdrew' }));

    disburseButton()!.click();
    await settle();
    confirmButton().click();
    await settle();

    expect(notice()?.classList).toContain('alert-warning');
    expect(notice()?.textContent).toContain('This is now cancelled');
    expect(badge()).toBe('Cancelled');
    expect(el.textContent).toContain('Seller withdrew');
    expect(disburseButton()!.disabled).toBeTrue();
  });

  it('cancelling the confirmation sends nothing', async () => {
    await open(agreementFixture({ status: 'APPROVED' }));
    disburseButton()!.click();
    await settle();
    button(/^\s*Cancel\s*$/)!.click();
    await settle();
    expect(api.disburse).not.toHaveBeenCalled();
    expect(disburseButton()!.disabled).toBeFalse();
  });

  it('marks overdue installments and the overdue amount', async () => {
    await open(disbursedAgreement({ status: 'REPAYMENT_IN_PROGRESS', overdue: true, overdueAmount: 100000, principalRepaid: 140000, feeRepaid: 5234.24, outstanding: 345234.25 }));
    api.schedule.and.resolveTo([
      installmentFixture({ status: 'OVERDUE', principalPaid: 140000, feePaid: 5234.24, remaining: 100000 }),
      installmentFixture({ sequenceNo: 2 }),
    ]);
    fixture.componentInstance['refresh']();
    await settle();

    const rows = el.querySelectorAll('tbody tr');
    expect(rows[0].classList).toContain('overdue-row');
    expect(rows[0].textContent).toContain('Overdue');
    expect(rows[1].textContent).toContain('Not yet due');
    expect(el.querySelector('.summary-figures .danger')?.textContent).toContain('₹1,00,000.00');
  });

  it('shows the penalty breakdown, and totals that reconcile with financing-service’s outstanding amount', async () => {
    // Total repayable 4,90,468.49 + penalty 1,226.17 − repaid 1,46,460.41 (incl. 1,226.17 penalty) = 3,45,234.25.
    await open(
      disbursedAgreement({
        status: 'REPAYMENT_IN_PROGRESS',
        overdue: true,
        overdueAmount: 100000,
        principalRepaid: 140000,
        feeRepaid: 5234.24,
        penaltyAccrued: 1226.17,
        penaltyRepaid: 1226.17,
        penaltyOutstanding: 0,
        outstanding: 345234.25,
      }),
    );
    api.schedule.and.resolveTo([
      installmentFixture({
        status: 'OVERDUE',
        principalPaid: 140000,
        feePaid: 5234.24,
        remaining: 100000,
        penaltyAccrued: 1226.17,
        penaltyPaid: 1226.17,
        penaltyRemaining: 0,
        penaltyAccruedThrough: '2026-10-27',
      }),
      installmentFixture({ sequenceNo: 2, penaltyAccrued: 2000, penaltyPaid: 0, penaltyRemaining: 2000 }),
    ]);
    fixture.componentInstance['refresh']();
    await settle();

    const figures = el.querySelector('.summary-figures')!.textContent!;
    expect(figures).toContain('Penalty accrued₹1,226.17');
    expect(figures).toContain('Repaid (incl. penalty)₹1,46,460.41');
    expect(figures).toContain('Outstanding (incl. penalty)₹3,45,234.25');
    expect(figures).toContain('Penalty outstanding₹0.00');

    const headers = Array.from(el.querySelectorAll('thead th')).map((th) => th.textContent?.trim());
    expect(headers).toContain('Penalty');
    const rows = el.querySelectorAll('tbody tr');
    const penaltyCell = (row: Element) => row.querySelector('[data-label="Penalty"]')!.textContent!;
    expect(rows[0].querySelector('[data-label="Paid"]')?.textContent).toContain('₹1,46,460.41');
    expect(penaltyCell(rows[0])).toContain('₹1,226.17');
    expect(penaltyCell(rows[0])).toContain('to 27 Oct 2026');
    expect(penaltyCell(rows[0])).not.toContain('unpaid');
    expect(penaltyCell(rows[1])).toContain('₹2,000.00 unpaid');

    const note = el.querySelector('.penalty-note')!.textContent!;
    expect(note).toContain('placeholders awaiting business sign-off');
    expect(note).toContain('not approved policy');
  });

  it('hides penalty figures instead of showing zero when financing-service does not send them', async () => {
    await open(disbursedAgreement({ penaltyAccrued: null, penaltyRepaid: null, penaltyOutstanding: null }));
    api.schedule.and.resolveTo([
      installmentFixture({ penaltyAccrued: null, penaltyPaid: null, penaltyRemaining: null, penaltyAccruedThrough: null }),
    ]);
    fixture.componentInstance['refresh']();
    await settle();

    expect(el.querySelector('.summary-figures')!.textContent).not.toContain('Penalty');
    expect(Array.from(el.querySelectorAll('thead th')).map((th) => th.textContent?.trim())).not.toContain('Penalty');
    expect(el.querySelector('.penalty-note')).toBeNull();
  });

  it('reports an agreement that does not exist or belongs to someone else', async () => {
    await open({ kind: 'not-found' });
    expect(el.querySelector('h1')?.textContent).toBe('Agreement not found');
    expect(button(/Try again/)).toBeUndefined();
  });

  it('shows the approval explanation when financing-service refuses the account', async () => {
    await open({ kind: 'forbidden' });
    expect(el.querySelector('h2')?.textContent).toBe('Your financier account is not active');
  });

  it('asks to sign in again when the session has ended', async () => {
    await open({ kind: 'unauthenticated' });
    expect(el.querySelector('h2')?.textContent).toBe('Your session has ended');
    expect(el.querySelector('a[href^="/login"]')).not.toBeNull();
  });

  it('says when too many requests were made', async () => {
    await open(agreementFixture());
    api.approve.and.rejectWith(new FinancingApiError({ kind: 'rate-limited', retryAfterSeconds: 7 }));
    approveButton().click();
    await settle();
    expect(notice()?.textContent).toContain('Wait 7 seconds');
    expect(approveButton().disabled).toBeFalse();
  });
});
