import { api, errorText, requireSession, say, setupShell } from './api.js';

const msg = document.getElementById('msg');
const tbody = document.getElementById('users');
let myEmail = null;

/** Etiqueta de estado: el texto lleva el significado; el color y el punto solo lo acompañan. */
function chip(text, kind) {
  const span = document.createElement('span');
  span.className = 'chip ' + kind;
  span.textContent = text;
  return span;
}

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
    // Email con su inicial
    const userCell = document.createElement('td');
    const who = document.createElement('div');
    who.className = 'user-cell';
    const initial = document.createElement('span');
    initial.className = 'avatar';
    initial.setAttribute('aria-hidden', 'true');
    initial.textContent = user.email.charAt(0);
    const email = document.createElement('span');
    email.textContent = user.email;
    who.append(initial, email);
    userCell.append(who);

    // Rol y estado como etiquetas: cada una dice su texto, el color solo acompaña
    const roleCell = document.createElement('td');
    roleCell.dataset.label = 'Rol';
    roleCell.append(chip(user.role === 'ADMIN' ? 'Administrador' : 'Usuario', user.role === 'ADMIN' ? 'role-admin plain' : 'plain'));

    const stateCell = document.createElement('td');
    stateCell.dataset.label = 'Estado';
    const chips = document.createElement('div');
    chips.className = 'chips';
    chips.append(chip(user.enabled ? 'Activa' : 'Desactivada', user.enabled ? 'ok' : 'bad'));
    if (!user.emailVerified) chips.append(chip('Sin verificar', 'warn'));
    if (user.locked) chips.append(chip('Bloqueada', 'bad'));
    if (user.totpEnabled) chips.append(chip('2FA', 'ok'));
    stateCell.append(chips);

    const actions = document.createElement('td');
    actions.dataset.label = 'Acciones';
    const buttons = document.createElement('div');
    buttons.className = 'actions';
    actions.append(buttons);
    const selfHint = 'No puedes aplicártelo a ti mismo';
    buttons.append(
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
      buttons.append(button('Quitar 2FA', () => {
        if (confirm('¿Quitar la verificación en dos pasos a ' + user.email + '? Hazlo solo si has comprobado su identidad.')) {
          act('/api/admin/users/' + user.id + '/totp/reset', undefined, 'Verificación en dos pasos eliminada.');
        }
      }, { danger: true }));
    }
    row.append(userCell, roleCell, stateCell, actions);
    tbody.append(row);
  }
}

async function load() {
  const response = await api('/api/admin/users');
  if (response.ok) render(await response.json());
  else say(msg, await errorText(response, 'No se pudo cargar la lista.'), 'error');
}

const me = await requireSession();
if (me) {
  // El servidor es quien manda (devuelve 403 a un USER); esta redirección solo evita mostrar una pantalla vacía.
  if (me.role !== 'ADMIN') location.replace('notes.html');
  else { setupShell(me); myEmail = me.email; await load(); }
}
