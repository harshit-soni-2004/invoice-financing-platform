import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Section links shared by the admin console pages. */
@Component({
  selector: 'ifp-admin-nav',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="admin-nav" aria-label="Admin console">
      <ul>
        <li><a routerLink="/admin/invoices" routerLinkActive="active" ariaCurrentWhenActive="page">Invoice queue</a></li>
        <li>
          <a routerLink="/admin/financiers" routerLinkActive="active" ariaCurrentWhenActive="page">Financier approvals</a>
        </li>
        <li><a routerLink="/admin/audit" routerLinkActive="active" ariaCurrentWhenActive="page">Audit log</a></li>
      </ul>
    </nav>
  `,
  styles: `
    .admin-nav {
      margin-bottom: var(--space-4);
      border-bottom: 1px solid var(--color-border);
    }

    ul {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    a {
      display: block;
      padding: var(--space-2) var(--space-3);
      border-bottom: 2px solid transparent;
      color: var(--color-text-muted);
      font-weight: 600;
      font-size: var(--text-sm);
      text-decoration: none;

      &:hover {
        color: var(--color-text);
      }

      &.active {
        border-bottom-color: var(--color-primary);
        color: var(--color-primary);
      }
    }
  `,
})
export class AdminNavComponent {}
