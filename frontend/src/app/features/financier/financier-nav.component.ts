import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

export type FinancierSection = 'opportunities' | 'agreements';

/** Switches between the financier's two areas. The shell header only links to the area's home. */
@Component({
  selector: 'ifp-financier-nav',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="tabs" aria-label="Financier sections">
      <a
        routerLink="/financier"
        [class.active]="section() === 'opportunities'"
        [attr.aria-current]="section() === 'opportunities' ? 'page' : null"
        >Opportunities</a
      >
      <a
        routerLink="/financier/agreements"
        [class.active]="section() === 'agreements'"
        [attr.aria-current]="section() === 'agreements' ? 'page' : null"
        >Agreements</a
      >
    </nav>
  `,
  styles: `
    .tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
      margin-bottom: var(--space-4);
      border-bottom: 1px solid var(--color-border);
    }

    a {
      padding: var(--space-2) var(--space-3);
      border-bottom: 2px solid transparent;
      margin-bottom: -1px;
      color: var(--color-text-muted);
      font-weight: 600;
      font-size: var(--text-sm);
      text-decoration: none;

      &:hover {
        color: var(--color-text);
      }

      &.active {
        color: var(--color-primary);
        border-bottom-color: var(--color-primary);
      }
    }
  `,
})
export class FinancierNavComponent {
  readonly section = input.required<FinancierSection>();
}
