import { errorText, say, setAccessToken } from './api.js';

const loginForm = document.getElementById('login-form');
const loginMsg = document.getElementById('login-msg');
const mfaSection = document.getElementById('mfa-section');
const mfaForm = document.getElementById('mfa-form');
const mfaMsg = document.getElementById('mfa-msg');
let mfaToken = null;
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
    const data = await response.json();
    if (data.mfaRequired) {
      // Contraseña correcta, pero hace falta el segundo factor. El token temporal solo vive en memoria.
      mfaToken = data.mfaToken;
      loginForm.reset();
      loginForm.hidden = true;
      mfaSection.hidden = false;
      say(loginMsg, '', 'ok');
      document.getElementById('mfa-code').focus();
      return;
    }
    setAccessToken(data.accessToken);
    location.assign('notes.html'); // la cookie del refresh ya está fijada; la página siguiente recupera el token
    return;
  }
  say(loginMsg, await errorText(response, 'No se pudo iniciar sesión.'), 'error');
});

mfaForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  say(mfaMsg, 'Comprobando…', 'ok');
  const response = await post('/api/auth/login/totp', { mfaToken, code: document.getElementById('mfa-code').value });
  if (response.ok) {
    setAccessToken((await response.json()).accessToken);
    location.assign('notes.html');
    return;
  }
  say(mfaMsg, await errorText(response, 'Código incorrecto o caducado. Vuelve a iniciar sesión.'), 'error');
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
