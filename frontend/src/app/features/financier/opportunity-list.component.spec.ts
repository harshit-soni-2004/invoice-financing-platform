import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import { opportunityFixture, pageOf } from './financier-testing';
import { Opportunity, Page } from './financier.models';
import { OpportunityListComponent } from './opportunity-list.component';

describe('OpportunityListComponent', () => {
  let fixture: ComponentFixture<OpportunityListComponent>;
  let el: HTMLElement;
  let opportunities: jasmine.Spy;

  async function open(first: Page<Opportunity> | FinancingFailure, query: Record<string, string> = {}): Promise<void> {
    opportunities = jasmine.createSpy('opportunities');
    if ('content' in first) {
      opportunities.and.resolveTo(first);
    } else {
      opportunities.and.rejectWith(new FinancingApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [OpportunityListComponent],
      providers: [provideRouter([]), { provide: FinancingApiService, useValue: { opportunities } }],
    });
    fixture = TestBed.createComponent(OpportunityListComponent);
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

  it('lists open invoices soonest due first, linking to each opportunity', async () => {
    await open(pageOf([opportunityFixture()]));

    expect(opportunities).toHaveBeenCalledOnceWith(0, { field: 'dueDate', direction: 'asc' });
    const row = el.querySelector('tbody tr')!;
    expect(row.textContent).toContain('Kaveri Textiles Pvt Ltd');
    expect(row.textContent).toContain('₹5,00,000.00');
    expect(row.textContent).toContain('₹4,90,000.00');
    expect(row.querySelector('a')?.getAttribute('href')).toBe('/financier/opportunities/a0000000-0000-4000-8000-000000000001');
    expect(el.textContent).toContain('Showing 1–1 of 1 invoice');
    expect(el.querySelector('nav[aria-label="Financier sections"] a[aria-current="page"]')?.textContent?.trim()).toBe('Opportunities');
  });

  it('reads the sort and one-based page from the URL', async () => {
    await open(pageOf([opportunityFixture()], { page: 1, totalElements: 21, totalPages: 2 }), { sort: 'amount,desc', page: '2' });
    expect(opportunities).toHaveBeenCalledOnceWith(1, { field: 'amount', direction: 'desc' });
    expect(el.querySelector<HTMLSelectElement>('#opp-sort')!.value).toBe('amount,desc');
    expect(el.textContent).toContain('Page 2 of 2');
  });

  it('ignores a sort financing-service does not support', async () => {
    await open(pageOf([]), { sort: 'buyerName,asc' });
    expect(opportunities).toHaveBeenCalledOnceWith(0, { field: 'dueDate', direction: 'asc' });
  });

  it('changes the sort through the URL and returns to the first page', async () => {
    await open(pageOf([opportunityFixture()]));
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const select = el.querySelector<HTMLSelectElement>('#opp-sort')!;
    select.value = 'invoiceNumber,asc';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { sort: 'invoiceNumber,asc', page: null },
      queryParamsHandling: 'merge',
    });
  });

  it('explains an empty list', async () => {
    await open(pageOf([]));
    expect(el.querySelector('h2')?.textContent).toBe('No invoices are open for financing right now');
  });

  it('offers a retry after a failure', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('temporarily unavailable');

    opportunities.and.resolveTo(pageOf([opportunityFixture()]));
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
    await settle();
    expect(el.querySelector('tbody tr')).not.toBeNull();
  });

  it('explains a financier account that financing-service does not accept', async () => {
    await open({ kind: 'forbidden' });
    expect(el.querySelector('h2')?.textContent).toBe('Your financier account is not active');
    expect(el.querySelector('#opp-sort')).toBeNull();
  });
});
