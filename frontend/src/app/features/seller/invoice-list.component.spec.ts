import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { InvoiceApiError, InvoiceApiService } from './invoice-api.service';
import { InvoiceListComponent } from './invoice-list.component';
import { pageOf, summaryFixture } from './invoice-testing';
import { InvoicePage } from './invoice.models';

describe('InvoiceListComponent', () => {
  let fixture: ComponentFixture<InvoiceListComponent>;
  let el: HTMLElement;
  let list: jasmine.Spy<(page: number, status: string | null) => Promise<InvoicePage>>;

  function create(inputs: { page?: string; status?: string } = {}): void {
    TestBed.configureTestingModule({
      imports: [InvoiceListComponent],
      providers: [
        provideRouter([]),
        { provide: InvoiceApiService, useValue: { list } },
        { provide: AuthService, useValue: { user: signal({ name: 'Asha Rao', role: 'SELLER' }) } },
      ],
    });
    fixture = TestBed.createComponent(InvoiceListComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    el = fixture.nativeElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function text(): string {
    return el.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  function button(name: string): HTMLButtonElement {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;
  }

  beforeEach(() => {
    list = jasmine.createSpy('list');
  });

  it('shows a loading state, then the seller’s invoices with status badges and links', async () => {
    let resolve!: (page: InvoicePage) => void;
    list.and.returnValue(new Promise((r) => (resolve = r)));
    create();
    await settle();
    expect(text()).toContain('Loading invoices…');
    expect(button('Refresh').disabled).toBeTrue();

    resolve(
      pageOf([
        summaryFixture(),
        summaryFixture({ id: 'id-2', invoiceNumber: 'INV-2', buyerName: 'Nila Foods', status: 'FINANCING_AVAILABLE' }),
      ]),
    );
    await settle();

    expect(list).toHaveBeenCalledOnceWith(0, null);
    const headers = Array.from(el.querySelectorAll('th')).map((th) => th.textContent?.trim());
    expect(headers).toEqual(['Invoice', 'Buyer', 'Amount', 'Due date', 'Status', 'Created']);
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/seller/invoices/a0000000-0000-4000-8000-000000000001');
    expect(rows[0].textContent).toContain('₹1,25,000.50');
    expect(rows[0].textContent).toContain('19 Dec 2026');
    expect(rows[0].querySelector('.badge')?.textContent?.trim()).toBe('Draft');
    expect(rows[1].querySelector('.badge-primary')?.textContent?.trim()).toBe('Open for financing');
    // Narrow screens show each cell's column name from data-label.
    expect(rows[0].querySelector('td')?.getAttribute('data-label')).toBe('Invoice');
    expect(text()).toContain('Showing 1–2 of 2 invoices');
    expect(button('Refresh').disabled).toBeFalse();
  });

  it('invites the seller to add a first invoice when there are none', async () => {
    list.and.resolveTo(pageOf([]));
    create();
    await settle();

    expect(el.querySelector('h2')?.textContent).toContain('You have no invoices yet');
    const link = Array.from(el.querySelectorAll('a')).find((a) => a.textContent?.includes('Add your first invoice'));
    expect(link?.getAttribute('href')).toBe('/seller/invoices/new');
    expect(el.querySelector('table')).toBeNull();
  });

  it('explains an empty filtered list and offers to clear the filter', async () => {
    list.and.resolveTo(pageOf([]));
    create({ status: 'REJECTED' });
    await settle();

    expect(list).toHaveBeenCalledOnceWith(0, 'REJECTED');
    expect(el.querySelector('h2')?.textContent).toContain('No rejected invoices');
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    button('Show all invoices').click();
    expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: { status: null, page: null } }));
  });

  it('ignores an unknown status in the address', async () => {
    list.and.resolveTo(pageOf([]));
    create({ status: 'ADMIN', page: 'abc' });
    await settle();
    expect(list).toHaveBeenCalledOnceWith(0, null);
  });

  it('reports a failure and loads again on retry', async () => {
    list.and.rejectWith(new InvoiceApiError({ kind: 'network' }));
    create();
    await settle();

    const alert = el.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Your invoices could not be loaded.');
    expect(alert?.textContent).toContain('could not be reached');

    list.and.resolveTo(pageOf([summaryFixture()]));
    button('Try again').click();
    await settle();
    expect(list).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
  });

  it('pages through the list with one-based page numbers in the address', async () => {
    list.and.resolveTo(pageOf([summaryFixture()], { page: 1, totalElements: 41, totalPages: 3 }));
    create({ page: '2' });
    await settle();

    expect(list).toHaveBeenCalledOnceWith(1, null);
    expect(text()).toContain('Page 2 of 3');
    expect(text()).toContain('Showing 21–21 of 41 invoices');
    const links = Array.from(el.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Invoice pages"] a'));
    expect(links.map((a) => a.textContent?.trim())).toEqual(['Previous', 'Next']);
    expect(links[0].getAttribute('href')).toBe('/');
    expect(links[1].getAttribute('href')).toBe('/?page=3');
  });

  it('filters by status from a labelled select', async () => {
    list.and.resolveTo(pageOf([summaryFixture()]));
    create();
    await settle();

    const select = el.querySelector<HTMLSelectElement>('#inv-status-filter')!;
    expect(el.querySelector('label[for="inv-status-filter"]')?.textContent).toBe('Status');
    expect(select.options.length).toBe(10);
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    select.value = 'VERIFIED';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: { status: 'VERIFIED', page: null } }));
  });
});
