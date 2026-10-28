import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthError, AuthService, homeFor, safeReturnUrl } from '../../core/auth/auth.service';

/** Starts the redirect to auth-service, or explains why the user has to sign in again. */
@Component({
  selector: 'ifp-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page narrow">
      <div class="card stack">
        @if (error(); as message) {
          <h1>Sign-in is unavailable</h1>
          <p class="alert alert-danger" role="alert">{{ message }}</p>
          <div class="cluster">
            <button type="button" class="btn btn-primary" (click)="start()">Try again</button>
          </div>
        } @else if (reason() === 'expired' && !redirecting()) {
          <h1>Your session expired</h1>
          <p class="muted">
            For your security, a session lasts 15 minutes. Sign in again to continue where you left off.
          </p>
          <div class="cluster">
            <button type="button" class="btn btn-primary" (click)="start()">Sign in again</button>
          </div>
        } @else {
          <h1>Signing you in</h1>
          <p class="muted" role="status">Taking you to the sign-in page…</p>
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
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  protected readonly error = signal<string | null>(null);
  protected readonly redirecting = signal(false);

  ngOnInit(): void {
    const user = this.auth.user();
    if (user) {
      void this.router.navigateByUrl(safeReturnUrl(this.returnUrl()) ?? homeFor(user.role), { replaceUrl: true });
    } else if (this.reason() !== 'expired') {
      this.start();
    }
  }

  protected start(): void {
    this.error.set(null);
    this.redirecting.set(true);
    this.auth.signIn(this.returnUrl()).catch((error: unknown) => {
      this.redirecting.set(false);
      this.error.set(error instanceof AuthError ? error.message : 'Sign-in could not be started.');
    });
  }
}
