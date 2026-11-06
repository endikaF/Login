import { api, errorText, logout, requireSession, say } from './api.js';

const msg = document.getElementById('msg');
const tbody = document.getElementById('users');
let myEmail = null;

function button(label, onClick, { danger = false, disabled = false, title = '' } = {}) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = label;
  b.className = danger ? 'danger' : 'secondary';
  b.disabled = disabled;
  if (title) b.title = title;
  b.addEventListener('click', onClick);
  return b;
}

async function act(path, body, okText) {
  const response = await api(path, { method: 'POST', body });
  if (response.ok) { say(msg, okText, 'ok'); await load(); }
  else say(msg, await errorText(response, 'No se pudo completar la acción.'), 'error');
}

function render(users) {
  tbody.replaceChildren();
  for (const user of users) {
    const isMe = user.email === myEmail;
    const row = document.createElement('tr');
    const cells = [user.email, user.role,
      (user.enabled ? 'Activa' : 'Desactivada') + (user.emailVerified ? '' : ' · sin verificar') + (user.locked ? ' · bloqueada' : '')
        + (user.totpEnabled ? ' · 2FA' : '')];
    for (const text of cells) {
      const td = document.createElement('td');
      td.textContent = text;
      row.append(td);
    }
    const actions = document.createElement('td');
    const selfHint = 'No puedes aplicártelo a ti mismo';
    actions.append(
      button(user.enabled ? 'Desactivar' : 'Activar', () => {
        if (!user.enabled || confirm('¿Desactivar la cuenta de ' + user.email + '? Se cerrarán sus sesiones.')) {
          act('/api/admin/users/' + user.id + '/enabled', { enabled: !user.enabled },
            user.enabled ? 'Cuenta desactivada.' : 'Cuenta activada.');
        }
      }, { danger: user.enabled, disabled: isMe && user.enabled, title: isMe ? selfHint : '' }),
      button(user.role === 'ADMIN' ? 'Quitar admin' : 'Hacer admin', () => {
        const next = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
        if (confirm('¿Cambiar a ' + user.email + ' al rol ' + next + '?')) {
          act('/api/admin/users/' + user.id + '/role', { role: next }, 'Rol actualizado.');
        }
      }, { disabled: isMe && user.role === 'ADMIN', title: isMe ? selfHint : '' }),
      button('Cerrar sus sesiones', () => act('/api/admin/users/' + user.id + '/logout', undefined, 'Sesiones cerradas.')),
    );
    if (user.totpEnabled) {
      actions.append(button('Quitar 2FA', () => {
        if (confirm('¿Quitar la verificación en dos pasos a ' + user.email + '? Hazlo solo si has comprobado su identidad.')) {
          act('/api/admin/users/' + user.id + '/totp/reset', undefined, 'Verificación en dos pasos eliminada.');
        }
      }, { danger: true }));
    }
    row.append(actions);
    tbody.append(row);
  }
}

async function load() {
  const response = await api('/api/admin/users');
  if (response.ok) render(await response.json());
  else say(msg, await errorText(response, 'No se pudo cargar la lista.'), 'error');
}

document.getElementById('logout').addEventListener('click', logout);

const me = await requireSession();
if (me) {
  // El servidor es quien manda (devuelve 403 a un USER); esta redirección solo evita mostrar una pantalla vacía.
  if (me.role !== 'ADMIN') location.replace('notes.html');
  else { myEmail = me.email; await load(); }
}
