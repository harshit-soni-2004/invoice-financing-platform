import { AppConfigError, loadAppConfig, parseAppConfig } from './app-config';

describe('app config', () => {
  it('accepts absolute URLs and strips trailing slashes', () => {
    expect(
      parseAppConfig({ apiBaseUrl: 'http://localhost:8080/', authIssuer: 'https://auth.example.test', clientId: ' ifp-spa ' }),
    ).toEqual({ apiBaseUrl: 'http://localhost:8080', authIssuer: 'https://auth.example.test', clientId: 'ifp-spa' });
  });

  it('rejects relative, non-http and missing values', () => {
    expect(() => parseAppConfig({ apiBaseUrl: '/api', authIssuer: 'http://a', clientId: 'x' })).toThrowError(
      AppConfigError,
      /apiBaseUrl must be an absolute URL/,
    );
    expect(() => parseAppConfig({ apiBaseUrl: 'javascript:alert(1)', authIssuer: 'http://a', clientId: 'x' })).toThrowError(
      AppConfigError,
      /http or https/,
    );
    expect(() => parseAppConfig({ apiBaseUrl: 'http://a', authIssuer: 'http://a' })).toThrowError(AppConfigError, /clientId/);
    expect(() => parseAppConfig(null)).toThrowError(AppConfigError);
  });

  it('loads the file without caching and reports HTTP failures', async () => {
    const ok = jasmine.createSpy('fetch').and.resolveTo(
      new Response(JSON.stringify({ apiBaseUrl: 'http://gw', authIssuer: 'http://auth', clientId: 'ifp-spa' })),
    );
    await expectAsync(loadAppConfig(ok)).toBeResolvedTo({ apiBaseUrl: 'http://gw', authIssuer: 'http://auth', clientId: 'ifp-spa' });
    expect(ok).toHaveBeenCalledWith('/config.json', { cache: 'no-store' });

    const missing = jasmine.createSpy('fetch').and.resolveTo(new Response('', { status: 404 }));
    await expectAsync(loadAppConfig(missing)).toBeRejectedWithError(AppConfigError, /HTTP 404/);

    const offline = jasmine.createSpy('fetch').and.rejectWith(new TypeError('Failed to fetch'));
    await expectAsync(loadAppConfig(offline)).toBeRejectedWithError(AppConfigError, /could not be loaded/);
  });
});
