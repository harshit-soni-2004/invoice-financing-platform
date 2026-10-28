import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Adds the bearer token to gateway API calls only. A 401 means the token is no longer accepted, so the user signs in
 * again. A 403 on a page load shows the "no access" page; a refused action is left to the page that sent it.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.accessTokenFor(request.url);
  if (!token) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          auth.sessionRejected();
        } else if (error.status === 403 && request.method === 'GET') {
          void router.navigateByUrl('/forbidden');
        }
      }
      return throwError(() => error);
    }),
  );
};
