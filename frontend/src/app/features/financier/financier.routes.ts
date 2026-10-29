import { Routes } from '@angular/router';

/** Mounted under /financier behind the FINANCIER role guard (see app.routes.ts). */
export const FINANCIER_ROUTES: Routes = [
  {
    path: '',
    title: 'Financing opportunities · Invoice Financing',
    loadComponent: () => import('./opportunity-list.component').then((m) => m.OpportunityListComponent),
  },
  {
    path: 'opportunities/:invoiceId',
    title: 'Opportunity · Invoice Financing',
    loadComponent: () => import('./opportunity-detail.component').then((m) => m.OpportunityDetailComponent),
  },
  {
    path: 'agreements',
    title: 'Your agreements · Invoice Financing',
    loadComponent: () => import('./agreement-list.component').then((m) => m.AgreementListComponent),
  },
  {
    path: 'agreements/:id',
    title: 'Agreement · Invoice Financing',
    loadComponent: () => import('./agreement-detail.component').then((m) => m.AgreementDetailComponent),
  },
];
