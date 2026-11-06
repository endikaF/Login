// Utilidades compartidas por todas las páginas.
//
// El access token vive SOLO en una variable de memoria (nunca en el almacenamiento del navegador ni en una cookie legible por JS):
// un XSS no puede llevárselo para usarlo más tarde. Al cambiar de página se pierde y se recupera con la cookie
// HttpOnly del refresh token. Cada recuperación ROTA el refresh token.
let accessToken = null;
let refreshing = null;

export async function refreshSession() {
  // Una sola renovación a la vez por página: dos /refresh simultáneos con el mismo token se verían como un robo
  // (reutilización) y el servidor cerraría la sesión.
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
      .then(async (r) => {
        if (!r.ok) { accessToken = null; return false; }
        accessToken = (await r.json()).accessToken;
        return true;
      })
      .catch(() => false)
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

export async function api(path, { method = 'GET', body } = {}) {
  const send = () => fetch(path, {
    method,
    credentials: 'same-origin',
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(accessToken ? { Authorization: 'Bearer ' + accessToken } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let response = await send();
  if (response.status === 401 && (await refreshSession())) {
    response = await send(); // el access token caducó: se renovó y se reintenta una vez
  }
  return response;
}

export function setAccessToken(token) { accessToken = token; }

/** Devuelve {email, role} si hay sesión; si no, redirige al acceso. */
export async function requireSession() {
  if (!accessToken && !(await refreshSession())) { location.replace('index.html'); return null; }
  const r = await api('/api/auth/me');
  if (!r.ok) { location.replace('index.html'); return null; }
  return r.json();
}

export async function logout() {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
  location.replace('index.html');
}

/** Muestra un mensaje accesible: los errores se anuncian con role=alert, el resto con role=status. */
export function say(element, text, kind = 'ok') {
  element.textContent = text; // textContent y no HTML: el texto no se interpreta como HTML
  element.className = 'msg ' + kind;
  element.setAttribute('role', kind === 'error' ? 'alert' : 'status');
}

export async function errorText(response, fallback) {
  if (response.status === 429) {
    const seconds = response.headers.get('Retry-After');
    return 'Demasiados intentos. Espera ' + (seconds ? seconds + ' s' : 'un momento') + ' e inténtalo de nuevo.';
  }
  try { return (await response.json()).message || fallback; } catch { return fallback; }
}

/** Token del enlace del email (va en el fragmento #token=...); se borra de la barra de direcciones al leerlo. */
export function takeTokenFromUrl() {
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  history.replaceState(null, '', location.pathname);
  return token;
}
