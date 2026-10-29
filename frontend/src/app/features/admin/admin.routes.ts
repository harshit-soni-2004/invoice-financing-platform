import { Routes } from '@angular/router';

import { roleGuard } from '../../core/auth/auth.guards';

/** Loaded under `/admin`. Guards only shape navigation; every admin API checks the ADMIN role itself. */
export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    canActivateChild: [roleGuard('ADMIN')],
    children: [
      {
        path: '',
        title: 'Admin console · Invoice Financing',
        loadComponent: () => import('./admin-home.component').then((m) => m.AdminHomeComponent),
      },
      {
        path: 'invoices',
        title: 'Invoice queue · Invoice Financing',
        loadComponent: () => import('./invoice-queue.component').then((m) => m.InvoiceQueueComponent),
      },
      {
        path: 'invoices/:id',
        title: 'Review invoice · Invoice Financing',
        loadComponent: () => import('./invoice-review.component').then((m) => m.InvoiceReviewComponent),
      },
      {
        path: 'financiers',
        title: 'Financier approvals · Invoice Financing',
        loadComponent: () => import('./financier-approvals.component').then((m) => m.FinancierApprovalsComponent),
      },
      {
        path: 'audit',
        title: 'Audit log · Invoice Financing',
        loadComponent: () => import('./audit-log.component').then((m) => m.AuditLogComponent),
      },
    ],
  },
];
