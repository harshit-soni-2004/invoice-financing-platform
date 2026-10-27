import { InjectionToken } from '@angular/core';

/** Runtime configuration, read from /config.json before the app starts so one build works in every environment. */
export interface AppConfig {
  /** Gateway base URL; every `/api/**` call goes here. */
  apiBaseUrl: string;
  /** auth-service issuer URL (authorization and token endpoints). */
  authIssuer: string;
  /** Public OAuth2 client registered in auth-service. */
  clientId: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

export class AppConfigError extends Error {}

export async function loadAppConfig(fetchFn: typeof fetch = fetch, url = '/config.json'): Promise<AppConfig> {
  let response: Response;
  try {
    response = await fetchFn(url, { cache: 'no-store' });
  } catch {
    throw new AppConfigError('The configuration file could not be loaded.');
  }
  if (!response.ok) {
    throw new AppConfigError(`The configuration file could not be loaded (HTTP ${response.status}).`);
  }
  return parseAppConfig(await response.json());
}

export function parseAppConfig(raw: unknown): AppConfig {
  if (typeof raw !== 'object' || raw === null) {
    throw new AppConfigError('The configuration file is not a JSON object.');
  }
  const value = raw as Record<string, unknown>;
  const clientId = value['clientId'];
  if (typeof clientId !== 'string' || clientId.trim() === '') {
    throw new AppConfigError('clientId is missing from the configuration.');
  }
  return {
    apiBaseUrl: absoluteUrl(value['apiBaseUrl'], 'apiBaseUrl'),
    authIssuer: absoluteUrl(value['authIssuer'], 'authIssuer'),
    clientId: clientId.trim(),
  };
}

function absoluteUrl(value: unknown, name: string): string {
  if (typeof value !== 'string') {
    throw new AppConfigError(`${name} is missing from the configuration.`);
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new AppConfigError(`${name} must be an absolute URL.`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new AppConfigError(`${name} must use http or https.`);
  }
  return url.href.replace(/\/+$/, '');
}
