import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { AccountApi, AccountApiError } from './account-api';

describe('AccountApi', () => {
  let api: AccountApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(AccountApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends only the display name when renaming', async () => {
    const result = api.rename('Asha Mehta');
    const request = http.expectOne('http://gateway.test/api/users/me');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ displayName: 'Asha Mehta' });
    request.flush({ displayName: 'Asha Mehta' });
    expect((await result).displayName).toBe('Asha Mehta');
  });

  it('reads field rejections from a validation problem', async () => {
    const result = api.changePassword('wrong', 'a brand new passphrase');
    const request = http.expectOne('http://gateway.test/api/users/me/password');
    expect(request.request.method).toBe('POST');
    request.flush(
      { code: 'VALIDATION_FAILED', errors: [{ field: 'currentPassword', message: 'is incorrect' }, { bogus: true }] },
      { status: 400, statusText: 'Bad Request' },
    );

    const error = await result.then(
      () => null,
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(AccountApiError);
    expect((error as AccountApiError).failure.kind).toBe('invalid');
    expect((error as AccountApiError).rejections).toEqual([{ field: 'currentPassword', message: 'is incorrect' }]);
  });

  it('reports a lockout with its Retry-After', async () => {
    const result = api.changePassword('wrong', 'a brand new passphrase');
    http
      .expectOne('http://gateway.test/api/users/me/password')
      .flush({ code: 'TOO_MANY_ATTEMPTS' }, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '300' } });

    const error = (await result.catch((e: unknown) => e)) as AccountApiError;
    expect(error.failure).toEqual({ kind: 'rate-limited', retryAfterSeconds: 300 });
  });
});
