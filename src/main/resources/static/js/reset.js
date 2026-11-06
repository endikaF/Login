import { errorText, say, takeTokenFromUrl } from './api.js';

const msg = document.getElementById('msg');
const form = document.getElementById('form');
// El token llega en el fragmento de la URL (no se envió al servidor al abrir la página) y se borra de la barra.
const token = takeTokenFromUrl();

if (!token) {
  form.hidden = true;
  say(msg, 'El enlace no es válido. Pide uno nuevo desde «¿Has olvidado la contraseña?».', 'error');
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  say(msg, 'Guardando…', 'ok');
  const response = await fetch('/api/auth/reset-password', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, newPassword: document.getElementById('password').value }),
  });
  if (response.ok) {
    form.hidden = true;
    say(msg, (await response.json()).message, 'ok');
  } else {
    say(msg, await errorText(response, 'No se pudo cambiar la contraseña.'), 'error');
  }
});
