// Show/hide toggle for the password field. Without JavaScript the button stays hidden and the form still works.
document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.getElementById('password-toggle');
  const input = document.getElementById('password');
  if (!toggle || !input) {
    return;
  }
  toggle.hidden = false;
  toggle.addEventListener('click', () => {
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    toggle.setAttribute('aria-pressed', String(show));
    toggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    input.focus();
  });
});
