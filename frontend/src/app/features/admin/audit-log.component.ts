import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Page, commonFailureMessage, failureOf } from '../../shared/api-failure';
import { formatInstant } from '../seller/invoice-format';
import { AdminNavComponent } from './admin-nav.component';
import { pageLink } from './admin-shared';
import {
  AUDIT_ID_PATTERN,
  AUDIT_MAX_PAGE,
  AUDIT_NAME_PATTERN,
  AuditApiService,
  AuditEvent,
  AuditFilter,
  UUID_PATTERN,
} from './audit-api.service';

type ListState = { kind: 'loading' } | { kind: 'loaded'; result: Page<AuditEvent> } | { kind: 'error'; message: string };

type FilterField = keyof AuditFilter;

const FIELD_ORDER: readonly FilterField[] = ['eventType', 'aggregateType', 'aggregateId', 'actorId', 'from', 'to'];

/** Reads `datetime-local` input (local wall-clock time) as an ISO instant. */
export function localInputToIso(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value)) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** The `datetime-local` value showing an ISO instant in local time. */
export function isoToLocalInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function validInstant(value: string | undefined): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return /^\d{4}-\d{2}-\d{2}T/.test(value) && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
}

/** Filters from the address bar; values audit-service would refuse are dropped rather than sent. */
function filterFromParams(params: Partial<Record<FilterField, string>>): AuditFilter {
  const matching = (value: string | undefined, pattern: RegExp) => (value && pattern.test(value) ? value : null);
  return {
    eventType: matching(params.eventType, AUDIT_NAME_PATTERN),
    aggregateType: matching(params.aggregateType, AUDIT_NAME_PATTERN),
    aggregateId: matching(params.aggregateId, AUDIT_ID_PATTERN),
    actorId: matching(params.actorId, UUID_PATTERN)?.toLowerCase() ?? null,
    from: validInstant(params.from),
    to: validInstant(params.to),
  };
}

function auditPageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 && page <= AUDIT_MAX_PAGE + 1 ? page - 1 : 0;
}

/** audit-service's trail of platform events, newest first. Read-only: nothing on this page changes data. */
@Component({
  selector: 'ifp-audit-log',
  imports: [RouterLink, ReactiveFormsModule, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './audit-log.component.html',
  styleUrls: ['./admin.scss', './audit-log.component.scss'],
})
export class AuditLogComponent {
  private readonly api = inject(AuditApiService);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Query parameters, bound by the router. */
  readonly page = input<string>();
  readonly eventType = input<string>();
  readonly aggregateType = input<string>();
  readonly aggregateId = input<string>();
  readonly actorId = input<string>();
  readonly from = input<string>();
  readonly to = input<string>();

  protected readonly formatInstant = formatInstant;
  protected readonly pageLink = pageLink;

  protected readonly form = new FormGroup({
    eventType: new FormControl('', { nonNullable: true }),
    aggregateType: new FormControl('', { nonNullable: true }),
    aggregateId: new FormControl('', { nonNullable: true }),
    actorId: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly pageIndex = computed(() => auditPageParam(this.page()));
  protected readonly filter = computed(() =>
    filterFromParams({
      eventType: this.eventType(),
      aggregateType: this.aggregateType(),
      aggregateId: this.aggregateId(),
      actorId: this.actorId(),
      from: this.from(),
      to: this.to(),
    }),
  );
  protected readonly filtered = computed(() => Object.values(this.filter()).some((value) => value !== null));
  protected readonly fieldErrors = signal<Partial<Record<FilterField, string>>>({});
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly loading = computed(() => this.state().kind === 'loading');

  protected readonly result = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.result : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });
  protected readonly range = computed(() => {
    const result = this.result();
    if (!result || result.content.length === 0) {
      return null;
    }
    const { page, size, totalElements, content } = result;
    return { from: page * size + 1, to: page * size + content.length, total: totalElements };
  });
  protected readonly hasNext = computed(() => {
    const result = this.result();
    return !!result && result.page + 1 < result.totalPages && result.page + 1 <= AUDIT_MAX_PAGE;
  });

  private request = 0;

  constructor() {
    effect(() => {
      const filter = this.filter();
      const page = this.pageIndex();
      untracked(() => {
        this.form.setValue({
          eventType: filter.eventType ?? '',
          aggregateType: filter.aggregateType ?? '',
          aggregateId: filter.aggregateId ?? '',
          actorId: filter.actorId ?? '',
          from: isoToLocalInput(filter.from),
          to: isoToLocalInput(filter.to),
        });
        void this.load(filter, page);
      });
    });
  }

  protected error(field: FilterField): string | null {
    return this.fieldErrors()[field] ?? null;
  }

  protected describedBy(field: FilterField, hint: string): string {
    return this.error(field) ? `audit-${field}-error ${hint}` : hint;
  }

  protected apply(): void {
    const raw = this.form.getRawValue();
    const errors: Partial<Record<FilterField, string>> = {};
    const trimmed = {
      eventType: raw.eventType.trim(),
      aggregateType: raw.aggregateType.trim(),
      aggregateId: raw.aggregateId.trim(),
      actorId: raw.actorId.trim(),
    };
    if (trimmed.eventType && !AUDIT_NAME_PATTERN.test(trimmed.eventType)) {
      errors.eventType = 'Use the event name as recorded, for example InvoiceSubmitted.';
    }
    if (trimmed.aggregateType && !AUDIT_NAME_PATTERN.test(trimmed.aggregateType)) {
      errors.aggregateType = 'Use the record type as recorded, for example Invoice.';
    }
    if (trimmed.aggregateId && !AUDIT_ID_PATTERN.test(trimmed.aggregateId)) {
      errors.aggregateId = 'Use up to 64 letters, digits and hyphens.';
    }
    if (trimmed.actorId && !UUID_PATTERN.test(trimmed.actorId)) {
      errors.actorId = 'Enter a user ID such as 7d4c2f9e-0000-4000-8000-000000000001.';
    }
    const from = raw.from ? localInputToIso(raw.from) : null;
    const to = raw.to ? localInputToIso(raw.to) : null;
    if (raw.from && !from) {
      errors.from = 'Enter a valid date and time.';
    }
    if (raw.to && !to) {
      errors.to = 'Enter a valid date and time.';
    }
    if (from && to && from >= to) {
      errors.to = 'The end must be after the start.';
    }
    this.fieldErrors.set(errors);
    const firstInvalid = FIELD_ORDER.find((field) => errors[field]);
    if (firstInvalid) {
      setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(`#audit-${firstInvalid}`)?.focus());
      return;
    }
    void this.router.navigate([], {
      queryParams: {
        eventType: trimmed.eventType || null,
        aggregateType: trimmed.aggregateType || null,
        aggregateId: trimmed.aggregateId || null,
        actorId: trimmed.actorId.toLowerCase() || null,
        from,
        to,
        page: null,
      },
    });
  }

  protected clear(): void {
    this.fieldErrors.set({});
    if (!this.filtered() && this.pageIndex() === 0) {
      this.form.reset();
      return;
    }
    void this.router.navigate([], { queryParams: {} });
  }

  protected refresh(): void {
    void this.load(this.filter(), this.pageIndex());
  }

  protected payloadText(event: AuditEvent): string {
    return event.payload === null ? 'No payload' : JSON.stringify(event.payload, null, 2);
  }

  private async load(filter: AuditFilter, page: number): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.search(filter, page);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        const last = Math.min(result.totalPages, AUDIT_MAX_PAGE + 1) - 1;
        void this.router.navigate([], { queryParams: pageLink(last), queryParamsHandling: 'merge' });
        return;
      }
      this.state.set({ kind: 'loaded', result });
    } catch (error) {
      if (request === this.request) {
        const failure = failureOf(error);
        this.state.set({
          kind: 'error',
          message:
            failure.kind === 'invalid'
              ? 'Some filters were not accepted. Check them and try again.'
              : commonFailureMessage(failure, 'audit service'),
        });
      }
    }
  }
}
