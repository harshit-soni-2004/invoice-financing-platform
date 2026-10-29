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

  const ravi = userFixture();
  const meera = userFixture({ id: 'f0000000-0000-4000-8000-000000000002', displayName: 'Meera Iyer', email: 'meera@example.test' });

  async function open(first: Page<AdminUser> | ApiFailure): Promise<void> {
    pendingFinanciers = jasmine.createSpy('pendingFinanciers');
    activate = jasmine.createSpy('activate');
    if ('content' in first) {
      pendingFinanciers.and.resolveTo(first);
    } else {
      pendingFinanciers.and.rejectWith(new ApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [FinancierApprovalsComponent],
      providers: [provideRouter([]), { provide: AdminUsersService, useValue: { pendingFinanciers, activate } }],
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
  const notice = () => el.querySelector<HTMLElement>('.notice');
  const row = (name: string) => Array.from(el.querySelectorAll('tbody tr')).find((r) => r.textContent?.includes(name))!;

  it('lists pending financiers and says that declining is not available', async () => {
    await open(pageOf([ravi, meera]));

    expect(pendingFinanciers).toHaveBeenCalledOnceWith(0);
    expect(el.querySelector('h1')?.textContent).toBe('Financier approvals');
    expect(el.textContent).toContain('2 accounts waiting for approval');
    expect(row('Ravi Menon').textContent).toContain('ravi@example.test');
    expect(row('Ravi Menon').textContent).toContain('Pending approval');
    expect(approveButton('Ravi Menon')?.disabled).toBeFalse();
    expect(el.textContent).toContain('Declining an application is not available');
    expect(Array.from(el.querySelectorAll('button')).some((b) => /reject|decline/i.test(b.textContent ?? ''))).toBeFalse();
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
