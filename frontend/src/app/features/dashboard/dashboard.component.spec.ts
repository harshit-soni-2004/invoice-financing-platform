import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SessionUser } from '../../core/auth/id-token';
import { NotificationBadgeService } from '../notifications/notification-badge.service';
import { DashboardComponent } from './dashboard.component';
import { POTENTIAL_BUYERS } from './potential-buyers';

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let el: HTMLElement;
  const count = signal<number | null>(null);

  function open(user: SessionUser): void {
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideRouter([]), { provide: NotificationBadgeService, useValue: { count } }],
    });
    fixture = TestBed.createComponent(DashboardComponent);
    fixture.componentRef.setInput('user', user);
    fixture.detectChanges();
    el = fixture.nativeElement;
  }

  const actionLinks = () =>
    Array.from(el.querySelectorAll<HTMLAnchorElement>('.action')).map((a) => [
      a.querySelector('.action-title')?.textContent?.trim(),
      a.getAttribute('href'),
    ]);
  const buyerRows = () => el.querySelectorAll('tbody tr');

  beforeEach(() => count.set(null));

  it('greets the user by name and shows seller shortcuts', () => {
    open({ id: 'u-1', name: 'Mintu', role: 'SELLER' });

    expect(el.querySelector('h1')?.textContent).toMatch(/^Good (morning|afternoon|evening), Mintu$/);
    expect(el.querySelector('.role-chip')?.textContent?.trim()).toBe('Seller');
    expect(actionLinks()).toContain(['New invoice', '/seller/invoices/new']);
    expect(actionLinks()).toContain(['Your invoices', '/seller']);
  });

  it('shows the shortcuts that match the role', () => {
    open({ id: 'u-2', name: 'Ravi', role: 'FINANCIER' });
    expect(actionLinks()).toContain(['Opportunities', '/financier']);
    expect(actionLinks()).not.toContain(['New invoice', '/seller/invoices/new']);

    TestBed.resetTestingModule();
    open({ id: 'u-3', name: 'Admin', role: 'ADMIN' });
    expect(actionLinks()).toContain(['Financier approvals', '/admin/financiers']);
  });

  it('reports the unread notification count', () => {
    open({ id: 'u-1', name: 'Mintu', role: 'SELLER' });
    expect(el.querySelector('.unread')?.textContent?.trim()).toBe('View notifications');

    count.set(3);
    fixture.detectChanges();
    expect(el.querySelector('.unread')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('3 unread notifications');

    count.set(0);
    fixture.detectChanges();
    expect(el.querySelector('.unread')?.textContent?.trim()).toBe("You're all caught up");
  });

  it('lists the sample buyers, labelled as sample data, and filters them', () => {
    open({ id: 'u-1', name: 'Mintu', role: 'SELLER' });
    expect(el.querySelector('#buyers-heading')?.textContent).toContain('Sample data');
    expect(buyerRows().length).toBe(POTENTIAL_BUYERS.length);

    const search = el.querySelector<HTMLInputElement>('#buyer-search')!;
    search.value = 'pune';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(buyerRows().length).toBe(1);
    expect(buyerRows()[0].textContent).toContain('Sahyadri Auto Components Ltd');

    search.value = 'no such buyer';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(buyerRows().length).toBe(0);
    expect(el.querySelector('[role="status"]')?.textContent).toContain('No buyers match your search.');

    search.value = '';
    search.dispatchEvent(new Event('input'));
    const industry = el.querySelector<HTMLSelectElement>('#buyer-industry')!;
    industry.value = 'Retail';
    industry.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(buyerRows().length).toBe(POTENTIAL_BUYERS.filter((b) => b.industry === 'Retail').length);
  });
});
