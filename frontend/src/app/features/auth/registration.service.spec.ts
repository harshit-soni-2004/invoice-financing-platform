import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { RegistrationError, RegistrationFailure, RegistrationRequest, RegistrationService } from './registration.service';

const URL = 'http://gateway.test/api/auth/register';

describe('RegistrationService', () => {
  let service: RegistrationService;
  let http: HttpTestingController;
  const request: RegistrationRequest = {
    email: 'asha@example.test',
    password: 'a long passphrase',
    displayName: 'Asha Rao',
    role: 'SELLER',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    service = TestBed.inject(RegistrationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function failureFor(
    body: object | null,
    init: { status: number; statusText: string; headers?: Record<string, string> },
  ): Promise<RegistrationFailure> {
    const result = service.register(request);
    http.expectOne(URL).flush(body, init);
    try {
      await result;
    } catch (error) {
      expect(error).toBeInstanceOf(RegistrationError);
      return (error as RegistrationError).failure;
    }
    throw new Error('expected the registration to fail');
  }

  it('posts the request to the gateway and returns the created account', async () => {
    const result = service.register(request);
    const call = http.expectOne(URL);
    expect(call.request.method).toBe('POST');
    expect(call.request.body).toEqual(request);
    call.flush(
      { id: 'u-1', email: 'asha@example.test', displayName: 'Asha Rao', role: 'SELLER', status: 'ACTIVE', createdAt: 'x' },
      { status: 201, statusText: 'Created' },
    );
    expect(await result).toEqual({
      id: 'u-1',
      email: 'asha@example.test',
      displayName: 'Asha Rao',
      role: 'SELLER',
      status: 'ACTIVE',
    });
  });

  it('reports field errors from a validation problem, ignoring unknown fields', async () => {
    const failure = await failureFor(
      {
        code: 'VALIDATION_FAILED',
        errors: [
          { field: 'password', message: 'must be at least 12 characters' },
          { field: 'internal', message: 'ignored' },
          { field: 'email', message: 'must be a well-formed email address' },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(failure).toEqual({
      kind: 'invalid',
      fieldErrors: { password: 'must be at least 12 characters', email: 'must be a well-formed email address' },
    });
  });

  it('treats a 400 without field errors as invalid with none', async () => {
    expect(await failureFor(null, { status: 400, statusText: 'Bad Request' })).toEqual({
      kind: 'invalid',
      fieldErrors: {},
    });
  });

  it('recognises a duplicate email', async () => {
    expect(await failureFor({ code: 'DUPLICATE_EMAIL' }, { status: 409, statusText: 'Conflict' })).toEqual({
      kind: 'duplicate-email',
    });
  });

  it('reads Retry-After from a rate-limited response', async () => {
    expect(
      await failureFor(null, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '12' } }),
    ).toEqual({ kind: 'rate-limited', retryAfterSeconds: 12 });
    expect(await failureFor(null, { status: 429, statusText: 'Too Many Requests' })).toEqual({
      kind: 'rate-limited',
      retryAfterSeconds: null,
    });
  });

  it('separates network failures, outages and anything else', async () => {
    const result = service.register(request);
    http.expectOne(URL).error(new ProgressEvent('error'));
    await expectAsync(result).toBeRejectedWith(new RegistrationError({ kind: 'network' }));

    expect(await failureFor(null, { status: 503, statusText: 'Unavailable' })).toEqual({ kind: 'unavailable' });
    expect(await failureFor(null, { status: 500, statusText: 'Error' })).toEqual({ kind: 'unexpected' });
    expect(await failureFor({ code: 'OTHER' }, { status: 409, statusText: 'Conflict' })).toEqual({ kind: 'unexpected' });
  });

  it('refuses a success response that does not describe the requested account', async () => {
    for (const body of [
      null,
      { id: 'u-1', email: 'a@b.c', displayName: 'A', role: 'ADMIN', status: 'ACTIVE' },
      { id: 'u-1', email: 'a@b.c', displayName: 'A', role: 'SELLER', status: 'DISABLED' },
      { email: 'a@b.c', displayName: 'A', role: 'SELLER', status: 'ACTIVE' },
    ]) {
      const result = service.register(request);
      http.expectOne(URL).flush(body, { status: 201, statusText: 'Created' });
      await expectAsync(result).withContext(JSON.stringify(body)).toBeRejectedWith(
        new RegistrationError({ kind: 'unexpected' }),
      );
    }
  });
});
