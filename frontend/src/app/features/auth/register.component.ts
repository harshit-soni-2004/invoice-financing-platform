import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { passwordViolation } from './password-policy';
import {
  RegisteredAccount,
  RegistrationError,
  RegistrationFailure,
  RegistrationField,
  RegistrationService,
  SelfRegistrableRole,
} from './registration.service';

type Field = 'role' | 'displayName' | 'email' | 'password' | 'confirmPassword';

/** DOM order, so the first invalid field gets focus. */
const FIELD_IDS: readonly [Field, string][] = [
  ['role', 'reg-role-seller'],
  ['displayName', 'reg-name'],
  ['email', 'reg-email'],
  ['password', 'reg-password'],
  ['confirmPassword', 'reg-confirm'],
];

const LABELS: Readonly<Record<RegistrationField, string>> = {
  role: 'Account type',
  displayName: 'Full name',
  email: 'Email',
  password: 'Password',
};

interface FormError {
  message: string;
  offerSignIn?: boolean;
}

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

/** auth-service trims nothing before its @Email check, so the app validates and sends the trimmed address. */
function trimmedEmail(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value.trim();
  return value === '' ? null : Validators.email(new FormControl(value));
}

function acceptablePassword(control: AbstractControl<string>): ValidationErrors | null {
  const violation = passwordViolation(control.value, control.parent?.get('email')?.value ?? '');
  return violation ? { policy: violation } : null;
}

function matchingPassword(control: AbstractControl<string>): ValidationErrors | null {
  if (control.value === '') {
    return { required: true };
  }
  return control.value === control.parent?.get('password')?.value ? null : { mismatch: true };
}

@Component({
  selector: 'ifp-register',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private readonly registration = inject(RegistrationService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly form = this.fb.group({
    role: this.fb.control<'' | SelfRegistrableRole>('', Validators.required),
    displayName: this.fb.control('', [notBlank, Validators.maxLength(100)]),
    email: this.fb.control('', [notBlank, trimmedEmail, Validators.maxLength(254)]),
    password: this.fb.control('', acceptablePassword),
    confirmPassword: this.fb.control('', matchingPassword),
  });

  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly showPasswords = signal(false);
  protected readonly formError = signal<FormError | null>(null);
  protected readonly registered = signal<RegisteredAccount | null>(null);

  private readonly errorSummary = viewChild<ElementRef<HTMLElement>>('errorSummary');
  private readonly outcomeHeading = viewChild<ElementRef<HTMLElement>>('outcomeHeading');

  constructor() {
    const { email, password, confirmPassword } = this.form.controls;
    email.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => password.updateValueAndValidity({ emitEvent: false }));
    password.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => confirmPassword.updateValueAndValidity({ emitEvent: false }));
  }

  protected error(field: Field): string | null {
    const control = this.form.controls[field];
    if (!control.errors || !(control.touched || this.submitted())) {
      return null;
    }
    const errors = control.errors;
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    switch (field) {
      case 'role':
        return 'Choose whether you are a seller or a financier.';
      case 'displayName':
        return errors['maxlength'] ? 'Full name must be at most 100 characters.' : 'Enter your full name.';
      case 'email':
        if (errors['required']) {
          return 'Enter your email address.';
        }
        return errors['maxlength']
          ? 'Email must be at most 254 characters.'
          : 'Enter a valid email address, like name@example.com.';
      case 'password':
        return `Password ${errors['policy'] ?? 'is not acceptable'}.`;
      case 'confirmPassword':
        return errors['required'] ? 'Re-enter your password.' : 'The passwords do not match.';
    }
  }

  protected describedBy(field: Field, hintId?: string): string | null {
    const ids = [hintId, this.error(field) ? `${this.idFor(field)}-error` : undefined].filter(Boolean);
    return ids.length > 0 ? ids.join(' ') : null;
  }

  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.submitted.set(true);
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalid();
      return;
    }

    const value = this.form.getRawValue();
    if (value.role === '') {
      return;
    }
    this.submitting.set(true);
    try {
      const account = await this.registration.register({
        role: value.role,
        displayName: value.displayName.trim(),
        email: value.email.trim(),
        password: value.password,
      });
      this.form.reset();
      this.registered.set(account);
      afterNextRender(() => this.outcomeHeading()?.nativeElement.focus(), { injector: this.injector });
    } catch (error) {
      this.showFailure(error instanceof RegistrationError ? error.failure : { kind: 'unexpected' });
    } finally {
      this.submitting.set(false);
    }
  }

  private showFailure(failure: RegistrationFailure): void {
    switch (failure.kind) {
      case 'invalid': {
        const fields = Object.entries(failure.fieldErrors) as [RegistrationField, string][];
        for (const [field, message] of fields) {
          const control = this.form.controls[field];
          control.setErrors({ ...control.errors, server: `${LABELS[field]} ${message}.` });
          control.markAsTouched();
        }
        if (fields.length > 0) {
          this.formError.set({ message: 'Some details were not accepted. Check the highlighted fields.' });
          this.focusFirstInvalid();
          return;
        }
        this.showFormError({ message: 'The registration was not accepted. Check your details and try again.' });
        return;
      }
      case 'duplicate-email': {
        const email = this.form.controls.email;
        email.setErrors({ ...email.errors, server: 'An account with this email already exists.' });
        email.markAsTouched();
        this.showFormError({
          message: 'An account with this email already exists. Sign in, or register with another email address.',
          offerSignIn: true,
        });
        return;
      }
      case 'rate-limited':
        this.showFormError({
          message:
            failure.retryAfterSeconds !== null
              ? `Too many sign-up attempts from this network. Wait ${waitTime(failure.retryAfterSeconds)} and try again.`
              : 'Too many sign-up attempts from this network, or sign-up is briefly unavailable. Wait a minute and try again.',
        });
        return;
      case 'network':
        this.showFormError({ message: 'The server could not be reached. Check your connection and try again.' });
        return;
      case 'unavailable':
        this.showFormError({ message: 'Sign-up is temporarily unavailable. Please try again in a few minutes.' });
        return;
      case 'unexpected':
        this.showFormError({
          message:
            'Something went wrong, and the account may or may not have been created. Try signing in, or try again later.',
          offerSignIn: true,
        });
        return;
    }
  }

  private showFormError(error: FormError): void {
    this.formError.set(error);
    afterNextRender(() => this.errorSummary()?.nativeElement.focus(), { injector: this.injector });
  }

  private focusFirstInvalid(): void {
    const first = FIELD_IDS.find(([field]) => this.form.controls[field].invalid);
    if (first) {
      afterNextRender(() => this.document.getElementById(first[1])?.focus(), { injector: this.injector });
    }
  }

  private idFor(field: Field): string {
    return field === 'role' ? 'reg-role' : (FIELD_IDS.find(([name]) => name === field)?.[1] ?? field);
  }
}

function waitTime(seconds: number): string {
  if (seconds <= 1) {
    return 'a moment';
  }
  if (seconds < 90) {
    return `${seconds} seconds`;
  }
  return `${Math.ceil(seconds / 60)} minutes`;
}
