import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { InvoiceDetailComponent } from './invoice-detail.component';
import { versioned } from './invoice-testing';
import { VersionedInvoice } from './invoice.models';

const ID = 'a0000000-0000-4000-8000-000000000001';

describe('InvoiceDetailComponent', () => {
  let fixture: ComponentFixture<InvoiceDetailComponent>;
  let el: HTMLElement;
  let get: jasmine.Spy;
  let transition: jasmine.Spy;

  async function open(first: VersionedInvoice | InvoiceFailure): Promise<void> {
    get = jasmine.createSpy('get');
    transition = jasmine.createSpy('transition');
    if ('invoice' in first) {
      get.and.resolveTo(first);
    } else {
      get.and.rejectWith(new InvoiceApiError(first));
    }
    TestBed.configureTestingModule({
      imports: [InvoiceDetailComponent],
      providers: [provideRouter([]), { provide: InvoiceApiService, useValue: { get, transition } }],
    });
    fixture = TestBed.createComponent(InvoiceDetailComponent);
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

  const submitButton = () => button(/^\s*(Submit for verification|Submitting…)\s*$/);
  const listButton = () => button(/Make available to financiers|Opening for financing/);
  const badge = () => el.querySelector('.page-header .badge')?.textContent?.trim();
  const notice = () => el.querySelector<HTMLElement>('.notice');

  it('shows the invoice and only enables actions its status allows', async () => {
    await open(versioned());

    expect(get).toHaveBeenCalledOnceWith(ID);
    expect(el.querySelector('h1')?.textContent).toContain('INV-2026/014');
    expect(badge()).toBe('Draft');
    const details = el.querySelector('dl')!.textContent!;
    expect(details).toContain('Kaveri Textiles Pvt Ltd');
    expect(details).toContain('27AAPFU0939F1ZV');
    expect(details).toContain('₹1,25,000.50');
    expect(details).toContain('20 Oct 2026');
    expect(submitButton().disabled).toBeFalse();
    expect(submitButton().getAttribute('aria-describedby')).toBe('action-hint-submit');
    expect(el.querySelector('#action-hint-submit')?.textContent).toContain('For drafts');
    expect(listButton().disabled).toBeTrue();
  });

  for (const [status, submit, list] of [
    ['SUBMITTED', true, true],
    ['VERIFIED', true, false],
    ['FINANCING_AVAILABLE', true, true],
    ['REJECTED', true, true],
  ] as const) {
    it(`disables the actions that ${status} does not allow`, async () => {
      await open(versioned({ status, statusReason: status === 'REJECTED' ? 'Buyer denies the debt' : null }));
      expect(submitButton().disabled).toBe(submit);
      expect(listButton().disabled).toBe(list);
      if (status === 'REJECTED') {
        expect(el.textContent).toContain('Buyer denies the debt');
      }
    });
  }

  it('submits a draft with its ETag, shows the confirmed state and ignores repeated clicks', async () => {
    await open(versioned({ version: 2 }));
    let resolve!: (value: VersionedInvoice) => void;
    transition.and.returnValue(new Promise((r) => (resolve = r)));

    submitButton().click();
    await settle();
    expect(submitButton().textContent?.trim()).toBe('Submitting…');
    expect(submitButton().disabled).toBeTrue();
    submitButton().click();
    await settle();
    expect(transition).toHaveBeenCalledOnceWith('submit', ID, '"2"');
    // Nothing changes until invoice-service answers.
    expect(badge()).toBe('Draft');

    resolve(versioned({ status: 'SUBMITTED', version: 3, submittedAt: '2026-10-29T03:00:00Z' }));
    await settle();

    expect(badge()).toBe('Submitted');
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Invoice submitted for verification.');
    expect(document.activeElement).toBe(notice());
    expect(submitButton().disabled).toBeTrue();
    expect(el.querySelector('.milestones')?.textContent).toContain('Submitted');
  });

  it('opens a verified invoice for financing with the current ETag', async () => {
    await open(versioned({ status: 'VERIFIED', version: 5 }));
    transition.and.resolveTo(versioned({ status: 'FINANCING_AVAILABLE', version: 6 }));

    listButton().click();
    await settle();

    expect(transition).toHaveBeenCalledOnceWith('list-for-financing', ID, '"5"');
    expect(badge()).toBe('Open for financing');
  });

  it('shows the latest version instead of applying an action to stale data', async () => {
    await open(versioned({ version: 2 }));
    transition.and.rejectWith(new InvoiceApiError({ kind: 'stale' }));
    get.and.resolveTo(versioned({ status: 'CANCELLED', version: 3, statusReason: 'Order withdrawn' }));

    submitButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(2);
    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('changed since you opened it');
    expect(badge()).toBe('Cancelled');
    expect(submitButton().disabled).toBeTrue();
  });

  it('explains a refused transition with the status invoice-service reports, then reloads', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'invalid-transition', currentStatus: 'SUBMITTED' }));
    get.and.resolveTo(versioned({ status: 'SUBMITTED', version: 1 }));

    submitButton().click();
    await settle();

    expect(notice()?.textContent).toContain('This invoice is now submitted, so that action is no longer possible.');
    expect(badge()).toBe('Submitted');
  });

  it('keeps the invoice as it was when an action is refused for other reasons', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'forbidden' }));

    submitButton().click();
    await settle();

    expect(get).toHaveBeenCalledTimes(1);
    expect(notice()?.classList).toContain('alert-danger');
    expect(notice()?.textContent).toContain('You do not have permission to do this.');
    expect(badge()).toBe('Draft');
    expect(submitButton().disabled).toBeFalse();
  });

  it('shows invoice-service’s reason when a business rule blocks submission', async () => {
    await open(versioned());
    transition.and.rejectWith(
      new InvoiceApiError({ kind: 'rule', field: 'dueDate', message: 'Due date must be at least 7 days away' }),
    );

    submitButton().click();
    await settle();

    expect(notice()?.textContent).toContain('Due date must be at least 7 days away');
    expect(badge()).toBe('Draft');
  });

  it('says when the session has ended during an action', async () => {
    await open(versioned());
    transition.and.rejectWith(new InvoiceApiError({ kind: 'unauthenticated' }));
    submitButton().click();
    await settle();
    expect(notice()?.textContent).toContain('Your session has ended');
  });

  it('reports an invoice that does not exist or belongs to someone else', async () => {
    await open({ kind: 'not-found' });

    expect(el.querySelector('h1')?.textContent).toBe('Invoice not found');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('belongs to another account');
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Try again'))).toBeFalse();
  });

  it('offers a retry when the invoice cannot be loaded', async () => {
    await open({ kind: 'unavailable' });
    expect(el.querySelector('h1')?.textContent).toBe('The invoice could not be loaded');

    get.and.resolveTo(versioned());
    button(/Try again/).click();
    await settle();
    expect(badge()).toBe('Draft');
  });
});
