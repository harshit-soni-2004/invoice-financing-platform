import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'ifp-landing',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="hero">
      <div class="page hero-inner">
        <p class="eyebrow">Invoice financing, end to end</p>
        <h1>Turn unpaid invoices into working capital</h1>
        <p class="lead">
          Sellers list verified invoices. Financiers compete with offers. The accepted offer becomes an agreement with a
          repayment schedule, and every step is recorded in an audit trail.
        </p>
        <div class="cluster">
          <a class="btn btn-primary" routerLink="/register">Create an account</a>
          <a class="btn btn-secondary" routerLink="/login">Sign in</a>
        </div>
      </div>
    </section>

    <section class="page" aria-labelledby="how-heading">
      <h2 id="how-heading">How it works</h2>
      <ol class="steps">
        <li class="card">
          <span class="step-no" aria-hidden="true">1</span>
          <h3>Submit an invoice</h3>
          <p class="muted">
            A seller records an invoice and submits it. An administrator records the buyer's acknowledgement and
            verifies it.
          </p>
        </li>
        <li class="card">
          <span class="step-no" aria-hidden="true">2</span>
          <h3>List it for financing</h3>
          <p class="muted">Once verified, the seller lists it and approved financiers see it as an opportunity.</p>
        </li>
        <li class="card">
          <span class="step-no" aria-hidden="true">3</span>
          <h3>Compare and accept</h3>
          <p class="muted">Financiers make offers with their own advance and fee terms. The seller accepts one.</p>
        </li>
        <li class="card">
          <span class="step-no" aria-hidden="true">4</span>
          <h3>Disburse and repay</h3>
          <p class="muted">
            The financier approves and disburses (simulated), and the seller repays against the schedule.
          </p>
        </li>
      </ol>
    </section>

    <section class="page roles" aria-labelledby="roles-heading">
      <h2 id="roles-heading">Built for each side of the deal</h2>
      <div class="role-grid">
        <article class="card">
          <h3>Sellers</h3>
          <p class="muted">Track every invoice's status history, review competing offers and record repayments.</p>
        </article>
        <article class="card">
          <h3>Financiers</h3>
          <p class="muted">
            Browse listed invoices, make or withdraw offers, and follow repayments with overdue indicators. New financier
            accounts are activated by an administrator.
          </p>
        </article>
        <article class="card">
          <h3>Administrators</h3>
          <p class="muted">
            Verify submitted invoices, approve financiers and review the append-only audit trail of every event.
          </p>
        </article>
      </div>
    </section>
  `,
  styles: `
    .hero {
      background: linear-gradient(180deg, var(--color-primary-soft), var(--color-bg));
      border-bottom: 1px solid var(--color-border);
    }

    .hero-inner {
      padding-block: var(--space-7);
      max-width: 52rem;
      margin-inline: auto;
      text-align: center;

      .cluster {
        justify-content: center;
      }
    }

    .eyebrow {
      font-size: var(--text-sm);
      font-weight: 650;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--color-primary);
    }

    h1 {
      font-size: clamp(var(--text-2xl), 5vw, var(--text-3xl));
    }

    .lead {
      font-size: var(--text-lg);
      color: var(--color-text-muted);
      margin-bottom: var(--space-5);
    }

    .steps {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .step-no {
      display: inline-grid;
      place-items: center;
      width: 2rem;
      height: 2rem;
      margin-bottom: var(--space-3);
      border-radius: 50%;
      background: var(--color-primary-soft);
      color: var(--color-primary);
      font-weight: 700;
    }

    .roles {
      padding-top: 0;
    }

    .role-grid {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
    }
  `,
})
export class LandingComponent {}
