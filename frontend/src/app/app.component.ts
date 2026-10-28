
import { ChangeDetectionStrategy, Component, computed, inject, signal, DOCUMENT } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthService, homeFor } from './core/auth/auth.service';
import { ROLE_LABELS } from './core/auth/role-labels';
import { ThemeService } from './core/theme/theme.service';

const AREA_LABELS = { SELLER: 'Seller workspace', FINANCIER: 'Financier workspace', ADMIN: 'Admin console' } as const;

interface NavLink {
  path: string;
  label: string;
  exact?: boolean;
}

@Component({
  selector: 'ifp-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly document = inject(DOCUMENT);
  protected readonly theme = inject(ThemeService);
  protected readonly auth = inject(AuthService);
  protected readonly menuOpen = signal(false);
  protected readonly year = new Date().getFullYear();
  protected readonly roleLabels = ROLE_LABELS;

  /** Links follow the signed-in role for convenience only; the APIs enforce access. */
  protected readonly links = computed<NavLink[]>(() => {
    const links: NavLink[] = [{ path: '/', label: 'Home', exact: true }];
    const user = this.auth.user();
    if (user) {
      links.push({ path: homeFor(user.role), label: AREA_LABELS[user.role] }, { path: '/account', label: 'Account' });
    }
    return links;
  });

  protected readonly themeLabel = computed(() => {
    switch (this.theme.preference()) {
      case 'light':
        return 'Theme: light';
      case 'dark':
        return 'Theme: dark';
      default:
        return 'Theme: system';
    }
  });

  constructor() {
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.menuOpen.set(false));
  }

  /** With <base href="/">, following "#main" would navigate to "/#main"; move focus instead. */
  protected skipToMain(event: Event): void {
    event.preventDefault();
    this.document.getElementById('main')?.focus();
  }

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }
}
