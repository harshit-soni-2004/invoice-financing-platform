import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './core/auth/auth.guards';

const workspace = () => import('./features/workspace/workspace.component').then((m) => m.WorkspaceComponent);

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Invoice Financing',
    loadComponent: () => import('./features/public/landing.component').then((m) => m.LandingComponent),
  },
  {
    path: 'login',
    title: 'Sign in · Invoice Financing',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'auth/callback',
    title: 'Signing in · Invoice Financing',
    loadComponent: () => import('./features/auth/callback.component').then((m) => m.CallbackComponent),
  },
  {
    path: 'account',
    title: 'Your account · Invoice Financing',
    canActivate: [authGuard],
    loadComponent: () => import('./features/account/account.component').then((m) => m.AccountComponent),
  },
  {
    path: 'seller',
    title: 'Seller workspace · Invoice Financing',
    canActivate: [roleGuard('SELLER')],
    data: { area: 'SELLER' },
    loadComponent: workspace,
  },
  {
    path: 'financier',
    title: 'Financier workspace · Invoice Financing',
    canActivate: [roleGuard('FINANCIER')],
    data: { area: 'FINANCIER' },
    loadComponent: workspace,
  },
  {
    path: 'admin',
    title: 'Admin console · Invoice Financing',
    canActivate: [roleGuard('ADMIN')],
    data: { area: 'ADMIN' },
    loadComponent: workspace,
  },
  {
    path: 'forbidden',
    title: 'No access · Invoice Financing',
    loadComponent: () => import('./shared/forbidden.component').then((m) => m.ForbiddenComponent),
  },
  {
    path: '**',
    title: 'Page not found · Invoice Financing',
    loadComponent: () => import('./shared/not-found.component').then((m) => m.NotFoundComponent),
  },
];
