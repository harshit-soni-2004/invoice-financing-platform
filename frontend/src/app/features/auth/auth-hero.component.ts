import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Decorative product panel beside the registration form; matches auth-service's sign-in page. */
@Component({
  selector: 'ifp-auth-hero',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="hero">
      <div class="glow glow-1" aria-hidden="true"></div>
      <div class="glow glow-2" aria-hidden="true"></div>

      <p class="brand-badge">
        <span class="brand-mark" aria-hidden="true">IF</span>
        <span class="brand-name">Invoice Financing</span>
        <span class="brand-tag">Working capital, faster</span>
      </p>

      <div class="stage" aria-hidden="true">
        <div class="glass-card float-slow">
          <div class="card-head">
            <span class="card-label">Invoice INV-2026-0142</span>
            <span class="pill">Verified</span>
          </div>
          <div class="card-amount">₹4,80,000</div>
          <div class="card-meta">Buyer · Northwind Retail Pvt Ltd · due in 60 days</div>
          <div class="meter"><div class="meter-fill"></div></div>
          <div class="meter-caption"><span>Advance 85%</span><span>₹4,08,000</span></div>
        </div>
        <div class="float-pill pill-1 float-reverse">
          <span class="pill-icon">⚡</span>
          <span><strong>3 offers</strong> received in 2 hours</span>
        </div>
        <div class="float-pill pill-2 float-delayed">
          <span class="pill-icon done">✓</span>
          <span>Disbursed <strong>₹4,08,000</strong></span>
        </div>
      </div>

      <div class="narrative">
        <p class="narrative-badge">Get started in minutes</p>
        <h2>One account for every side of the deal.</h2>
        <ul class="points">
          <li><strong>Sellers</strong> list verified invoices and pick the best offer.</li>
          <li><strong>Financiers</strong> fund invoices and track repayments on schedule.</li>
          <li>Every step is recorded in an append-only audit trail.</li>
        </ul>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
    }

    .hero {
      position: relative;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: var(--space-5);
      height: 100%;
      padding: var(--space-6);
      border-radius: var(--radius-lg);
      background: linear-gradient(155deg, #13265e 0%, #0c1a45 55%, #070f2b 100%);
      color: #ffffff;
    }

    .glow {
      position: absolute;
      border-radius: 50%;
      filter: blur(80px);
      pointer-events: none;
    }

    .glow-1 {
      width: 20rem;
      height: 20rem;
      top: -5rem;
      right: -4rem;
      background: rgb(96 140 255 / 28%);
    }

    .glow-2 {
      width: 17rem;
      height: 17rem;
      bottom: 0;
      left: -4rem;
      background: rgb(45 212 191 / 16%);
    }

    .brand-badge {
      position: relative;
      align-self: flex-start;
      display: inline-flex;
      align-items: center;
      gap: 0.6rem;
      margin: 0;
      padding: 0.35rem 0.85rem 0.35rem 0.4rem;
      border-radius: 999px;
      background: rgb(255 255 255 / 8%);
      border: 1px solid rgb(255 255 255 / 14%);
    }

    .brand-mark {
      display: inline-grid;
      place-items: center;
      width: 1.75rem;
      height: 1.75rem;
      border-radius: 8px;
      background: #4f7cff;
      font-size: var(--text-xs);
      font-weight: 800;
    }

    .brand-name {
      font-weight: 700;
    }

    .brand-tag {
      font-size: var(--text-xs);
      color: rgb(255 255 255 / 70%);
      border-left: 1px solid rgb(255 255 255 / 20%);
      padding-left: 0.55rem;
    }

    .stage {
      position: relative;
      align-self: center;
      width: 100%;
      max-width: 22rem;
      margin: var(--space-5) 0;
    }

    .glass-card {
      position: relative;
      z-index: 1;
      padding: 1.35rem;
      border-radius: var(--radius-lg);
      background: rgb(255 255 255 / 9%);
      border: 1px solid rgb(255 255 255 / 16%);
      backdrop-filter: blur(16px);
      box-shadow: 0 16px 36px rgb(0 0 0 / 35%);
    }

    .card-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-2);
    }

    .card-label {
      font-size: var(--text-xs);
      font-weight: 600;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: rgb(255 255 255 / 75%);
    }

    .pill {
      font-size: var(--text-xs);
      font-weight: 700;
      padding: 0.15rem 0.55rem;
      border-radius: 999px;
      background: rgb(52 211 153 / 18%);
      color: #6ee7b7;
      border: 1px solid rgb(52 211 153 / 30%);
    }

    .card-amount {
      margin-top: 0.6rem;
      font-size: 2rem;
      font-weight: 800;
      letter-spacing: -0.03em;
    }

    .card-meta {
      margin-bottom: var(--space-4);
      font-size: 0.8rem;
      color: rgb(255 255 255 / 72%);
    }

    .meter {
      height: 8px;
      border-radius: 999px;
      background: rgb(255 255 255 / 12%);
      overflow: hidden;
    }

    .meter-fill {
      width: 85%;
      height: 100%;
      border-radius: 999px;
      background: linear-gradient(90deg, #4f7cff, #2dd4bf);
    }

    .meter-caption {
      display: flex;
      justify-content: space-between;
      margin-top: 0.4rem;
      font-size: 0.78rem;
      font-weight: 600;
      color: rgb(255 255 255 / 82%);
    }

    .float-pill {
      position: absolute;
      z-index: 2;
      display: flex;
      align-items: center;
      gap: 0.55rem;
      padding: 0.5rem 0.8rem;
      border-radius: 12px;
      background: rgb(10 20 52 / 88%);
      border: 1px solid rgb(255 255 255 / 18%);
      box-shadow: 0 12px 28px rgb(0 0 0 / 40%);
      font-size: 0.8rem;
      white-space: nowrap;
    }

    .pill-icon {
      display: inline-grid;
      place-items: center;
      width: 1.6rem;
      height: 1.6rem;
      border-radius: 8px;
      background: rgb(79 124 255 / 30%);

      &.done {
        background: rgb(52 211 153 / 25%);
        color: #6ee7b7;
      }
    }

    .pill-1 {
      top: -2.75rem;
      right: -1rem;
    }

    .pill-2 {
      bottom: -1.25rem;
      left: -1rem;
    }

    .narrative {
      position: relative;
    }

    .narrative-badge {
      margin: 0;
      font-size: var(--text-xs);
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: #8fb0ff;
    }

    h2 {
      margin: 0.35rem 0 0.75rem;
      font-size: clamp(1.3rem, 2vw, 1.6rem);
      line-height: 1.25;
      color: #ffffff;
    }

    .points {
      display: grid;
      gap: 0.45rem;
      margin: 0;
      padding: 0;
      list-style: none;

      li {
        position: relative;
        padding-left: 1.5rem;
        font-size: var(--text-sm);
        color: rgb(255 255 255 / 80%);
      }

      li::before {
        content: '';
        position: absolute;
        left: 0;
        top: 0.45rem;
        width: 0.55rem;
        height: 0.55rem;
        border-radius: 50%;
        background: #2dd4bf;
        box-shadow: 0 0 8px rgb(45 212 191 / 70%);
      }

      strong {
        color: #ffffff;
      }
    }

    @keyframes float-y {
      0%,
      100% {
        transform: translateY(0);
      }
      50% {
        transform: translateY(-6px);
      }
    }

    @keyframes float-y-rev {
      0%,
      100% {
        transform: translateY(0);
      }
      50% {
        transform: translateY(6px);
      }
    }

    .float-slow {
      animation: float-y 5s ease-in-out infinite;
    }

    .float-reverse {
      animation: float-y-rev 4.5s ease-in-out infinite;
    }

    .float-delayed {
      animation: float-y 5.5s ease-in-out 1s infinite;
    }

    @media (prefers-reduced-motion: reduce) {
      .float-slow,
      .float-reverse,
      .float-delayed {
        animation: none;
      }
    }

    @media (max-width: 34rem) {
      .hero {
        padding: var(--space-5);
      }

      .stage {
        display: none;
      }
    }
  `,
})
export class AuthHeroComponent {}
