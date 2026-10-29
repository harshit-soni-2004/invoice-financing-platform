import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './core/auth/auth.service';
import { SessionUser } from './core/auth/id-token';
import { NotificationBadgeService } from './features/notifications/notification-badge.service';

describe('AppComponent', () => {
  const user = signal<SessionUser | null>(null);
  const signOut = jasmine.createSpy('signOut');
  const badgeLabel = signal<string | null>(null);
  const refresh = jasmine.createSpy('refresh').and.resolveTo();
  const refreshIfStale = jasmine.createSpy('refreshIfStale').and.resolveTo();

  beforeEach(async () => {
    user.set(null);
    badgeLabel.set(null);
    signOut.calls.reset();
    refresh.calls.reset();
    refreshIfStale.calls.reset();
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { user, signOut } },
        { provide: NotificationBadgeService, useValue: { label: badgeLabel, refresh, refreshIfStale } },
      ],
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

  it('does not check notifications for visitors', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(refresh).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('a[href="/notifications"]')).toBeNull();
  });

  it('links to notifications and shows the new count in the link and on the phone menu button', () => {
    user.set({ id: 'u', name: 'Asha Rao', role: 'SELLER' });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(refresh).toHaveBeenCalledTimes(1);

    const link = el.querySelector('a[href="/notifications"]')!;
    expect(link.textContent?.trim()).toBe('Notifications');
    expect(el.querySelector('.menu-toggle .nav-count')).toBeNull();

    badgeLabel.set('3');
    fixture.detectChanges();
    expect(link.querySelector('.nav-count')?.textContent?.trim()).toBe('3 new');
    expect(el.querySelector('.menu-toggle')?.textContent).toContain('3 new notifications');
  });
});
