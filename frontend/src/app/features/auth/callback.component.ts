import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthError, AuthService } from '../../core/auth/auth.service';

/** auth-service redirects here with `code` and `state` (or `error`) after the user signs in. */
@Component({
  selector: 'ifp-auth-callback',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page narrow">
      <div class="card stack">
        @if (failure(); as message) {
          <h1>Sign-in failed</h1>
          <p class="alert alert-danger" role="alert">{{ message }}</p>
          <div class="cluster">
            <a class="btn btn-primary" routerLink="/login">Start again</a>
            <a class="btn btn-secondary" routerLink="/">Go to the home page</a>
          </div>
        } @else {
          <h1>Signing you in</h1>
          <p class="muted" role="status">Completing sign-in…</p>
        }
      </div>
    </section>
  `,
  styles: `
    .narrow {
      max-width: 36rem;
    }
  `,
})
export class CallbackComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly code = input<string>();
  readonly state = input<string>();
  readonly error = input<string>();

  protected readonly failure = signal<string | null>(null);

  ngOnInit(): void {
    this.auth
      .completeSignIn({ code: this.code(), state: this.state(), error: this.error() })
      .then((url) => this.router.navigateByUrl(url, { replaceUrl: true }))
      .catch((error: unknown) => {
        this.failure.set(error instanceof AuthError ? error.message : 'Sign-in could not be completed.');
      });
  }
}
