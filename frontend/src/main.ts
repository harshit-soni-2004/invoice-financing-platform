import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { createAppConfig } from './app/app.config';
import { loadAppConfig } from './app/core/config/app-config';

loadAppConfig()
  .then((config) => bootstrapApplication(AppComponent, createAppConfig(config)))
  .catch((error: unknown) => {
    const message = error instanceof Error ? error.message : 'The application could not start.';
    const root = document.querySelector('ifp-root');
    if (root) {
      root.innerHTML = '';
      const panel = document.createElement('main');
      panel.className = 'startup-error';
      panel.setAttribute('role', 'alert');
      const title = document.createElement('h1');
      title.textContent = 'The application could not start';
      const detail = document.createElement('p');
      detail.textContent = message;
      panel.append(title, detail);
      root.append(panel);
    }
    console.error(error);
  });
