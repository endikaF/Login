import { errorText, say } from './api.js';

const msg = document.getElementById('msg');

document.getElementById('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  say(msg, 'Enviando…', 'ok');
  const response = await fetch('/api/auth/forgot-password', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: document.getElementById('email').value }),
  });
  // Mismo mensaje exista o no la cuenta (el servidor responde igual): no se revela qué emails están registrados.
  if (response.ok) say(msg, (await response.json()).message, 'ok');
  else say(msg, await errorText(response, 'No se pudo enviar la solicitud.'), 'error');
});
