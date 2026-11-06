import { errorText, say, setAccessToken } from './api.js';

const loginForm = document.getElementById('login-form');
const loginMsg = document.getElementById('login-msg');
const registerForm = document.getElementById('register-form');
const registerMsg = document.getElementById('register-msg');

async function post(path, body) {
  return fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  say(loginMsg, 'Comprobando…', 'ok');
  const response = await post('/api/auth/login', {
    email: document.getElementById('login-email').value,
    password: document.getElementById('login-password').value,
  });
  if (response.ok) {
    setAccessToken((await response.json()).accessToken);
    location.assign('notes.html'); // la cookie del refresh ya está fijada; la página siguiente recupera el token
    return;
  }
  say(loginMsg, await errorText(response, 'No se pudo iniciar sesión.'), 'error');
});

registerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  say(registerMsg, 'Enviando…', 'ok');
  const response = await post('/api/auth/register', {
    email: document.getElementById('register-email').value,
    password: document.getElementById('register-password').value,
  });
  if (response.ok) {
    registerForm.reset();
    // El servidor responde igual exista o no el email; aquí no se afirma nada más.
    say(registerMsg, (await response.json()).message, 'ok');
    return;
  }
  say(registerMsg, await errorText(response, 'No se pudo completar el registro.'), 'error');
});
