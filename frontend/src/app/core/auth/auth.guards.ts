import { inject } from '@angular/core';
import { CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';

import { AuthService, homeFor } from './auth.service';
import { Role } from './id-token';

/** Guards only shape navigation; every API checks the token's role and ownership itself. */
export const authGuard: CanActivateFn = (_route, state) => inject(AuthService).isSignedIn() || signInFirst(state);

/** Pages for visitors only, such as registration: a signed-in user goes to their home page instead. */
export const guestGuard: CanActivateFn = () => {
  const user = inject(AuthService).user();
  return !user || inject(Router).createUrlTree([homeFor(user.role)]);
};

export function roleGuard(...roles: Role[]): CanActivateFn {
  return (_route, state) => {
    const user = inject(AuthService).user();
    if (!user) {
      return signInFirst(state);
    }
    return roles.includes(user.role) || inject(Router).createUrlTree(['/forbidden']);
  };
}

function signInFirst(state: RouterStateSnapshot): UrlTree {
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
}
