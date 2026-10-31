import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionUser } from '../../core/auth/id-token';
import { ROLE_LABELS } from '../../core/auth/role-labels';
import { NotificationBadgeService } from '../notifications/notification-badge.service';
import { BUYER_INDUSTRIES, CreditRating, POTENTIAL_BUYERS, formatCrore } from './potential-buyers';

interface QuickAction {
  path: string;
  title: string;
  description: string;
  icon: string;
}

const ACTIONS: Readonly<Record<SessionUser['role'], { intro: string; actions: QuickAction[] }>> = {
  SELLER: {
    intro: 'Add invoices, compare financiers’ offers and keep repayments on schedule.',
    actions: [
      { path: '/seller/invoices/new', title: 'New invoice', description: 'Record an invoice and submit it for verification.', icon: '＋' },
      { path: '/seller', title: 'Your invoices', description: 'Track status, review offers and record repayments.', icon: '▤' },
      { path: '/notifications', title: 'Notifications', description: 'Offers, verifications and repayment reminders.', icon: '◔' },
      { path: '/account', title: 'Account', description: 'Your profile as the sign-in service holds it.', icon: '◉' },
    ],
  },
  FINANCIER: {
    intro: 'Find listed invoices, make offers and follow repayments on your agreements.',
    actions: [
      { path: '/financier', title: 'Opportunities', description: 'Verified invoices that are open for offers.', icon: '◎' },
      { path: '/financier/agreements', title: 'Your agreements', description: 'Disbursements, schedules and overdue indicators.', icon: '▤' },
      { path: '/notifications', title: 'Notifications', description: 'Accepted offers and repayment activity.', icon: '◔' },
      { path: '/account', title: 'Account', description: 'Your profile as the sign-in service holds it.', icon: '◉' },
    ],
  },
  ADMIN: {
    intro: 'Verify invoices, approve financiers and review the audit trail.',
    actions: [
      { path: '/admin/invoices', title: 'Invoice queue', description: 'Record buyer confirmations and verify invoices.', icon: '▤' },
      { path: '/admin/financiers', title: 'Financier approvals', description: 'Approve or reject new financier accounts.', icon: '✓' },
      { path: '/admin/audit', title: 'Audit log', description: 'Every event, append-only and searchable.', icon: '☰' },
      { path: '/notifications', title: 'Notifications', description: 'Platform activity that needs your attention.', icon: '◔' },
    ],
  },
};

const RATING_BADGE: Readonly<Record<CreditRating, string>> = {
  AAA: 'badge-success',
  AA: 'badge-success',
  A: 'badge-info',
  BBB: 'badge-warning',
};

/** Home page for a signed-in user. */
@Component({
  selector: 'ifp-dashboard',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page dashboard">
      <header class="welcome">
        <div class="glow" aria-hidden="true"></div>
        <div class="welcome-text">
          <p class="eyebrow">{{ today }}</p>
          <h1>{{ greeting() }}, {{ user().name }}</h1>
          <p class="intro">{{ content().intro }}</p>
        </div>
        <div class="welcome-side">
          <span class="role-chip">{{ roleLabels[user().role] }}</span>
          <a class="unread" routerLink="/notifications">
            @if (notifications.count(); as count) {
              <strong>{{ count }}</strong> unread {{ count === 1 ? 'notification' : 'notifications' }}
            } @else if (notifications.count() === 0) {
              You're all caught up
            } @else {
              View notifications
            }
          </a>
        </div>
      </header>

      <section aria-labelledby="actions-heading">
        <h2 id="actions-heading" class="section-title">Quick actions</h2>
        <ul class="actions">
          @for (action of content().actions; track action.path) {
            <li>
              <a class="action card" [routerLink]="action.path">
                <span class="action-icon" aria-hidden="true">{{ action.icon }}</span>
                <span class="action-title">{{ action.title }}</span>
                <span class="action-desc">{{ action.description }}</span>
              </a>
            </li>
          }
        </ul>
      </section>

      <section class="card buyers" aria-labelledby="buyers-heading">
        <div class="buyers-head">
          <div>
            <h2 id="buyers-heading">
              Potential buyers <span class="badge badge-warning sample">Sample data</span>
            </h2>
            <p class="muted">
              Companies whose invoices are attractive to finance: strong credit and quick payment. Illustrative only, not
              real companies.
            </p>
          </div>
          <div class="filters">
            <label class="visually-hidden" for="buyer-search">Search buyers</label>
            <input
              id="buyer-search"
              class="input"
              type="search"
              placeholder="Search by name or city"
              [value]="query()"
              (input)="query.set(valueOf($event))"
            />
            <label class="visually-hidden" for="buyer-industry">Industry</label>
            <select
              id="buyer-industry"
              class="select"
              [value]="industry()"
              (change)="industry.set(valueOf($event))"
            >
              <option value="">All industries</option>
              @for (name of industries; track name) {
                <option [value]="name">{{ name }}</option>
              }
            </select>
          </div>
        </div>

        <div class="stats" role="list" aria-label="Buyer summary">
          <div class="stat" role="listitem">
            <span class="stat-value">{{ buyers().length }}</span>
            <span class="stat-label">buyers shown</span>
          </div>
          <div class="stat" role="listitem">
            <span class="stat-value">{{ averageDays() }} days</span>
            <span class="stat-label">average time to pay</span>
          </div>
          <div class="stat" role="listitem">
            <span class="stat-value">{{ investmentGrade() }}</span>
            <span class="stat-label">rated A or better</span>
          </div>
        </div>

        @if (buyers().length === 0) {
          <p class="muted empty" role="status">No buyers match your search.</p>
        } @else {
          <div class="table-wrap">
            <table class="table">
              <caption class="visually-hidden">Potential buyers (sample data)</caption>
              <thead>
                <tr>
                  <th scope="col">Buyer</th>
                  <th scope="col">Industry</th>
                  <th scope="col">Rating</th>
                  <th scope="col" class="num">Avg. days to pay</th>
                  <th scope="col" class="num">Annual purchases</th>
                  <th scope="col">Match</th>
                </tr>
              </thead>
              <tbody>
                @for (buyer of buyers(); track buyer.id) {
                  <tr>
                    <th scope="row">
                      <span class="buyer-name">{{ buyer.name }}</span>
                      <span class="buyer-city">{{ buyer.city }}</span>
                    </th>
                    <td data-label="Industry">{{ buyer.industry }}</td>
                    <td data-label="Rating"><span class="badge" [class]="ratingBadge[buyer.rating]">{{ buyer.rating }}</span></td>
                    <td class="num" data-label="Avg. days to pay">{{ buyer.avgDaysToPay }}</td>
                    <td class="num" data-label="Annual purchases">{{ crore(buyer.annualPurchases) }}</td>
                    <td data-label="Match">
                      <span class="match">
                        <span class="match-track" aria-hidden="true">
                          <span class="match-fill" [style.width.%]="buyer.matchScore"></span>
                        </span>
                        <span class="match-value">{{ buyer.matchScore }}%</span>
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </section>
  `,
  styles: `
    .dashboard {
      display: flex;
      flex-direction: column;
      gap: var(--space-6);
    }

    .welcome {
      position: relative;
      overflow: hidden;
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4);
      align-items: flex-end;
      justify-content: space-between;
      padding: var(--space-6);
      border-radius: var(--radius-lg);
      background: linear-gradient(155deg, #13265e 0%, #0c1a45 55%, #070f2b 100%);
      color: #ffffff;
    }

    .glow {
      position: absolute;
      width: 22rem;
      height: 22rem;
      top: -8rem;
      right: -4rem;
      border-radius: 50%;
      background: rgb(96 140 255 / 30%);
      filter: blur(80px);
      pointer-events: none;
    }

    .welcome-text,
    .welcome-side {
      position: relative;
    }

    .eyebrow {
      margin: 0 0 var(--space-1);
      font-size: var(--text-xs);
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: #8fb0ff;
    }

    h1 {
      margin: 0;
      font-size: clamp(var(--text-xl), 4vw, var(--text-3xl));
      letter-spacing: -0.02em;
      color: #ffffff;
      overflow-wrap: anywhere;
    }

    .intro {
      margin: var(--space-2) 0 0;
      max-width: 38rem;
      color: rgb(255 255 255 / 80%);
    }

    .welcome-side {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: var(--space-2);
    }

    .role-chip {
      padding: 0.2rem 0.75rem;
      border-radius: 999px;
      background: rgb(255 255 255 / 12%);
      border: 1px solid rgb(255 255 255 / 20%);
      font-size: var(--text-sm);
      font-weight: 650;
    }

    .unread {
      color: #ffffff;
      font-size: var(--text-sm);
      text-decoration: none;
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-sm);
      background: rgb(255 255 255 / 8%);

      &:hover {
        background: rgb(255 255 255 / 16%);
      }

      &:focus-visible {
        outline: 2px solid #ffffff;
        outline-offset: 2px;
      }
    }

    .section-title {
      margin: 0 0 var(--space-3);
      font-size: var(--text-lg);
    }

    .actions {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .action {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      height: 100%;
      color: inherit;
      text-decoration: none;
      transition:
        border-color var(--transition),
        transform var(--transition),
        box-shadow var(--transition);

      &:hover {
        border-color: var(--color-primary);
        box-shadow: var(--shadow-md);
        transform: translateY(-2px);
      }
    }

    .action-icon {
      display: inline-grid;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      margin-bottom: var(--space-2);
      border-radius: var(--radius-sm);
      background: var(--color-primary-soft);
      color: var(--color-primary);
      font-weight: 700;
    }

    .action-title {
      font-weight: 650;
    }

    .action-desc {
      font-size: var(--text-sm);
      color: var(--color-text-muted);
    }

    .buyers {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }

    .buyers-head {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4);
      align-items: flex-end;
      justify-content: space-between;

      h2 {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        margin: 0;
        font-size: var(--text-xl);
      }

      p {
        margin: var(--space-1) 0 0;
        max-width: 40rem;
      }
    }

    .sample {
      font-size: var(--text-xs);
    }

    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);

      .input {
        min-width: 14rem;
      }
    }

    .stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: var(--space-3);
    }

    .stat {
      display: flex;
      flex-direction: column;
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius-sm);
      background: var(--color-surface-2);
    }

    .stat-value {
      font-size: var(--text-xl);
      font-weight: 750;
    }

    .stat-label {
      font-size: var(--text-sm);
      color: var(--color-text-muted);
    }

    .empty {
      margin: 0;
    }

    th[scope='row'] {
      font-weight: 400;
      color: var(--color-text);
      background: none;
    }

    tr:last-child th[scope='row'] {
      border-bottom: 0;
    }

    .buyer-name {
      display: block;
      font-weight: 650;
    }

    .buyer-city {
      font-size: var(--text-sm);
      color: var(--color-text-muted);
    }

    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }

    .match {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      min-width: 8rem;
    }

    .match-track {
      flex: 1;
      height: 6px;
      border-radius: 999px;
      background: var(--color-surface-2);
      overflow: hidden;
    }

    .match-fill {
      display: block;
      height: 100%;
      border-radius: 999px;
      background: var(--color-primary);
    }

    .match-value {
      font-size: var(--text-sm);
      font-weight: 650;
      font-variant-numeric: tabular-nums;
    }

    @media (max-width: 40rem) {
      .welcome {
        padding: var(--space-5);
      }

      .actions {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: var(--space-3);
      }

      .action {
        padding: var(--space-4);
      }

      .action-icon {
        margin-bottom: var(--space-1);
      }

      .action-desc {
        display: none;
      }

      th[scope='row'] {
        display: block;
        padding: 0 0 var(--space-2);
        border: 0;
      }

      .match {
        min-width: 0;
        width: 9rem;
      }

      .welcome-side {
        align-items: flex-start;
      }

      .filters,
      .filters .input,
      .filters .select {
        width: 100%;
        min-width: 0;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .action {
        transition: none;
      }
    }
  `,
})
export class DashboardComponent {
  protected readonly notifications = inject(NotificationBadgeService);

  readonly user = input.required<SessionUser>();

  protected readonly roleLabels = ROLE_LABELS;
  protected readonly ratingBadge = RATING_BADGE;
  protected readonly industries = BUYER_INDUSTRIES;
  protected readonly crore = formatCrore;
  protected readonly today = new Intl.DateTimeFormat('en-IN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(new Date());

  protected readonly query = signal('');
  protected readonly industry = signal('');

  protected readonly content = computed(() => ACTIONS[this.user().role]);

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    return hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  });

  protected readonly buyers = computed(() => {
    const query = this.query().trim().toLowerCase();
    const industry = this.industry();
    return POTENTIAL_BUYERS.filter(
      (b) =>
        (!industry || b.industry === industry) &&
        (!query || b.name.toLowerCase().includes(query) || b.city.toLowerCase().includes(query)),
    );
  });

  protected readonly averageDays = computed(() => {
    const list = this.buyers();
    return list.length === 0 ? 0 : Math.round(list.reduce((sum, b) => sum + b.avgDaysToPay, 0) / list.length);
  });

  protected readonly investmentGrade = computed(() => this.buyers().filter((b) => b.rating !== 'BBB').length);

  protected valueOf(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }
}
