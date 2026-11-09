# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Static HTML/CSS/JS servido por Spring Boot desde `src/main/resources/static/`. Sin framework, sin bundler y sin dependencias nuevas (la regla del proyecto exige preguntar antes de añadir una). Módulos ES nativos en `js/`.

## Users

Personas con cuenta propia que guardan notas personales y privadas (rol USER) y administradores que gestionan cuentas (rol ADMIN). Entran desde el navegador, en escritorio o móvil, con sesión corta y renovable. Algunos tienen verificación en dos pasos (TOTP) activada.

## Product Purpose

Tablero personal de notas cuyo núcleo es la autenticación y la autorización: cada usuario solo ve y borra sus propias notas. Debe percibirse como un producto real y fiable, no como una demo. Éxito: entrar, escribir y encontrar notas sin fricción, y que las pantallas de seguridad (2FA, recuperación, administración) transmitan control y confianza.

## Positioning

Notas privadas por cuenta con seguridad seria de verdad (sesiones rotatorias, 2FA con códigos de recuperación, panel de administración, bloqueo progresivo). La interfaz debe hacer visible esa solidez sin enseñar jerga.

## Operating Context

Flujos: crear cuenta, verificar email por enlace, iniciar sesión (con segundo paso opcional), recuperar y restablecer contraseña, crear/listar/borrar notas, gestionar el 2FA, y (admin) activar/desactivar cuentas, cambiar roles, cerrar sesiones y quitar el 2FA. Los emails son simulados (salen por consola).

## Capabilities and Constraints

- CSP estricta: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:`. Sin estilos ni scripts en línea, sin `style=`, sin manejadores `on*`, sin fuentes ni recursos externos.
- Rutas estáticas permitidas: `/`, `/*.html`, `/css/**`, `/js/**` (cualquier carpeta nueva exige tocar la configuración de seguridad).
- El JS no puede usar `innerHTML`/`outerHTML`/`eval`/`localStorage`, ni mencionarlos en comentarios; el contenido dinámico se inserta con `textContent`. Hay tests que lo vigilan.
- Los IDs usados por los scripts (`login-form`, `note-form`, `users`, `who`, `logout`, etc.) deben conservarse o actualizarse a la vez en el JS.
- Contraste mínimo AA en claro y oscuro (ya existía `prefers-color-scheme`).
- Idioma: español.

## Brand Commitments

Nombre actual: «Tablero de notas». No hay logotipo, voz de marca ni referencias vinculantes. El usuario pide el aspecto más profesional posible, «como un producto real», y rechaza lo monótono y lo que parece hecho con IA.

## Evidence on Hand

Contenido real: notas del usuario (título ≤100 car., texto ≤5000, fecha `createdAt`), cuentas con rol/estado/2FA. No existen testimonios, clientes, métricas ni precios; no se deben inventar.

## Product Principles

1. La seguridad se demuestra con claridad y control, no con alarmas.
2. Cada pantalla tiene una tarea principal evidente.
3. Estados completos: vacío, carga, error, éxito, deshabilitado.
4. Accesible por teclado y lector de pantalla; respeta `prefers-reduced-motion` y el tema del sistema.
5. Sin afirmaciones que el producto no cumpla.

## Accessibility & Inclusion

Contraste AA, foco visible, objetivos táctiles cómodos, formularios con etiqueta visible y errores asociados a su campo, movimiento reducido respetado.
