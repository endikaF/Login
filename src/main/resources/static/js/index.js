import { errorText, say, setAccessToken } from './api.js';

const loginForm = document.getElementById('login-form');
const loginMsg = document.getElementById('login-msg');
const mfaSection = document.getElementById('mfa-section');
const mfaForm = document.getElementById('mfa-form');
const mfaMsg = document.getElementById('mfa-msg');
let mfaToken = null;
const registerForm = document.getElementById('register-form');
const registerMsg = document.getElementById('register-msg');

// Selector «Entrar / Crear cuenta»: patrón de pestañas con flechas del teclado (solo la pestaña activa entra en el orden de Tab).
const seg = document.getElementById('seg');
const tabs = [document.getElementById('tab-login'), document.getElementById('tab-register')];
const panels = [document.getElementById('panel-login'), document.getElementById('panel-register')];

function selectTab(index, { focus = false } = {}) {
  tabs.forEach((tab, i) => {
    tab.setAttribute('aria-selected', String(i === index));
    tab.tabIndex = i === index ? 0 : -1;
    panels[i].hidden = i !== index;
  });
  if (focus) tabs[index].focus();
}
tabs.forEach((tab, i) => {
  tab.addEventListener('click', () => selectTab(i));
  tab.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault();
      selectTab(i === 0 ? 1 : 0, { focus: true });
    }
  });
});

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
      seg.hidden = true;
      panels.forEach((panel) => { panel.hidden = true; });
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
