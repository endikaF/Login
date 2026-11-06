import { api, errorText, logout, requireSession, say } from './api.js';

const msg = document.getElementById('msg');
const list = document.getElementById('notes');
const empty = document.getElementById('empty');

function render(notes) {
  list.replaceChildren();
  empty.hidden = notes.length > 0;
  for (const note of notes) {
    // Todo se inserta con textContent: aunque una nota contenga "<script>", se muestra como texto y no se ejecuta.
    const item = document.createElement('li');
    const title = document.createElement('h3');
    title.textContent = note.title;
    const body = document.createElement('p');
    body.textContent = note.body;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger';
    remove.textContent = 'Borrar';
    remove.setAttribute('aria-label', 'Borrar la nota ' + note.title);
    remove.addEventListener('click', () => removeNote(note.id));
    item.append(title, body, remove);
    list.append(item);
  }
}

async function load() {
  const response = await api('/api/notes');
  if (response.ok) render(await response.json());
  else say(msg, await errorText(response, 'No se pudieron cargar las notas.'), 'error');
}

async function removeNote(id) {
  const response = await api('/api/notes/' + encodeURIComponent(id), { method: 'DELETE' });
  if (response.ok) { say(msg, 'Nota borrada.', 'ok'); await load(); }
  else say(msg, await errorText(response, 'No se pudo borrar la nota.'), 'error');
}

document.getElementById('note-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const response = await api('/api/notes', {
    method: 'POST',
    body: { title: document.getElementById('note-title').value, body: document.getElementById('note-body').value },
  });
  if (response.ok) { event.target.reset(); say(msg, 'Nota guardada.', 'ok'); await load(); }
  else say(msg, await errorText(response, 'No se pudo guardar la nota.'), 'error');
});

document.getElementById('logout').addEventListener('click', logout);
document.getElementById('logout-all').addEventListener('click', async () => {
  await api('/api/auth/logout-all', { method: 'POST' });
  location.replace('index.html');
});

const me = await requireSession();
if (me) {
  document.getElementById('who').textContent = me.email;
  // Mostrar el enlace es solo comodidad: la seguridad real la impone el servidor en /api/admin/**.
  document.getElementById('admin-link').hidden = me.role !== 'ADMIN';
  await load();
}
