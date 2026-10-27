import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Invoice Financing',
    loadComponent: () => import('./features/public/landing.component').then((m) => m.LandingComponent),
  },
  {
    path: '**',
    title: 'Page not found · Invoice Financing',
    loadComponent: () => import('./shared/not-found.component').then((m) => m.NotFoundComponent),
  },
];
