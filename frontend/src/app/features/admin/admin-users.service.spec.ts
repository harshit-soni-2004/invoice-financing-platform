import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError, ApiFailure } from '../../shared/api-failure';
import { userFixture } from './admin-testing';
import { AdminUsersService } from './admin-users.service';

const URL = 'http://gateway.test/api/admin/users';

describe('AdminUsersService', () => {
  let api: AdminUsersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(AdminUsersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function failureOf(promise: Promise<unknown>): Promise<ApiFailure> {
    try {
      await promise;
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      return (error as ApiError).failure;
    }
    throw new Error('expected the request to fail');
  }

  it('lists pending financiers with auth-service’s role and status filters', async () => {
    const result = api.pendingFinanciers(1);
    const request = http.expectOne((req) => req.url === URL);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('role')).toBe('FINANCIER');
    expect(request.request.params.get('status')).toBe('PENDING_APPROVAL');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ content: [userFixture()], page: 1, size: 20, totalElements: 21, totalPages: 2 });

    const page = await result;
    expect(page.content[0].displayName).toBe('Ravi Menon');
    expect(page.totalPages).toBe(2);
  });

  it('treats a malformed user list as unexpected', async () => {
    const result = api.pendingFinanciers(0);
    http
      .expectOne((req) => req.url === URL)
      .flush({ content: [{ ...userFixture(), role: 'ROOT' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await failureOf(result)).toEqual({ kind: 'unexpected' });
  });

  it('activates an account with an empty POST and returns auth-service’s view of it', async () => {
    const result = api.activate('f0000000-0000-4000-8000-000000000001');
    const request = http.expectOne(`${URL}/f0000000-0000-4000-8000-000000000001/activate`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(userFixture({ status: 'ACTIVE' }));
    expect((await result).status).toBe('ACTIVE');
  });

  it('reports an account that is no longer pending with its current status', async () => {
    const result = api.activate('id-1');
    http
      .expectOne(`${URL}/id-1/activate`)
      .flush({ code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE' }, { status: 409, statusText: 'Conflict' });
    expect(await failureOf(result)).toEqual({ kind: 'conflict', code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE' });
  });

  it('reports a missing admin role', async () => {
    const result = api.activate('id-1');
    http.expectOne(`${URL}/id-1/activate`).flush({ code: 'ACCESS_DENIED' }, { status: 403, statusText: 'Forbidden' });
    expect(await failureOf(result)).toEqual({ kind: 'forbidden' });
  });
});
