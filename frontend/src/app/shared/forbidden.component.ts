import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService, homeFor } from '../core/auth/auth.service';

@Component({
  selector: 'ifp-forbidden',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page narrow">
      <div class="card">
        <h1>You don't have access to this page</h1>
        <p class="muted">Your account's role does not include this area.</p>
        <a class="btn btn-primary" [routerLink]="home()">Go to your home page</a>
      </div>
    </section>
  `,
  styles: `
    .narrow {
      max-width: 36rem;
    }
  `,
})
export class ForbiddenComponent {
  private readonly auth = inject(AuthService);
  protected readonly home = computed(() => {
    const user = this.auth.user();
    return user ? homeFor(user.role) : '/';
  });
}
