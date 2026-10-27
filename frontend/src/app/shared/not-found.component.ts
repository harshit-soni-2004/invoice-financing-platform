import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'ifp-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page narrow">
      <div class="card">
        <h1>Page not found</h1>
        <p class="muted">The page you asked for does not exist, or you do not have access to it.</p>
        <a class="btn btn-primary" routerLink="/">Go to the home page</a>
      </div>
    </section>
  `,
  styles: `
    .narrow {
      max-width: 36rem;
    }
  `,
})
export class NotFoundComponent {}
