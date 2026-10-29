import { Routes } from '@angular/router';

import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

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
    path: 'register',
    title: 'Create an account · Invoice Financing',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register.component').then((m) => m.RegisterComponent),
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
    canActivate: [roleGuard('SELLER')],
    children: [
      {
        path: '',
        title: 'Your invoices · Invoice Financing',
        loadComponent: () => import('./features/seller/invoice-list.component').then((m) => m.InvoiceListComponent),
      },
      {
        path: 'invoices/new',
        title: 'New invoice · Invoice Financing',
        loadComponent: () => import('./features/seller/invoice-create.component').then((m) => m.InvoiceCreateComponent),
      },
      {
        path: 'invoices/:id',
        title: 'Invoice · Invoice Financing',
        loadComponent: () => import('./features/seller/invoice-detail.component').then((m) => m.InvoiceDetailComponent),
      },
    ],
  },
  {
    path: 'financier',
    canActivate: [roleGuard('FINANCIER')],
    loadChildren: () => import('./features/financier/financier.routes').then((m) => m.FINANCIER_ROUTES),
  },
  {
    path: 'admin',
    canActivate: [roleGuard('ADMIN')],
    loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
  {
    path: 'notifications',
    title: 'Notifications · Invoice Financing',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/notifications/notification-list.component').then((m) => m.NotificationListComponent),
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
