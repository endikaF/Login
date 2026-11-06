import { api, errorText, logout, requireSession, say } from './api.js';

const msg = document.getElementById('msg');
const statusText = document.getElementById('status');
const enableSection = document.getElementById('enable-section');
const disableSection = document.getElementById('disable-section');
const recoverySection = document.getElementById('recovery-section');
const confirmBlock = document.getElementById('confirm-block');
const setupForm = document.getElementById('setup-form');

function showState(enabled) {
  statusText.textContent = enabled ? 'Estado: ACTIVADA.' : 'Estado: desactivada.';
  enableSection.hidden = enabled;
  disableSection.hidden = !enabled;
}

setupForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const response = await api('/api/auth/totp/setup', {
    method: 'POST',
    body: { password: document.getElementById('setup-password').value },
  });
  if (!response.ok) { say(msg, await errorText(response, 'No se pudo generar la clave.'), 'error'); return; }
  const data = await response.json();
  document.getElementById('secret').textContent = data.secret;
  document.getElementById('otpauth-link').setAttribute('href', data.otpauthUri);
  setupForm.reset(); // la contraseña no se queda en el formulario
  confirmBlock.hidden = false;
  say(msg, 'Clave generada. Añádela a tu app y confirma con un código.', 'ok');
});

document.getElementById('enable-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const response = await api('/api/auth/totp/enable', {
    method: 'POST',
    body: { code: document.getElementById('enable-code').value },
  });
  if (!response.ok) { say(msg, await errorText(response, 'Código incorrecto.'), 'error'); return; }
  const { recoveryCodes } = await response.json();
  document.getElementById('recovery-codes').textContent = recoveryCodes.join('\n'); // texto, nunca HTML
  recoverySection.hidden = false;
  confirmBlock.hidden = true;
  event.target.reset();
  document.getElementById('secret').textContent = ''; // el secreto ya no se necesita en pantalla
  showState(true);
  say(msg, 'Verificación en dos pasos activada. Guarda tus códigos de recuperación.', 'ok');
  document.getElementById('recovery-codes').focus();
});

document.getElementById('disable-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!confirm('¿Desactivar la verificación en dos pasos?')) return;
  const response = await api('/api/auth/totp/disable', {
    method: 'POST',
    body: {
      password: document.getElementById('disable-password').value,
      code: document.getElementById('disable-code').value,
    },
  });
  if (!response.ok) { say(msg, await errorText(response, 'No se pudo desactivar.'), 'error'); return; }
  event.target.reset();
  recoverySection.hidden = true;
  showState(false);
  say(msg, 'Verificación en dos pasos desactivada.', 'ok');
});

document.getElementById('logout').addEventListener('click', logout);

const me = await requireSession();
if (me) {
  // Rellena los campos de usuario ocultos con el email de la cuenta (para gestores de contraseñas).
  document.querySelectorAll('input[name="username"]').forEach((input) => { input.value = me.email; });
  showState(me.totpEnabled);
}
