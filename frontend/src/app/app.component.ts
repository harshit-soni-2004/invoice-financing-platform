
import { ChangeDetectionStrategy, Component, computed, inject, signal, DOCUMENT } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ThemeService } from './core/theme/theme.service';

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
  protected readonly menuOpen = signal(false);
  protected readonly year = new Date().getFullYear();

  protected readonly links = computed<NavLink[]>(() => [{ path: '/', label: 'Home', exact: true }]);

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
