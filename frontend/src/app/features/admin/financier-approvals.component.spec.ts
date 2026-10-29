import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ApiError, ApiFailure, Page } from '../../shared/api-failure';
import { pageOf, userFixture } from './admin-testing';
import { AdminUser, AdminUsersService } from './admin-users.service';
import { FinancierApprovalsComponent } from './financier-approvals.component';

describe('FinancierApprovalsComponent', () => {
  let fixture: ComponentFixture<FinancierApprovalsComponent>;
  let el: HTMLElement;
  let pendingFinanciers: jasmine.Spy;
  let activate: jasmine.Spy;
  let reject: jasmine.Spy;

  const ravi = userFixture();
  const meera = userFixture({ id: 'f0000000-0000-4000-8000-000000000002', displayName: 'Meera Iyer', email: 'meera@example.test' });

  async function open(first: Page<AdminUser> | ApiFailure): Promise<void> {
    pendingFinanciers = jasmine.createSpy('pendingFinanciers');
    activate = jasmine.createSpy('activate');
    reject = jasmine.createSpy('reject');
    if ('content' in first) {
      pendingFinanciers.and.resolveTo(first);
    } else {
      pendingFinanciers.and.rejectWith(new ApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [FinancierApprovalsComponent],
      providers: [provideRouter([]), { provide: AdminUsersService, useValue: { pendingFinanciers, activate, reject } }],
    });
    fixture = TestBed.createComponent(FinancierApprovalsComponent);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  const approveButton = (name: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-label="Approve ${name}"]`);
  const rejectButton = (name: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-label="Reject ${name}"]`);
  const notice = () => el.querySelector<HTMLElement>('.notice');
  const row = (name: string) => Array.from(el.querySelectorAll('tbody tr')).find((r) => r.textContent?.includes(name))!;
  const dialog = () => el.querySelector<HTMLDialogElement>('dialog')!;
  const dialogButton = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;
  const reasonBox = () => dialog().querySelector<HTMLTextAreaElement>('textarea')!;

  async function openRejectFor(name: string, reason = ''): Promise<void> {
    rejectButton(name)!.click();
    await settle();
    if (reason) {
      reasonBox().value = reason;
      reasonBox().dispatchEvent(new Event('input'));
      await settle();
    }
  }

  afterEach(() => {
    if (dialog()?.open) {
      dialog().close();
    }
  });

  it('lists pending financiers with approve and reject actions', async () => {
    await open(pageOf([ravi, meera]));

    expect(pendingFinanciers).toHaveBeenCalledOnceWith(0);
    expect(el.querySelector('h1')?.textContent).toBe('Financier approvals');
    expect(el.textContent).toContain('2 accounts waiting for approval');
    expect(row('Ravi Menon').textContent).toContain('ravi@example.test');
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
    expect(approveButton('Ravi Menon')?.disabled).toBeFalse();
    expect(rejectButton('Ravi Menon')?.disabled).toBeFalse();
    expect(el.textContent).toContain('Rejecting an application is final');
    expect(el.textContent).not.toContain('not available');
    expect(dialog().open).toBeFalse();
  });

  it('asks for confirmation, sends the trimmed reason and shows Rejected only after auth-service answers', async () => {
    await open(pageOf([ravi, meera]));
    let resolve!: (value: AdminUser) => void;
    reject.and.returnValue(new Promise((r) => (resolve = r)));

    await openRejectFor('Ravi Menon', '  Company registration could not be verified  ');
    expect(dialog().open).toBeTrue();
    expect(dialog().textContent).toContain('Reject Ravi Menon?');
    expect(dialog().textContent).toContain('ravi@example.test');
    expect(document.activeElement).toBe(dialogButton('Keep pending'));
    expect(reject).not.toHaveBeenCalled();

    dialogButton('Reject application').click();
    await settle();
    expect(reject).toHaveBeenCalledOnceWith(ravi.id, 'Company registration could not be verified');
    expect(dialogButton('Rejecting…').disabled).toBeTrue();
    expect(approveButton('Meera Iyer')?.disabled).toBeTrue();
    expect(row('Ravi Menon').textContent).toContain('Pending approval');

    resolve({ ...ravi, status: 'REJECTED' });
    await settle();

    expect(dialog().open).toBeFalse();
    expect(row('Ravi Menon').textContent).toContain('Rejected');
    expect(row('Ravi Menon').querySelector('.badge')?.classList).toContain('badge-danger');
    expect(rejectButton('Ravi Menon')).toBeNull();
    expect(approveButton('Ravi Menon')).toBeNull();
    expect(approveButton('Meera Iyer')?.disabled).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain("Ravi Menon's application is rejected.");
  });

  it('sends no reason when the field is left blank', async () => {
    await open(pageOf([ravi]));
    reject.and.resolveTo({ ...ravi, status: 'REJECTED' });
    await openRejectFor('Ravi Menon', '   ');
    dialogButton('Reject application').click();
    await settle();
    expect(reject).toHaveBeenCalledOnceWith(ravi.id, null);
  });

  it('keeps the account pending when the administrator backs out', async () => {
    await open(pageOf([ravi]));
    await openRejectFor('Ravi Menon', 'Not sure yet');
    dialogButton('Keep pending').click();
    await settle();

    expect(dialog().open).toBeFalse();
    expect(reject).not.toHaveBeenCalled();
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
    expect(rejectButton('Ravi Menon')?.disabled).toBeFalse();
  });

  it('refuses an over-long reason without calling auth-service', async () => {
    await open(pageOf([ravi]));
    await openRejectFor('Ravi Menon', 'x'.repeat(501));
    dialogButton('Reject application').click();
    await settle();

    expect(reject).not.toHaveBeenCalled();
    expect(dialog().open).toBeTrue();
    expect(dialog().querySelector('[role="alert"]')?.textContent).toContain('500 characters or fewer');
  });

  it('keeps the dialog open with a retry message when auth-service cannot be reached', async () => {
    await open(pageOf([ravi]));
    reject.and.rejectWith(new ApiError({ kind: 'unavailable' }));
    await openRejectFor('Ravi Menon');
    dialogButton('Reject application').click();
    await settle();

    expect(dialog().open).toBeTrue();
    const alert = dialog().querySelector<HTMLElement>('[role="alert"]')!;
    expect(alert.textContent).toContain('Ravi Menon was not rejected.');
    expect(alert.textContent).toContain('temporarily unavailable');
    expect(document.activeElement).toBe(alert);
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
    expect(dialogButton('Reject application').disabled).toBeFalse();
  });

  it('shows the current status when the account was decided elsewhere', async () => {
    await open(pageOf([ravi]));
    reject.and.rejectWith(new ApiError({ kind: 'conflict', code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE' }));
    await openRejectFor('Ravi Menon');
    dialogButton('Reject application').click();
    await settle();

    expect(dialog().open).toBeFalse();
    expect(notice()?.textContent).toContain('Ravi Menon is no longer waiting for approval. It is now approved.');
    expect(row('Ravi Menon').textContent).toContain('Approved');
  });

  it('says why on 403 and leaves the account pending', async () => {
    await open(pageOf([ravi]));
    reject.and.rejectWith(new ApiError({ kind: 'forbidden' }));
    await openRejectFor('Ravi Menon');
    dialogButton('Reject application').click();
    await settle();

    expect(dialog().open).toBeFalse();
    expect(notice()?.textContent).toContain('You do not have permission to reject accounts.');
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
  });

  it('shows an empty state', async () => {
    await open(pageOf([]));
    expect(el.textContent).toContain('No accounts are waiting');
  });

  it('approves once, waits for auth-service and shows the confirmed status', async () => {
    await open(pageOf([ravi, meera]));
    let resolve!: (value: AdminUser) => void;
    activate.and.returnValue(new Promise((r) => (resolve = r)));

    approveButton('Ravi Menon')!.click();
    await settle();
    expect(approveButton('Ravi Menon')?.textContent?.trim()).toBe('Approving…');
    expect(approveButton('Ravi Menon')?.disabled).toBeTrue();
    expect(approveButton('Meera Iyer')?.disabled).toBeTrue();
    approveButton('Ravi Menon')!.click();
    await settle();
    expect(activate).toHaveBeenCalledOnceWith(ravi.id);
    // Nothing changes until auth-service answers.
    expect(row('Ravi Menon').textContent).toContain('Pending approval');

    resolve({ ...ravi, status: 'ACTIVE' });
    await settle();

    expect(row('Ravi Menon').textContent).toContain('Approved');
    expect(approveButton('Ravi Menon')).toBeNull();
    expect(approveButton('Meera Iyer')?.disabled).toBeFalse();
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Ravi Menon is approved and can now sign in.');
    expect(document.activeElement).toBe(notice());
  });

  it('explains 409 when the account is no longer pending and shows its current status', async () => {
    await open(pageOf([ravi]));
    activate.and.rejectWith(new ApiError({ kind: 'conflict', code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE' }));

    approveButton('Ravi Menon')!.click();
    await settle();

    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('Ravi Menon is no longer waiting for approval. It is now approved.');
    expect(row('Ravi Menon').textContent).toContain('Approved');
    expect(approveButton('Ravi Menon')).toBeNull();
  });

  it('reloads the list after a concurrent-modification 409', async () => {
    await open(pageOf([ravi]));
    activate.and.rejectWith(new ApiError({ kind: 'conflict', code: 'CONCURRENT_MODIFICATION', currentStatus: null }));
    pendingFinanciers.and.resolveTo(pageOf([ravi]));

    approveButton('Ravi Menon')!.click();
    await settle();

    expect(pendingFinanciers).toHaveBeenCalledTimes(2);
    expect(notice()?.textContent).toContain('changed by another request');
    expect(approveButton('Ravi Menon')?.disabled).toBeFalse();
  });

  it('keeps the account pending on 403 and says why', async () => {
    await open(pageOf([ravi]));
    activate.and.rejectWith(new ApiError({ kind: 'forbidden' }));

    approveButton('Ravi Menon')!.click();
    await settle();

    expect(notice()?.classList).toContain('alert-danger');
    expect(notice()?.textContent).toContain('You do not have permission to approve accounts.');
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
    expect(approveButton('Ravi Menon')?.disabled).toBeFalse();
  });

  it('never reports success for an unexpected 412', async () => {
    await open(pageOf([ravi]));
    activate.and.rejectWith(new ApiError({ kind: 'stale' }));

    approveButton('Ravi Menon')!.click();
    await settle();

    expect(notice()?.classList).toContain('alert-danger');
    expect(notice()?.textContent).toContain('Ravi Menon was not approved.');
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
  });

  it('marks an account that no longer exists', async () => {
    await open(pageOf([ravi]));
    activate.and.rejectWith(new ApiError({ kind: 'not-found' }));
    approveButton('Ravi Menon')!.click();
    await settle();
    expect(row('Ravi Menon').textContent).toContain('No longer exists');
  });

  it('offers a retry when the list cannot be loaded', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('account service is temporarily unavailable');

    pendingFinanciers.and.resolveTo(pageOf([ravi]));
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
    await settle();
    expect(approveButton('Ravi Menon')).not.toBeNull();
  });
});
