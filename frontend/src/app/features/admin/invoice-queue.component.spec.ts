import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { InvoiceApiError, InvoiceApiService } from '../seller/invoice-api.service';
import { pageOf, summaryFixture } from '../seller/invoice-testing';
import { InvoiceQueueComponent } from './invoice-queue.component';

describe('InvoiceQueueComponent', () => {
  let fixture: ComponentFixture<InvoiceQueueComponent>;
  let el: HTMLElement;
  let list: jasmine.Spy;

  async function open(stage?: string): Promise<void> {
    TestBed.configureTestingModule({
      imports: [InvoiceQueueComponent],
      providers: [provideRouter([]), { provide: InvoiceApiService, useValue: { list } }],
    });
    fixture = TestBed.createComponent(InvoiceQueueComponent);
    if (stage) {
      fixture.componentRef.setInput('stage', stage);
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

  beforeEach(() => {
    list = jasmine.createSpy('list');
  });

  it('lists submitted invoices awaiting buyer confirmation by default, linking to their review page', async () => {
    list.and.resolveTo(pageOf([summaryFixture({ status: 'SUBMITTED' })]));
    await open();

    expect(list).toHaveBeenCalledOnceWith(0, 'SUBMITTED');
    expect(el.querySelector('.toolbar h2')?.textContent).toBe('Awaiting buyer confirmation');
    expect(el.querySelector('[aria-current="true"]')?.textContent?.trim()).toBe('Awaiting buyer confirmation');
    const link = el.querySelector<HTMLAnchorElement>('tbody a')!;
    expect(link.textContent?.trim()).toBe('INV-2026/014');
    expect(link.getAttribute('href')).toBe('/admin/invoices/a0000000-0000-4000-8000-000000000001');
    expect(el.querySelector('tbody .badge')?.textContent?.trim()).toBe('Submitted');
    expect(el.textContent).toContain('Showing 1–1 of 1 invoice');
  });

  it('lists buyer-confirmed invoices in the verification stage', async () => {
    list.and.resolveTo(pageOf([summaryFixture({ status: 'BUYER_CONFIRMED' })]));
    await open('verification');
    expect(list).toHaveBeenCalledOnceWith(0, 'BUYER_CONFIRMED');
    expect(el.querySelector('tbody .badge')?.textContent?.trim()).toBe('Buyer confirmed');
  });

  it('shows an empty state for an empty stage', async () => {
    list.and.resolveTo(pageOf([]));
    await open('verification');
    expect(el.textContent).toContain('No buyer-confirmed invoices are waiting for verification.');
  });

  it('explains a 403 with a safe message and offers a retry after an outage', async () => {
    list.and.rejectWith(new InvoiceApiError({ kind: 'forbidden' }));
    await open();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('You do not have permission to do this.');

    list.and.rejectWith(new InvoiceApiError({ kind: 'unavailable' }));
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
    await settle();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('temporarily unavailable');

    list.and.resolveTo(pageOf([summaryFixture({ status: 'SUBMITTED' })]));
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
    await settle();
    expect(el.querySelector('tbody a')).not.toBeNull();
  });
});
