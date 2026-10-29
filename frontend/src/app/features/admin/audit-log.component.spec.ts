import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { ApiError, ApiFailure } from '../../shared/api-failure';
import { auditFixture, pageOf } from './admin-testing';
import { AuditApiService } from './audit-api.service';
import { AuditLogComponent, isoToLocalInput, localInputToIso } from './audit-log.component';

describe('AuditLogComponent', () => {
  let fixture: ComponentFixture<AuditLogComponent>;
  let el: HTMLElement;
  let search: jasmine.Spy;
  let navigate: jasmine.Spy;

  async function open(inputs: Record<string, string> = {}): Promise<void> {
    TestBed.configureTestingModule({
      imports: [AuditLogComponent],
      providers: [provideRouter([]), { provide: AuditApiService, useValue: { search } }],
    });
    navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(AuditLogComponent);
    for (const [name, value] of Object.entries(inputs)) {
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

  function fill(id: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  const submit = () => el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

  beforeEach(() => {
    search = jasmine.createSpy('search').and.resolveTo(pageOf([auditFixture()]));
  });

  it('lists events newest first with their details, and changes nothing', async () => {
    await open();

    expect(search).toHaveBeenCalledOnceWith(
      { eventType: null, aggregateType: null, aggregateId: null, actorId: null, from: null, to: null },
      0,
    );
    const event = el.querySelector('.event')!;
    expect(event.textContent).toContain('InvoiceSubmitted');
    expect(event.textContent).toContain('Invoice a0000000-0000-4000-8000-000000000001');
    expect(event.textContent).toContain('invoice-service');
    expect(event.querySelector('pre')?.textContent).toContain('"invoiceNumber": "INV-2026/014"');
    expect(el.textContent).toContain('Showing 1–1 of 1 event');
    const labels = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(labels).toEqual(['Refresh', 'Apply filters', 'Clear filters']);
  });

  it('applies filters from the address bar and drops values audit-service would refuse', async () => {
    await open({ eventType: 'InvoiceVerified', aggregateId: 'bad id!', actorId: '7D4C2F9E-0000-4000-8000-000000000001', page: '2' });
    expect(search).toHaveBeenCalledOnceWith(
      {
        eventType: 'InvoiceVerified',
        aggregateType: null,
        aggregateId: null,
        actorId: '7d4c2f9e-0000-4000-8000-000000000001',
        from: null,
        to: null,
      },
      1,
    );
    expect(el.querySelector<HTMLInputElement>('#audit-eventType')?.value).toBe('InvoiceVerified');
  });

  it('validates filters in the browser before searching', async () => {
    await open();
    fill('audit-eventType', 'Invoice Submitted');
    fill('audit-actorId', 'not-a-uuid');
    fill('audit-from', '2026-10-28T10:00');
    fill('audit-to', '2026-10-28T09:00');
    submit();
    await settle();

    expect(navigate).not.toHaveBeenCalled();
    expect(el.querySelector('#audit-eventType-error')?.textContent).toContain('InvoiceSubmitted');
    expect(el.querySelector('#audit-actorId-error')).not.toBeNull();
    expect(el.querySelector('#audit-to-error')?.textContent).toContain('after the start');
    expect(el.querySelector('#audit-eventType')?.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement?.id).toBe('audit-eventType');
  });

  it('puts valid filters in the address bar as ISO instants', async () => {
    await open();
    fill('audit-eventType', ' InvoiceVerified ');
    fill('audit-aggregateType', 'Invoice');
    fill('audit-from', '2026-10-28T09:00');
    submit();
    await settle();

    expect(navigate).toHaveBeenCalledOnceWith([], {
      queryParams: {
        eventType: 'InvoiceVerified',
        aggregateType: 'Invoice',
        aggregateId: null,
        actorId: null,
        from: new Date('2026-10-28T09:00').toISOString(),
        to: null,
        page: null,
      },
    });
  });

  it('distinguishes an empty log from a filter with no matches', async () => {
    search.and.resolveTo(pageOf([]));
    await open();
    expect(el.textContent).toContain('No events recorded yet');

    TestBed.resetTestingModule();
    search.and.resolveTo(pageOf([]));
    await open({ eventType: 'InvoiceVerified' });
    expect(el.textContent).toContain('No events match these filters');
  });

  const failures: [ApiFailure, string][] = [
    [{ kind: 'forbidden' }, 'You do not have permission'],
    [{ kind: 'invalid' }, 'Some filters were not accepted'],
    [{ kind: 'conflict', code: 'UNEXPECTED', currentStatus: null }, 'Refresh and try again'],
    [{ kind: 'stale' }, 'Refresh and try again'],
    [{ kind: 'unavailable' }, 'audit service is temporarily unavailable'],
  ];
  for (const [failure, text] of failures) {
    it(`shows a safe message for a ${failure.kind} failure and offers a retry`, async () => {
      search.and.rejectWith(new ApiError(failure));
      await open();
      expect(el.querySelector('[role="alert"]')?.textContent).toContain(text);

      search.and.resolveTo(pageOf([auditFixture()]));
      Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
      await settle();
      expect(el.querySelector('.event')).not.toBeNull();
    });
  }

  it('converts between datetime-local values and ISO instants', () => {
    const iso = localInputToIso('2026-10-28T09:30');
    expect(iso).toBe(new Date('2026-10-28T09:30').toISOString());
    expect(isoToLocalInput(iso)).toBe('2026-10-28T09:30');
    expect(localInputToIso('yesterday')).toBeNull();
    expect(isoToLocalInput(null)).toBe('');
  });
});
