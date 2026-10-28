import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';

import { authGuard, guestGuard, roleGuard } from './auth.guards';
import { AuthService } from './auth.service';
import { SessionUser } from './id-token';

describe('auth guards', () => {
  const user = signal<SessionUser | null>(null);
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/seller?tab=offers' } as RouterStateSnapshot;

  beforeEach(() => {
    user.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { user, isSignedIn: () => user() !== null } },
      ],
    });
  });

  const run = (guard: typeof authGuard) => TestBed.runInInjectionContext(() => guard(route, state));
  const serialize = (tree: unknown) => TestBed.inject(Router).serializeUrl(tree as UrlTree);

  it('sends signed-out users to sign in, remembering where they were going', () => {
    expect(serialize(run(authGuard))).toBe('/login?returnUrl=%2Fseller%3Ftab%3Doffers');
    expect(serialize(run(roleGuard('SELLER')))).toBe('/login?returnUrl=%2Fseller%3Ftab%3Doffers');
  });

  it('lets signed-in users through', () => {
    user.set({ id: 'u', name: 'Asha', role: 'FINANCIER' });
    expect(run(authGuard)).toBeTrue();
    expect(run(roleGuard('FINANCIER', 'ADMIN'))).toBeTrue();
  });

  it('keeps visitor-only pages for signed-out users', () => {
    expect(run(guestGuard)).toBeTrue();
    user.set({ id: 'u', name: 'Asha', role: 'FINANCIER' });
    expect(serialize(run(guestGuard))).toBe('/financier');
  });

  it('shows the no-access page for another role', () => {
    user.set({ id: 'u', name: 'Asha', role: 'SELLER' });
    expect(serialize(run(roleGuard('ADMIN')))).toBe('/forbidden');
  });
});
