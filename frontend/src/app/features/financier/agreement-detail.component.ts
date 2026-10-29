import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { AccessProblemComponent } from './access-problem.component';
import { shortId } from './agreement-list.component';
import { DisburseKeysService } from './disburse-keys.service';
import { FinancierBadgeComponent } from './financier-badge.component';
import { FinancierNavComponent } from './financier-nav.component';
import { failureMessage, formatAmount, formatDate, formatInstant, formatRate } from './financier-format';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import {
  AGREEMENT_STATUS_INFO,
  Agreement,
  AgreementStatus,
  Disbursement,
  FINANCIER_ACTION_FROM,
  FinancierAction,
  INSTALLMENT_STATUS_INFO,
  Installment,
} from './financier.models';

interface Notice {
  tone: 'success' | 'warning' | 'danger';
  message: string;
}

type ViewState = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'error'; failure: FinancingFailure };

type ScheduleState =
  | { kind: 'none' }
  | { kind: 'loading' }
  | { kind: 'loaded'; installments: Installment[]; disbursement: Disbursement | null }
  | { kind: 'error'; message: string };

/** Statuses that come after disbursement, when a schedule and a disbursement record exist. */
const DISBURSED_STATUSES: ReadonlySet<AgreementStatus> = new Set(['DISBURSED', 'REPAYMENT_IN_PROGRESS', 'SETTLED']);

/** Failures after which the agreement may have changed: show the latest version. */
const RELOAD_AFTER: ReadonlySet<FinancingFailure['kind']> = new Set([
  'invalid-transition',
  'concurrent',
  'conflict',
  'stale',
  'rule',
]);

/** Failures where the request's outcome is unknown: the disbursement may have happened. */
const OUTCOME_UNKNOWN: ReadonlySet<FinancingFailure['kind']> = new Set(['network', 'unavailable', 'unexpected']);

@Component({
  selector: 'ifp-agreement-detail',
  imports: [RouterLink, AccessProblemComponent, FinancierBadgeComponent, FinancierNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agreement-detail.component.html',
  styleUrl: './financier-detail.scss',
})
export class AgreementDetailComponent {
  private readonly api = inject(FinancingApiService);
  private readonly keys = inject(DisburseKeysService);
  private readonly injector = inject(Injector);

  /** Route parameter, bound by the router. */
  readonly id = input.required<string>();

  protected readonly statusInfo = AGREEMENT_STATUS_INFO;
  protected readonly installmentInfo = INSTALLMENT_STATUS_INFO;
  protected readonly formatAmount = formatAmount;
  protected readonly formatDate = formatDate;
  protected readonly formatInstant = formatInstant;
  protected readonly formatRate = formatRate;
  protected readonly formatFailure = failureMessage;
  protected readonly shortId = shortId;

  protected readonly view = signal<ViewState>({ kind: 'loading' });
  protected readonly agreement = signal<Agreement | null>(null);
  protected readonly schedule = signal<ScheduleState>({ kind: 'none' });
  protected readonly acting = signal<FinancierAction | null>(null);
  protected readonly confirmingDisburse = signal(false);
  protected readonly notice = signal<Notice | null>(null);

  protected readonly loadFailure = computed(() => {
    const view = this.view();
    return view.kind === 'error' ? view.failure : null;
  });

  protected readonly accessProblem = computed(() => {
    const kind = this.loadFailure()?.kind;
    return kind === 'forbidden' || kind === 'unauthenticated' ? kind : null;
  });

  protected readonly installments = computed(() => {
    const state = this.schedule();
    return state.kind === 'loaded' ? state.installments : null;
  });

  protected readonly disbursement = computed(() => {
    const state = this.schedule();
    return state.kind === 'loaded' ? state.disbursement : null;
  });

  protected readonly scheduleError = computed(() => {
    const state = this.schedule();
    return state.kind === 'error' ? state.message : null;
  });

  protected readonly milestones = computed(() => {
    const agreement = this.agreement();
    if (!agreement) {
      return [];
    }
    const steps: [string, string | null][] = [
      ['Offer accepted', agreement.createdAt],
      ['Approved', agreement.approvedAt],
      ['Disbursed', agreement.disbursedAt],
      ['Settled', agreement.settledAt],
    ];
    return steps.filter((step): step is [string, string] => step[1] !== null);
  });

  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private readonly confirmButton = viewChild<ElementRef<HTMLButtonElement>>('confirmButton');
  private request = 0;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => void this.load(id));
    });
  }

  protected canRun(action: FinancierAction): boolean {
    return this.agreement()?.status === FINANCIER_ACTION_FROM[action] && this.acting() === null;
  }

  protected refresh(): void {
    this.notice.set(null);
    void this.load(this.id());
  }

  protected reloadSchedule(): void {
    const agreement = this.agreement();
    if (agreement) {
      void this.loadSchedule(agreement, this.request);
    }
  }

  protected async approve(): Promise<void> {
    const agreement = this.agreement();
    if (!agreement || !this.canRun('approve')) {
      return;
    }
    this.acting.set('approve');
    this.notice.set(null);
    try {
      this.agreement.set(await this.api.approve(agreement.id));
      this.showNotice({ tone: 'success', message: 'Agreement approved. You can now disburse the advance.' });
      // The approve answer is the summary form; the full view comes from a reload.
      await this.load(agreement.id, { keepNotice: true });
    } catch (error) {
      await this.handleActionFailure(error, agreement.id);
    } finally {
      this.acting.set(null);
    }
  }

  protected askDisburse(): void {
    if (!this.canRun('disburse')) {
      return;
    }
    this.notice.set(null);
    this.confirmingDisburse.set(true);
    afterNextRender(() => this.confirmButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected cancelDisburse(): void {
    this.confirmingDisburse.set(false);
  }

  protected async disburse(): Promise<void> {
    const agreement = this.agreement();
    if (!agreement || !this.canRun('disburse')) {
      return;
    }
    // The same key for every attempt at this agreement's disbursement, until financing-service confirms it.
    const key = this.keys.keyFor(agreement.id);
    this.acting.set('disburse');
    this.notice.set(null);
    try {
      const { disbursement, replayed } = await this.api.disburse(agreement.id, key);
      this.keys.forget(agreement.id);
      this.confirmingDisburse.set(false);
      this.showNotice({
        tone: 'success',
        message: replayed
          ? `This advance had already been disbursed (reference ${disbursement.reference}). Nothing was paid twice.`
          : `Advance of ${formatAmount(disbursement.amount, disbursement.currency)} disbursed (simulated). Reference ${disbursement.reference}.`,
      });
      await this.load(agreement.id, { keepNotice: true });
    } catch (error) {
      this.confirmingDisburse.set(false);
      await this.handleActionFailure(error, agreement.id);
    } finally {
      this.acting.set(null);
    }
  }

  private async handleActionFailure(error: unknown, id: string): Promise<void> {
    const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
    if (failure.kind === 'not-found') {
      this.agreement.set(null);
      this.view.set({ kind: 'error', failure });
      return;
    }
    if (OUTCOME_UNKNOWN.has(failure.kind)) {
      this.showNotice({
        tone: 'warning',
        message: `${failureMessage(failure)} The latest state is shown below. Retrying is safe: the same request is sent again and cannot pay out twice.`,
      });
      await this.load(id, { keepNotice: true });
      return;
    }
    const reload = RELOAD_AFTER.has(failure.kind);
    this.showNotice({ tone: reload ? 'warning' : 'danger', message: failureMessage(failure) });
    if (reload) {
      await this.load(id, { keepNotice: true });
    }
  }

  private async load(id: string, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    if (this.agreement()?.id !== id) {
      this.agreement.set(null);
      this.view.set({ kind: 'loading' });
    }
    try {
      const agreement = await this.api.agreement(id);
      if (request !== this.request) {
        return;
      }
      this.agreement.set(agreement);
      this.view.set({ kind: 'loaded' });
      if (agreement.status !== 'APPROVED') {
        // Disbursed, or never going to be: a stored key would only matter for a retry of this agreement's payout.
        this.keys.forget(agreement.id);
        this.confirmingDisburse.set(false);
      }
      await this.loadSchedule(agreement, request);
    } catch (error) {
      if (request !== this.request) {
        return;
      }
      const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
      if (failure.kind === 'not-found' || !this.agreement()) {
        this.agreement.set(null);
        this.view.set({ kind: 'error', failure });
      } else if (!options.keepNotice) {
        this.showNotice({ tone: 'danger', message: `The latest details could not be loaded. ${failureMessage(failure)}` });
      }
    }
  }

  private async loadSchedule(agreement: Agreement, request: number): Promise<void> {
    if (!DISBURSED_STATUSES.has(agreement.status)) {
      this.schedule.set({ kind: 'none' });
      return;
    }
    if (this.schedule().kind !== 'loaded') {
      this.schedule.set({ kind: 'loading' });
    }
    try {
      const [installments, disbursement] = await Promise.all([
        this.api.schedule(agreement.id),
        this.api.disbursement(agreement.id),
      ]);
      if (request === this.request) {
        this.schedule.set({ kind: 'loaded', installments, disbursement });
      }
    } catch (error) {
      if (request === this.request) {
        const failure: FinancingFailure = error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
        this.schedule.set({ kind: 'error', message: failureMessage(failure) });
      }
    }
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }
}
