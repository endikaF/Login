# Notas de traspaso — Proyecto Login

> **Para qué sirve este archivo:** que puedas abrir una conversación nueva con Claude y retomar el trabajo exactamente donde
> se dejó. Resume qué hay hecho, cómo está montado, las reglas de trabajo acordadas, lo que falta y las trampas ya conocidas.
> **No contiene ningún secreto**; los valores reales viven solo en tu `.env` local (ignorado por git).
>
> Última actualización: **2026-11-09** · Último commit de código: `97595d2` (rediseño visual) · Rama `main`, sincronizada con `origin/main`
> (única rama: la del rediseño, `worktree-rediseno-frontend`, ya integrada, se eliminó).

---

## 1. Resumen en 30 segundos

- **Qué es:** web con backend Java 21 / Spring Boot 3.5.16 / Spring Security 6, cuyo núcleo es la **autenticación y
  autorización de varios usuarios**. El tema es un tablero personal de notas: solo existe para tener un recurso privado por
  usuario con el que probar la autorización.
- **Estado:** **proyecto completo.** Las fases 0 a 6 del plan están hechas y además el 2FA opcional (TOTP) y el **rediseño visual
  del frontend** (libreta naranja, papel cuadriculado; sección 6). **161 tests en verde** (`mvn verify` repetido el 2026-11-09 sobre
  `main` con el rediseño incluido: `BUILD SUCCESS`, 0 fallos).
- **Todo está commiteado y subido a GitHub** (comprobado: `git status` limpio, `main` al día con `origin/main`).
- **No queda ninguna tarea obligatoria pendiente.** Solo mejoras opcionales (sección 7).

### Cómo retomar en una conversación nueva
1. Abre Claude Code **en la carpeta** `C:\Users\Endika\Desktop\Claude\Login`.
2. Pega algo así:
   > *Lee `Notas.md`, `README.md` y `SECURITY-REVIEW.md` para ponerte al día. Después ejecuta `git log --oneline -5`, `git status` y `mvn verify`
   > y confírmame que todo está en verde antes de tocar nada. Sigue las reglas de trabajo de la sección 2 de `Notas.md`.*
3. Dile qué quieres hacer (por ejemplo, una de las mejoras de la sección 7).

---

## 2. Reglas de trabajo acordadas (Claude debe respetarlas siempre)

Vienen del encargo original y de las correcciones que fuiste dando. Si algo choca con una petición nueva, **pregunta**.

**Proceso**
- Trabajo **por fases**: al terminar cada una, ejecutar sus tests, resumir y **esperar tu confirmación** antes de seguir.
- `mvn verify` debe pasar antes de cerrar una fase. **Reportar la salida real**; no afirmar que algo funciona sin haberlo ejecutado.
  Si algo no se puede verificar, decirlo explícitamente.
- **Verificar las versiones** de las dependencias en la fuente oficial (Maven Central) antes de fijarlas; no inventar versiones.
- Si un comando falla **dos veces seguidas por la misma causa**, parar y explicar el diagnóstico en vez de reintentar.

**Git**
- **Los mensajes de commit van siempre en español** (asunto y cuerpo; vale el prefijo `feat:`/`docs:`/`chore:`).
- Antes de cada commit, comprobar que **no hay secretos, `.env` ni `data/*.db`** en el área de preparación.
- **Claude nunca hace `git push`.** Lo haces tú. Tras cada commit, un recordatorio breve («Sube los cambios a GitHub»).
- Commit solo cuando tú lo pidas o confirmes la fase.

**Antes de hacer cualquiera de estas cosas, PREGUNTAR**
- Borrar archivos (incluida la carpeta `data/`).
- Añadir una dependencia que no esté ya en el stack.
- Cambiar el esquema ya creado (`schema.sql`). Añadir una tabla nueva también cuenta.
- Instalar software en el sistema.
- `git push`, o abrir puertos distintos de localhost.

**Código y seguridad**
- Solo se crea o modifica contenido **dentro de** `C:\Users\Endika\Desktop\Claude\Login`.
- **Ningún secreto en el código ni en el repositorio.** Todo por variables de entorno (`.env.example` solo con marcadores).
- **Solo JDBC** con consultas **parametrizadas** (sin JPA/Hibernate, sin concatenar SQL). Sin Docker. Sin Flyway.
- **Nada de criptografía casera:** Spring Security y el JDK.
- Entregar solo lo pedido: sin funcionalidades, abstracciones ni dependencias que no tengan justificación en un requisito.
- Transacciones cortas (SQLite admite un único escritor).

**Modo aprendizaje** (eres desarrollador en formación)
- Cada vez que se implemente algo nuevo (clase, tabla, filtro, endpoint, configuración, dependencia): explicar en 2-4 líneas
  **qué hace, por qué existe y qué ataque o problema evita.**
- Marcar como **[TRADE-OFF]** toda decisión con alternativa razonable y decir por qué se eligió la actual.
- Indicar qué es una **simplificación deliberada** de proyecto de aprendizaje y qué se haría en producción.
- Al final de cada fase: **«Qué hemos aprendido»** (máximo 6 líneas).
- **Actualizar el `README.md` al cerrar cada fase** con lo importante de esa fase.

**Estilo de las respuestas**
- En español. Resúmenes claros, con tablas cuando ayuden. Sin afirmar nada que no se haya comprobado.
- Si se publica algo o se pide algo con coste/efecto externo, confirmar primero.

---

## 3. Entorno

| Elemento | Valor |
|---|---|
| Sistema | Windows 10 Pro. Shell principal PowerShell; también hay Git Bash |
| Java | JDK **23.0.2** instalado; el proyecto se compila con `release 21` (aprobado por ti; no hace falta instalar el 21) |
| Maven | 3.9.9 |
| Base de datos | SQLite en `data/app.db` (se crea sola). Se inspecciona con **SQLiteMan** |
| Cliente `sqlite3` | Existe (3.51.1); Claude lo ha usado **solo en lectura** para inspeccionar |
| Python | Disponible; se usó para validar el TOTP con una implementación independiente |
| Navegador | Chrome, manejado a través de las herramientas `chrome-devtools` para probar la interfaz real |
| Repositorio | `origin/main` en GitHub. Hay un commit inicial anterior a nuestro trabajo |

**Versiones fijadas** (verificadas en Maven Central el 2026-11-06): Spring Boot **3.5.16** (la 4.x existe, pero el encargo pedía 3.x),
`sqlite-jdbc` **3.54.1.0**, BouncyCastle `bcprov-jdk18on` **1.86**, Bucket4j `bucket4j_jdk17-core` **8.22.0**,
`spring-security-oauth2-jose` (versión gestionada por Spring Boot).

**Variables de entorno obligatorias** (van en `.env`, que está en `.gitignore`; `.env.example` tiene los marcadores vacíos):

| Variable | Para qué | Si falta |
|---|---|---|
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Primer administrador, creado al arrancar si no existe | No se crea administrador |
| `JWT_SECRET` (≥ 32 caracteres) | Firma de los access token | La app **no arranca** |
| `TOTP_ENCRYPTION_KEY` (≥ 32 caracteres, distinta de la anterior) | Cifra los secretos del 2FA en la BD | La app **no arranca** |

Opcionales: `APP_COOKIE_SECURE=false` (solo desarrollo, p. ej. Safari), y cualquier propiedad `app.*` de `application.yml`.

> **Estado local de datos:** tu `.env` usa una contraseña de admin **de prueba** (la cambias por la definitiva cuando termines de
> revisar). Tu `data/app.db` contiene usuarios y notas de prueba antiguos. Para empezar limpio: para la app, borra `data\`
> (Claude debe **preguntarte antes**) y se regenera sola.

---

## 4. Cómo se arranca y se prueba

```powershell
cd C:\Users\Endika\Desktop\Claude\Login
mvn verify                 # 161 tests; usa archivos SQLite temporales, no toca data\app.db
mvn spring-boot:run        # arranca en http://localhost:8080 (solo localhost)
```
Los emails son **simulados**: aparecen en la consola de la app bajo `EMAIL SIMULADO`. La guía completa de pruebas manuales
(11 casos, IDOR, TOTP, curl, consultas SQL) está en el **README → «Guía rápida»**.

---

## 5. Qué se hizo, fase por fase

| Fase | Contenido | Commit |
|---|---|---|
| 0 | Diseño: tema, paquetes, tablas, modelo de amenazas. `.gitignore` | `9989836` (mensaje en inglés, ya publicado: no reescribir) |
| 1 | Scaffolding, SQLite + JDBC, registro, política de contraseñas, Argon2id | `69c378b` |
| 2 | Login, JWT de 10 min, refresh token rotatorio con detección de reutilización, logout, logout-all | `f4cc1fd` |
| 3 | Rate limiting (Bucket4j), bloqueo progresivo de cuentas, auditoría | `92ceaa3` |
| 4 | Verificación de email y recuperación de contraseña (token de un solo uso), email simulado | `6d92ba5` |
| 5 | Roles, panel admin, propiedad de recursos (notas), cabeceras, CORS/CSRF, frontend | `30a4b2d` |
| 6 | Revisión ASVS, límite de tamaño de cuerpos, vida absoluta de sesión, aviso de cambio de contraseña, README | `0b8e437` |
| extra | Segundo factor TOTP con códigos de recuperación | `8271e2f` |
| extra | Guía rápida de uso y pruebas en el README | `c6d3a4f` |
| extra | **Rediseño visual completo del frontend** (8 páginas, `style.css`, `setupShell()` en `api.js`, README) | `97595d2` |
| — | Este archivo (`Notas.md`) | `46f02a8` |

Contador de tests por hito: 20 → 48 → 70 → 92 → 123 → 129 → **161**.

---

## 6. Cómo está construido

### Paquetes (`src/main/java/com/example/login/`)
| Paquete | Contenido |
|---|---|
| `config/` | `SecurityConfig` (Argon2id, cadena de filtros, cabeceras, CORS, reglas de URL), `AdminBootstrap`, `RequestSizeFilter`, `ClockConfig` |
| `user/` | `User`, `Role`, `UserRepository` (JDBC), `UserService`, registro, excepciones |
| `auth/` | `AuthController/Service`, `JwtService`, `JwtAuthFilter`, refresh tokens (`RefreshTokenService/Repository`), recuperación y verificación (`AccountRecoveryController/Service`), excepciones |
| `security/` | `PasswordPolicy`, `TokenHasher`, `RateLimitService`, `LoginAttemptService` |
| `totp/` | `Totp` (RFC 6238 + Base32), `TotpService`, `TotpRepository`, `TotpSecretCipher`, `TotpController` |
| `token/` | `OneTimeTokenService` (verificación y reset) |
| `notes/` | `NoteController`, `NoteRepository` (propiedad impuesta en la consulta) |
| `admin/` | `AdminController`, `AdminService`, `AdminRepository`, `AdminExceptions` |
| `audit/` | `AuditService`, `AuditEvent` |
| `mail/` | `EmailService` (interfaz), `ConsoleEmailService`, `AccountMailer` |
| `web/` | `GlobalExceptionHandler` |

Frontend en `src/main/resources/static/`: `index`, `notes`, `security`, `admin`, `forgot-password`, `reset-password`, `verify-email`
(`.html`) + `css/style.css` + `js/*.js` (módulos, sin código en línea).

### Rediseño visual (commit `97595d2`)
- **Qué es:** nuevo sistema visual para las 8 páginas. Metáfora de **libreta de espiral**: cabecera y panel de acceso en naranja de
  cubierta (`#ff6a1a`) **siempre con tinta negra** (nunca blanco sobre naranja, por contraste), lienzo de papel cuadriculado con
  **celda fija de 24 px** como módulo de espaciado, y hojas con **microperforación** en el borde superior (degradado radial repetido).
  Claro/oscuro según el sistema, movimiento reducido respetado, tipografía del sistema.
- **Novedades de interfaz:** acceso en dos mitades con selector «Entrar / Crear cuenta» (pestañas con teclado); cabecera común con
  navegación, avatar y menú de cuenta (`setupShell()` en `js/api.js`); notas con panel de escritura, hojas con fecha y estado vacío;
  seguridad con indicador de estado y pasos numerados; admin con rol/estado como etiquetas y tabla que pasa a fichas en móvil.
- **[TRADE-OFF] Sin fuentes web:** la CSP no permite fuentes externas. En producción se alojarían en el mismo origen y se añadiría
  `font-src 'self'`. La CSP **no cambió** con el rediseño.
- **Cómo se hizo:** con la skill **Impeccable** (se deduce de sus archivos `PRODUCT.md` y `.impeccable/`), en un worktree/rama aparte (`worktree-rediseno-frontend`) que ya se integró en `main`
  y se eliminó. Su contexto de diseño se rescató a la raíz del repo:
  - `PRODUCT.md`: usuarios, propósito, restricciones (CSP, sin `innerHTML`/`localStorage`, IDs que usa el JS, contraste AA, español).
  - `.impeccable/surfaces/src-main-resources-static-index-html.md`: el **contrato de dirección** del rediseño (tesis, mundo propio,
    primer viewport, movimiento firma). Es la referencia para mantener coherente cualquier cambio visual futuro.
  - `.impeccable/mocks|questions|review/`: estado local de la herramienta (llevan su propio `.gitignore` con `*`, no se commitean).
  - **Pendiente declarado en ese contrato:** la revisión final y `DESIGN.md` (sistema de diseño derivado de lo entregado) **no se
    llegaron a generar**. Verificación tras el rediseño: `mvn verify` en verde el 2026-11-09 (161 tests, incluidos los de guardia del
    frontend) y tu prueba manual del 2026-11-08 (registro, login, TOTP, notas, logout, reseteo). **No consta** revisión
    visual de cada página (admin, verify-email, móvil, modo oscuro); los tests no cubren el aspecto.

### Tablas (`src/main/resources/schema.sql`, `CREATE TABLE IF NOT EXISTS`)
`users`, `refresh_tokens`, `one_time_tokens`, `notes`, `audit_log`, `user_totp`, `totp_recovery_codes`.

### Endpoints
- Públicos: `POST /api/auth/` `register`, `login`, `login/totp`, `refresh`, `logout`, `forgot-password`, `reset-password`, `verify-email`, `resend-verification`.
- Con sesión: `GET /api/auth/me`, `POST /api/auth/logout-all`, `POST /api/auth/totp/{setup,enable,disable}`, `GET/POST /api/notes`, `DELETE /api/notes/{id}`.
- Solo ADMIN: `GET /api/admin/users`, `POST /api/admin/users/{id}/{enabled,role,logout,totp/reset}`.

### Decisiones clave (las explicaciones largas están en el README)
- **Sesión:** access token JWT (HS256, 10 min, solo en memoria del navegador) + refresh token **opaco** en cookie
  `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`, guardado **hasheado**, rotatorio, con familia revocable y vida absoluta de 30 días.
- **El rol y el estado de la cuenta se leen de la BD en cada petición** (no van en el JWT) y `users.token_version` permite
  invalidar de golpe todos los access token. Cuesta una consulta por petición a cambio de revocación inmediata.
- **Anti-enumeración:** mismas respuestas, códigos y límites para cuenta existente e inexistente; Argon2 se ejecuta siempre.
  El aviso «verifica tu email» solo lo recibe quien ya acertó la contraseña.
- **CSRF:** el filtro de Spring está desactivado a propósito; lo sustituyen Bearer en cabecera + cookie `SameSite=Strict` + CORS restrictivo.
- **Propiedad:** el dueño sale del token; toda consulta lleva `AND user_id = ...`; 404 idéntico para «ajena» e «inexistente».
- **TOTP:** HMAC-SHA1 del JDK, 6 dígitos / 30 s / ventana ±1; secreto **cifrado** (AES-GCM) porque hay que poder leerlo; no reutilizable
  (`last_used_step` atómico); fallos cuentan para el bloqueo; token temporal `typ=mfa`; **el reseteo de contraseña por email NO quita el 2FA**
  (lo hace un admin).
- **SQLite:** pool de **una** conexión y transacciones cortas; `PRAGMA foreign_keys` activado por URL del driver.

### Convenciones de los tests (`src/test/java/...`)
- Cada clase de integración usa su **propio archivo SQLite temporal** (`TestDb.newUrl()` en `target/test-db/`).
- `TestDb.JWT_SECRET` es ficticio. `src/test/resources/config/application.properties` pone límites de rate limiting **holgados** y una
  `TOTP_ENCRYPTION_KEY` ficticia; los tests de límites los sobrescriben con valores bajos y usan `@DirtiesContext`.
- Ayudas: `AuthTestSupport` (crear usuario + login), `RecordingEmailService` (captura los emails y extrae el token del enlace),
  `TestClock` (reloj que se puede adelantar para el TOTP).
- Hay tests «de guardia»: el frontend no puede tener scripts/estilos/manejadores en línea ni usar `innerHTML`, `eval`, `localStorage`…
  (¡ni siquiera mencionarlos en un comentario de los `.js`!).

---

## 7. Lo que falta (todo opcional) y por dónde seguir

Están detalladas, con su estado, en `SECURITY-REVIEW.md`. Ordenadas por valor aproximado:

| # | Mejora | Notas / qué habría que preguntar |
|---|---|---|
| 1 | **Rechazar contraseñas filtradas o comunes** | Hueco de ASVS más claro. Opciones: lista local pequeña, o consulta k-anonymity a Have I Been Pwned (llamada externa → preguntar) |
| 2 | **Cambio de contraseña estando autenticado** (pidiendo la actual) | Hoy solo existe el reseteo por email. Sin cambios de esquema |
| 3 | **Análisis de dependencias (CVE)** | p. ej. OWASP Dependency-Check: **añade plugin/dependencia → preguntar** |
| 4 | **Limpieza de datos caducados** | Tarea programada que borre tokens caducados, usuarios sin verificar antiguos y registros viejos. Sin cambios de esquema |
| 5 | **Obligar al 2FA a los administradores** | Pequeño; decidir cómo se gestiona el primer acceso |
| 6 | **Regenerar códigos de recuperación** sin desactivar el 2FA | Sin cambios de esquema |
| 7 | **Código QR** para el TOTP | Exige una librería → **preguntar** |
| 8 | **Límite de concurrencia de Argon2** (semáforo) | Evita agotar memoria con muchos logins simultáneos |
| 9 | **Rehash de Argon2** al iniciar sesión si suben los parámetros | Sin cambios de esquema |
| 10 | **Protección del «último administrador»** | Hoy solo hay autoprotección individual |
| 11 | **`audit_log` de solo añadir** (trigger que prohíba `UPDATE/DELETE`) | Es un trigger nuevo → cambio de esquema → **preguntar** |
| 12 | **TLS detrás de un proxy** y `X-Forwarded-*` | Documentado, no implementado; abrir puertos → **preguntar** |
| 13 | **WebAuthn / passkeys** (resistente a phishing) | Proyecto aparte; dependencia nueva |
| 14 | Pasar de SQLite a **PostgreSQL**, rate limiting en **Redis**, claves asimétricas con rotación | Es «qué se haría en producción»; no es necesario aquí |

**Siguiente paso sugerido si no sabes por dónde empezar:** el nº 1 (contraseñas filtradas) y el nº 3 (CVE), que son los dos huecos
que más pesan en la revisión.

**Cosas conocidas y aceptadas** (no son bugs): el mensaje de commit de la Fase 0 está en inglés (ya publicado); no hay QR; los emails
son simulados; el rate limiting vive en memoria; varias pestañas refrescando a la vez pueden cerrar la sesión (rotación del refresh);
con 2FA, un login consume dos «fichas» del límite por cuenta.

---

## 8. Trampas ya conocidas (ahorran tiempo)

- **`curl` y la `ñ` en Git Bash:** pasa los argumentos con la página de códigos de Windows y el servidor responde 400 (correcto, no es UTF-8).
  Usar un archivo UTF-8 con `--data-binary @archivo.json`, o el navegador. En PowerShell, `curl.exe` (no `curl`).
- **Nombres en los tests:** un método auxiliar llamado `post` oculta al `post(...)` estático de MockMvc; llamarlo `send`.
- **Inyección de `CorsConfigurationSource`:** hay dos beans (el nuestro y el de Spring MVC); se usa `@Qualifier("corsConfigurationSource")`.
- **`GlobalExceptionHandler`:** su manejador de `Exception` atrapa también los errores de Spring MVC (404, 405…) y `AccessDeniedException`
  de `@PreAuthorize`; ambos están tratados aparte para no devolver 500 falsos. Un id no numérico en la ruta tiene su propio manejador.
- **Errores 403 vs 401 en el 2FA:** al fallar contraseña/código estando ya autenticado se responde 403; con 401 el frontend renovaría el
  token y reintentaría, contando el fallo dos veces.
- **Secure en cookies:** Chrome/Edge/Firefox la aceptan en `http://localhost`; **Safari no** (usar `APP_COOKIE_SECURE=false` solo en desarrollo).
- **SQLite bloqueado:** abrir `data/app.db` en SQLiteMan con la app en marcha puede dar `database is locked`.
- **Herramientas de Claude en esta máquina:** los `heredoc` largos con muchas comillas a veces fallan en Bash; es más fiable escribir un
  script a un archivo temporal y ejecutarlo. Los avisos «el archivo cambió en disco» suelen ser ediciones propias.
- **Probar la interfaz real** (JavaScript incluido) no lo cubren los tests: se hizo con Chrome vía `chrome-devtools`. Conviene repetirlo tras
  tocar el frontend.
- **Rate limiting al probar a mano:** «Demasiados intentos» es normal si se prueba muy seguido; esperar o reiniciar la app.

---

## 9. Archivos a leer primero en una conversación nueva

1. `Notas.md` (este) · 2. `README.md` (uso, decisiones y tests por fase) · 3. `SECURITY-REVIEW.md` (qué cubre y qué no) ·
4. `src/main/resources/application.yml` y `schema.sql` · 5. `config/SecurityConfig.java` (el corazón de la seguridad) ·
6. `auth/AuthService.java` (login, refresh, MFA) · 7. **Si se va a tocar el frontend:** `PRODUCT.md` y
`.impeccable/surfaces/src-main-resources-static-index-html.md` (restricciones y dirección visual del rediseño).

Nota: Claude también guarda una memoria automática del proyecto (commits en español, README en cada fase, el push lo haces tú, estado
general), pero **este archivo es la referencia completa**; si algo difiere, manda este (y el código).
