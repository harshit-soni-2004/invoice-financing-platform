import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { ApiFailure, apiFailureFor, commonFailureMessage, parsePage } from './api-failure';

describe('api-failure', () => {
  const error = (status: number, body: unknown = null, headers: Record<string, string> = {}) =>
    new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers) });

  const cases: [number, unknown, Record<string, string>, ApiFailure][] = [
    [0, null, {}, { kind: 'network' }],
    [400, { code: 'INVALID_QUERY' }, {}, { kind: 'invalid' }],
    [401, null, {}, { kind: 'unauthenticated' }],
    [403, { code: 'ACCESS_DENIED' }, {}, { kind: 'forbidden' }],
    [404, { code: 'USER_NOT_FOUND' }, {}, { kind: 'not-found' }],
    [
      409,
      { code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE', detail: 'internal wording' },
      {},
      { kind: 'conflict', code: 'USER_NOT_PENDING_APPROVAL', currentStatus: 'ACTIVE' },
    ],
    [409, 'not json', {}, { kind: 'conflict', code: null, currentStatus: null }],
    [412, { code: 'VERSION_MISMATCH' }, {}, { kind: 'stale' }],
    [428, null, {}, { kind: 'stale' }],
    [429, null, { 'Retry-After': '12' }, { kind: 'rate-limited', retryAfterSeconds: 12 }],
    [429, null, { 'Retry-After': 'soon' }, { kind: 'rate-limited', retryAfterSeconds: null }],
    [503, null, {}, { kind: 'unavailable' }],
    [500, { code: 'INTERNAL_ERROR', detail: 'NullPointerException at ...' }, {}, { kind: 'unexpected' }],
  ];

  for (const [status, body, headers, expected] of cases) {
    it(`maps ${status} ${JSON.stringify(body)} to ${expected.kind}`, () => {
      expect(apiFailureFor(error(status, body, headers))).toEqual(expected);
    });
  }

  it('never echoes server wording in messages', () => {
    const failures: ApiFailure[] = cases.map((c) => c[3]);
    for (const failure of failures) {
      const message = commonFailureMessage(failure, 'audit service');
      expect(message).not.toContain('internal wording');
      expect(message).not.toContain('NullPointerException');
      expect(message.length).toBeGreaterThan(0);
    }
    expect(commonFailureMessage({ kind: 'unavailable' }, 'audit service')).toContain('audit service');
  });

  it('parses the shared page contract and rejects malformed pages', () => {
    const row = (value: unknown) => (typeof value === 'number' ? value : null);
    expect(parsePage({ content: [1, 2], page: 0, size: 20, totalElements: 2, totalPages: 1 }, row)).toEqual({
      content: [1, 2],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
    expect(parsePage({ content: [1, 'x'], page: 0, size: 20, totalElements: 2, totalPages: 1 }, row)).toBeNull();
    expect(parsePage({ content: [], page: -1, size: 20, totalElements: 0, totalPages: 0 }, row)).toBeNull();
    expect(parsePage([], row)).toBeNull();
  });
});
