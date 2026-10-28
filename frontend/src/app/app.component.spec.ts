import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './core/auth/auth.service';
import { SessionUser } from './core/auth/id-token';

describe('AppComponent', () => {
  const user = signal<SessionUser | null>(null);
  const signOut = jasmine.createSpy('signOut');

  beforeEach(async () => {
    user.set(null);
    signOut.calls.reset();
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { user, signOut } }],
    }).compileComponents();
  });

  it('renders a skip link, primary navigation and a focusable main region', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('a.skip-link')?.getAttribute('href')).toBe('#main');
    expect(el.querySelector('nav[aria-label="Primary"]')).not.toBeNull();
    expect(el.querySelector('main#main')?.getAttribute('tabindex')).toBe('-1');
  });

  it('toggles the mobile menu and reflects it in aria-expanded', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.menu-toggle');
    expect(button.getAttribute('aria-expanded')).toBe('false');

    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('#primary-nav').classList).toContain('open');
  });

  it('offers sign-in when signed out', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('a[href="/login"]')?.textContent?.trim()).toBe('Sign in');
    expect(el.querySelector('a[href="/register"]')?.textContent?.trim()).toBe('Create account');
    expect(el.querySelector('a[href="/account"]')).toBeNull();
  });

  it("shows the user's name, role, area link and sign-out when signed in", () => {
    user.set({ id: 'u', name: 'Vikram Shah', role: 'FINANCIER' });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('.who')?.textContent).toContain('Vikram Shah');
    expect(el.querySelector('.who .badge')?.textContent?.trim()).toBe('Financier');
    expect(el.querySelector('a[href="/financier"]')?.textContent?.trim()).toBe('Financier workspace');
    expect(el.querySelector('a[href="/account"]')).not.toBeNull();
    expect(el.querySelector('a[href="/login"]')).toBeNull();
    expect(el.querySelector('a[href="/register"]')).toBeNull();

    const signOutButton = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Sign out');
    signOutButton?.click();
    expect(signOut).toHaveBeenCalled();
  });
});
