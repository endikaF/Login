import { errorText, say, takeTokenFromUrl } from './api.js';

const msg = document.getElementById('msg');
const confirmButton = document.getElementById('confirm');
const token = takeTokenFromUrl();

if (!token) {
  confirmButton.hidden = true;
  say(msg, 'El enlace no es válido. Puedes registrarte de nuevo para recibir otro.', 'error');
}

// Se pide un clic en lugar de verificar solo al abrir la página: así un antivirus o un "previsualizador" de
// enlaces que abra el correo no consume el token de un solo uso sin que el usuario haya hecho nada.
confirmButton.addEventListener('click', async () => {
  say(msg, 'Verificando…', 'ok');
  const response = await fetch('/api/auth/verify-email', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });
  if (response.ok) {
    confirmButton.hidden = true;
    say(msg, (await response.json()).message, 'ok');
  } else {
    say(msg, await errorText(response, 'No se pudo verificar el email.'), 'error');
  }
});
