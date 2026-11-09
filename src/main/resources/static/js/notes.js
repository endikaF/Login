import { api, errorText, requireSession, say, setupShell } from './api.js';

const msg = document.getElementById('msg');
const list = document.getElementById('notes');
const empty = document.getElementById('empty');
const count = document.getElementById('count');
const dateFormat = new Intl.DateTimeFormat('es', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

function when(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

function render(notes) {
  list.replaceChildren();
  empty.hidden = notes.length > 0;
  count.textContent = notes.length === 0 ? '' : notes.length + (notes.length === 1 ? ' nota' : ' notas');
  for (const note of notes) {
    // Todo se inserta con textContent: aunque una nota contenga "<script>", se muestra como texto y no se ejecuta.
    const item = document.createElement('li');
    const title = document.createElement('h3');
    title.textContent = note.title;
    const body = document.createElement('p');
    body.textContent = note.body;

    const foot = document.createElement('div');
    foot.className = 'foot';
    const time = document.createElement('time');
    const created = when(note.createdAt);
    if (created) { time.dateTime = created.toISOString(); time.textContent = dateFormat.format(created); }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'danger';
    remove.textContent = 'Borrar';
    remove.setAttribute('aria-label', 'Borrar la nota ' + note.title);
    remove.addEventListener('click', () => removeNote(note.id));
    foot.append(time, remove);

    item.append(title, body, foot);
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

const me = await requireSession();
if (me) {
  setupShell(me);
  await load();
  // La entrada escalonada solo se ve al abrir la página; al guardar o borrar, la lista no vuelve a animarse.
  setTimeout(() => list.classList.remove('enter'), 900);
}
