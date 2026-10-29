import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

/**
 * Shown instead of a page when financing-service refuses the account (403) or the session has ended (401).
 *
 * auth-service issues no token to a financier whose account is still pending approval, so a pending financier normally
 * never reaches these pages. A 403 here means the token carries no FINANCIER role that financing-service accepts, for
 * example after the account was changed; signing in again picks up the current role and status.
 */
@Component({
  selector: 'ifp-access-problem',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card stack" role="alert">
      @if (kind() === 'forbidden') {
        <h2>Your financier account is not active</h2>
        <p>
          The financing service did not accept your account for financier tools. New financier accounts can only be
          used after an administrator approves them.
        </p>
        <p class="muted">
          If you were approved recently, sign out and sign in again. Otherwise wait for the approval, or contact the
          platform administrator.
        </p>
        <div class="cluster">
          <a class="btn btn-secondary" routerLink="/account">View your account</a>
        </div>
      } @else {
        <h2>Your session has ended</h2>
        <p>Sign in again to continue where you left off.</p>
        <div class="cluster">
          <a class="btn btn-primary" routerLink="/login" [queryParams]="{ returnUrl: returnUrl }">Sign in again</a>
        </div>
      }
    </div>
  `,
  styles: `
    h2,
    p {
      margin: 0;
    }

    h2 {
      font-size: var(--text-lg);
    }
  `,
})
export class AccessProblemComponent {
  readonly kind = input.required<'forbidden' | 'unauthenticated'>();

  protected readonly returnUrl = inject(Router).url;
}
