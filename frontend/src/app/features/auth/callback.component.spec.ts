import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthError, AuthService } from '../../core/auth/auth.service';
import { CallbackComponent } from './callback.component';

describe('CallbackComponent', () => {
  let completeSignIn: jasmine.Spy;
  let router: Router;

  beforeEach(() => {
    completeSignIn = jasmine.createSpy('completeSignIn');
    TestBed.configureTestingModule({
      imports: [CallbackComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { completeSignIn } }],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
  });

  async function render(params: Record<string, string>): Promise<ComponentFixture<CallbackComponent>> {
    const fixture = TestBed.createComponent(CallbackComponent);
    for (const [name, value] of Object.entries(params)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    return fixture;
  }

  it('completes the sign-in and replaces the callback URL in history', async () => {
    completeSignIn.and.resolveTo('/account');
    await render({ code: 'c', state: 's' });
    expect(completeSignIn).toHaveBeenCalledWith({ code: 'c', state: 's', error: undefined });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/account', { replaceUrl: true });
  });

  it('explains a failed sign-in and offers to start again', async () => {
    completeSignIn.and.rejectWith(new AuthError('Sign-in was cancelled or refused.'));
    const fixture = await render({ error: 'access_denied' });
    const alert: HTMLElement = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert.textContent).toContain('Sign-in was cancelled or refused.');
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
