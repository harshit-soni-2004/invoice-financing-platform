import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { APP_CONFIG, AppConfig } from './core/config/app-config';

export function createAppConfig(config: AppConfig): ApplicationConfig {
  return {
    providers: [
      { provide: APP_CONFIG, useValue: config },
      provideBrowserGlobalErrorListeners(),
      provideZonelessChangeDetection(),
      provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
      provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    ],
  };
}
