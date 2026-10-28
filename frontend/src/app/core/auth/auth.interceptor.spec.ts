import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let router: Router;
  const auth = {
    accessTokenFor: (url: string) => (url.startsWith('http://gateway.test/api/') ? 'access-1' : null),
    sessionRejected: jasmine.createSpy('sessionRejected'),
  };

  beforeEach(() => {
    auth.sessionRejected.calls.reset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
  });

  afterEach(() => backend.verify());

  it('adds the bearer token to gateway API calls', () => {
    http.get('http://gateway.test/api/invoices').subscribe();
    expect(backend.expectOne('http://gateway.test/api/invoices').request.headers.get('Authorization')).toBe(
      'Bearer access-1',
    );
  });

  it('sends nothing to other origins', () => {
    http.get('http://auth.test/.well-known/openid-configuration').subscribe();
    http.get('/config.json').subscribe();
    expect(backend.expectOne('http://auth.test/.well-known/openid-configuration').request.headers.has('Authorization')).toBeFalse();
    expect(backend.expectOne('/config.json').request.headers.has('Authorization')).toBeFalse();
  });

  it('signs in again on 401 and still reports the error to the caller', () => {
    let failed = false;
    http.get('http://gateway.test/api/invoices').subscribe({ error: () => (failed = true) });
    backend.expectOne('http://gateway.test/api/invoices').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(auth.sessionRejected).toHaveBeenCalled();
    expect(failed).toBeTrue();
  });

  it('shows the no-access page when a page load is forbidden', () => {
    http.get('http://gateway.test/api/audit-events').subscribe({ error: () => undefined });
    backend.expectOne('http://gateway.test/api/audit-events').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/forbidden');
    expect(auth.sessionRejected).not.toHaveBeenCalled();
  });

  it('leaves a refused action to the page that sent it', () => {
    http.post('http://gateway.test/api/invoices/1/submit', {}).subscribe({ error: () => undefined });
    backend.expectOne('http://gateway.test/api/invoices/1/submit').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
