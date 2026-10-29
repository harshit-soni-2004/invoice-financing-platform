import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AgreementListComponent } from './agreement-list.component';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { agreementFixture, disbursedAgreement, pageOf } from './financier-testing';
import { Agreement, Page } from './financier.models';

describe('AgreementListComponent', () => {
  let fixture: ComponentFixture<AgreementListComponent>;
  let el: HTMLElement;
  let agreements: jasmine.Spy;

  async function open(first: Page<Agreement> | FinancingFailure, query: Record<string, string> = {}): Promise<void> {
    agreements = jasmine.createSpy('agreements');
    if ('content' in first) {
      agreements.and.resolveTo(first);
    } else {
      agreements.and.rejectWith(new FinancingApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [AgreementListComponent],
      providers: [provideRouter([]), { provide: FinancingApiService, useValue: { agreements } }],
    });
    fixture = TestBed.createComponent(AgreementListComponent);
    for (const [name, value] of Object.entries(query)) {
      fixture.componentRef.setInput(name, value);
    }
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('lists the financier’s agreements with status and outstanding amount', async () => {
    await open(pageOf([agreementFixture(), disbursedAgreement({ id: 'e0000000-0000-4000-8000-000000000002' })]));

    expect(agreements).toHaveBeenCalledOnceWith(0, null);
    const rows = el.querySelectorAll('tbody tr');
    expect(rows[0].textContent).toContain('Awaiting your approval');
    expect(rows[0].textContent).toContain('—');
    expect(rows[1].textContent).toContain('Disbursed');
    expect(rows[1].textContent).toContain('₹4,90,468.49');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/financier/agreements/e0000000-0000-4000-8000-000000000001');
    expect(rows[0].querySelector('a')?.textContent).toBe('E0000000');
  });

  it('filters by a status from the URL', async () => {
    await open(pageOf([]), { status: 'APPROVED' });
    expect(agreements).toHaveBeenCalledOnceWith(0, 'APPROVED');
    expect(el.querySelector('h2')?.textContent).toBe('No agreements with status “Approved”');
  });

  it('ignores an unknown status', async () => {
    await open(pageOf([]), { status: 'OVERDUE' });
    expect(agreements).toHaveBeenCalledOnceWith(0, null);
    expect(el.querySelector('h2')?.textContent).toBe('You have no agreements yet');
  });

  it('changes the filter through the URL', async () => {
    await open(pageOf([agreementFixture()]));
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const select = el.querySelector<HTMLSelectElement>('#agr-status-filter')!;
    select.value = 'PENDING_APPROVAL';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenCalledWith([], { queryParams: { status: 'PENDING_APPROVAL', page: null }, queryParamsHandling: 'merge' });
  });

  it('asks to sign in again when the session has ended', async () => {
    await open({ kind: 'unauthenticated' });
    expect(el.querySelector('h2')?.textContent).toBe('Your session has ended');
  });
});
