# Login — tablero de notas con autenticación y autorización

Proyecto de aprendizaje: web con backend Java cuyo núcleo es la **autenticación y autorización
multiusuario**. El tema (notas personales) solo aporta un recurso privado por usuario para poder
probar que nadie accede a datos ajenos.

**Estado:** Fase 6 de 6 completada salvo el segundo factor TOTP, que es opcional y está pendiente de decisión.
La revisión de seguridad (OWASP ASVS 4.0 nivel 2) está en [SECURITY-REVIEW.md](SECURITY-REVIEW.md), con sus límites y los huecos conocidos.

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Diseño, modelo de datos, amenazas | Hecha |
| 1 | Scaffolding, SQLite + JDBC, registro, Argon2id | Hecha |
| 2 | Login, JWT, refresh token rotatorio, logout | Hecha |
| 3 | Fuerza bruta, rate limiting, bloqueo, auditoría | Hecha |
| 4 | Verificación de email y recuperación de contraseña | Hecha |
| 5 | Roles, panel admin, propiedad, cabeceras, CORS/CSRF, frontend | Hecha |
| 6 | Revisión ASVS nivel 2, README final, TOTP opcional | Revisión y README hechos; TOTP pendiente |

## Stack (versiones verificadas en Maven Central el 2026-11-06)

- Java 21 (se compila con `release 21`; funciona con tu JDK 23), Maven 3.9+
- Spring Boot **3.5.16** (Spring Security 6.5.x), `spring-boot-starter-web/security/validation/jdbc`
- SQLite con `sqlite-jdbc` **3.54.1.0**, acceso solo por `JdbcTemplate`/`NamedParameterJdbcTemplate` (sin JPA)
- BouncyCastle `bcprov-jdk18on` **1.86** (necesario para `Argon2PasswordEncoder`; el JDK no incluye Argon2)
- `spring-security-oauth2-jose` (versión gestionada por Spring Boot) para firmar y validar JWT con Nimbus, sin criptografía casera
- Bucket4j `bucket4j_jdk17-core` **8.22.0** (verificada en Maven Central) para el rate limiting en memoria

## Ejecución en Windows

### Requisitos
- **JDK 21 o superior** y **Maven 3.9+** en el `PATH`. Compruébalo en PowerShell:
  ```powershell
  java -version
  mvn -v
  ```
  El proyecto se compila con `release 21`, así que también funciona con un JDK más nuevo (se ha probado con el 23).
- Un navegador moderno (probado en Chrome). No hace falta instalar ninguna base de datos ni Docker.

### Puesta en marcha
1. **Crea tu `.env`** (está en `.gitignore`, **nunca se sube a git**):
   ```powershell
   Copy-Item .env.example .env
   ```
2. **Rellénalo** con el Bloc de notas u otro editor (Spring lo carga solo si existe, desde la carpeta del proyecto):
   - `ADMIN_EMAIL` y `ADMIN_PASSWORD`: primer administrador (mínimo 12 caracteres). Se crea al arrancar si no
     existe. Sin estas variables no se crea ninguno. Una vez creado puedes borrar la contraseña del `.env`.
   - `JWT_SECRET`: **obligatoria**. Sin ella, o con menos de 32 caracteres, la app se niega a arrancar.
     Genera una aleatoria en PowerShell (usa el generador criptográfico del sistema) y pégala:
     ```powershell
     $b = New-Object byte[] 48; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
     ```
     Si la cambias, todos los access token emitidos dejan de valer.
3. **Arranca** desde la carpeta del proyecto:
   ```powershell
   mvn spring-boot:run
   ```
   Espera a ver `Started LoginApplication`. La app escucha solo en `http://127.0.0.1:8080`. Para pararla, `Ctrl+C`.
4. **Abre** `http://localhost:8080` en el navegador.
5. **Cuenta de prueba normal:** regístrate en la web. Como el email es simulado, el enlace de verificación aparece
   **en la consola donde corre la app**, bajo `EMAIL SIMULADO`. Cópialo y ábrelo en el navegador; pulsa
   «Verificar mi email». El de recuperar contraseña funciona igual.
6. **Tests:** `mvn verify` (usa archivos SQLite temporales en `target/`, no toca tu `data/app.db`).

### Alternativas
- **Sin `.env`**, con variables de entorno de la sesión de PowerShell:
  ```powershell
  $env:JWT_SECRET = "<tu clave>"; $env:ADMIN_EMAIL = "admin@example.com"; $env:ADMIN_PASSWORD = "<tu contraseña>"
  mvn spring-boot:run
  ```
- **Como JAR:** `mvn -DskipTests package` y después, desde la carpeta del proyecto (para que encuentre `.env` y `data/`):
  ```powershell
  java -jar target\login-0.1.0-SNAPSHOT.jar
  ```

### Problemas frecuentes
| Síntoma | Causa y solución |
|---|---|
| `JWT_SECRET no definida o demasiado corta` | Falta en `.env` o tiene menos de 32 caracteres (ver paso 2) |
| `Port 8080 was already in use` | Hay otra instancia en marcha. Ciérrala o cambia `server.port` en `application.yml` |
| Al registrarme no recibo el correo | Es simulado: está en la consola de la app, no en tu bandeja |
| «Debes verificar tu email» al entrar | Abre primero el enlace de verificación de la consola |
| Tras fallar la contraseña 5 veces no me deja entrar | Bloqueo temporal (1, 2, 4... hasta 30 min); espera o resetea la contraseña. Para quien se bloquea en desarrollo: reinicia con la BD vacía |
| `database is locked` al abrir `data/app.db` | SQLite admite un solo escritor: cierra SQLiteMan o ábrelo en solo lectura mientras la app corre |
| El navegador no guarda la sesión | La cookie lleva `Secure`: Chrome, Edge y Firefox la aceptan en `http://localhost`, **Safari no**. Solo para desarrollo se puede arrancar con `$env:APP_COOKIE_SECURE="false"` |
| Los acentos salen mal en la consola | Es solo la codificación de la consola de Windows; no afecta a los datos |
| Quiero empezar de cero | Para la app y borra la carpeta `data\`; se regenera sola vacía en el siguiente arranque |

### HTTPS
La app habla HTTP en local. Para un uso real debe ir detrás de un proxy inverso con TLS (y configurar las cabeceras
`X-Forwarded-*`). Eso no está incluido; es el hueco de «comunicaciones» en [SECURITY-REVIEW.md](SECURITY-REVIEW.md).

### Base de datos

No hay que crear nada a mano. Al arrancar, la app crea la carpeta `data/` y el archivo `data/app.db`,
y ejecuta `schema.sql` (`CREATE TABLE IF NOT EXISTS`), así que es seguro reiniciar. Puedes abrir
`data/app.db` con SQLiteMan (mejor con la app parada, para no chocar con el bloqueo de escritura).
`data/` está en `.gitignore`. Si borras el archivo, se regenera vacío en el siguiente arranque.

### Probar el registro con curl en Windows

`curl` desde Git Bash pasa los argumentos con la página de códigos de Windows: un carácter como `ñ`
escrito directamente en `-d` llega mal codificado y el servidor responde `400 Solicitud mal formada`
(correcto: no es UTF-8 válido). Guarda el cuerpo en un archivo UTF-8 y usa `--data-binary @cuerpo.json`,
o usa el navegador (`fetch` siempre envía UTF-8).

## Qué hay implementado (Fase 1)

- **Registro público** `POST /api/auth/register` con `{"email", "password"}`. Valida en servidor, normaliza
  el email (minúsculas), fuerza siempre el rol `USER` y deja `email_verified = 0`. Desde la Fase 4 responde
  `202` con el mismo mensaje exista o no el email (ver Fase 4).
- **Política de contraseñas:** 12–128 caracteres, sin reglas de composición. Se comprueba *antes* de
  hashear, para que una contraseña enorme no consuma CPU (DoS).
- **Argon2id** (`Argon2PasswordEncoder(16, 32, 1, 19456, 2)`): sal 16 B, hash 32 B, paralelismo 1,
  19 MiB de memoria, 2 iteraciones (mínimo recomendado por OWASP). Los parámetros van dentro del hash,
  así que pueden subirse luego sin invalidar los existentes. Nunca se guarda ni se loguea la contraseña.
- **Primer admin** creado al arrancar desde `ADMIN_EMAIL`/`ADMIN_PASSWORD`. Es idempotente y, si la
  contraseña incumple la política, el arranque falla.
- **Errores centralizados** (`GlobalExceptionHandler`): mensajes fijos, sin trazas; el detalle va al log.
- **Denegar por defecto:** solo el registro es público; el resto exige autenticación.

## Qué hay implementado (Fase 2)

| Endpoint | Acceso | Qué hace |
|---|---|---|
| `POST /api/auth/login` | público | Devuelve `{accessToken, tokenType, expiresIn}` y fija la cookie del refresh token |
| `POST /api/auth/refresh` | cookie | Rota el refresh token y emite un access token nuevo |
| `POST /api/auth/logout` | cookie | Revoca en servidor la sesión (toda su familia) y borra la cookie |
| `POST /api/auth/logout-all` | Bearer | Cierra todas las sesiones y anula los access token vigentes |
| `GET /api/auth/me` | Bearer | Devuelve email y rol del token (sirve al frontend) |

- **Login con respuesta genérica:** usuario inexistente, contraseña errónea y cuenta desactivada devuelven
  exactamente el mismo `401 Credenciales inválidas.` Si el usuario no existe se verifica igualmente la contraseña
  contra un hash Argon2id ficticio, para que el tiempo de respuesta sea parecido. Las contraseñas de más de
  128 caracteres se descartan sin hashear.
- **Access token JWT:** HS256, 10 minutos, firmado con `JWT_SECRET`. Lleva `sub` (id), `iss`, `exp`, `jti` y
  `ver` (versión). El decoder fija HS256, así que rechaza `alg:none`. **El rol no va en el token:** se lee de la BD
  en cada petición, por lo que desactivar una cuenta o cambiar un rol surte efecto al instante.
- **Refresh token:** 256 bits aleatorios (`SecureRandom`), opaco, 7 días. En BD solo se guarda su SHA-256.
  Viaja en cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`, nunca en el JSON.
- **Rotación y detección de reutilización:** cada login abre una *familia*; cada refresh consume el token y
  emite otro de la misma familia. Si se presenta un token ya rotado (robo o cliente roto), se revoca toda la
  familia. El marcado "usado" es un `UPDATE` atómico, así dos refresh simultáneos con el mismo token no pasan los dos.
  Una reutilización en un dispositivo no afecta a las sesiones de otros dispositivos.
- **Cierre global:** `logout-all` revoca todos los refresh tokens y sube `users.token_version`; el filtro JWT
  compara esa versión en cada petición, así los access token aún no caducados también dejan de valer.
- **401 sin pistas:** una petición sin identidad válida recibe 401 con cuerpo vacío; no se dice si el token
  estaba caducado, manipulado o revocado.

## Qué hay implementado (Fase 3)

- **Rate limiting por IP y por cuenta** (Bucket4j, algoritmo token bucket, en memoria). Se aplica antes de tocar
  la BD o Argon2, que es lo caro. Superado el límite: `429` con cabecera `Retry-After` y mensaje genérico.

  | Límite | Valor por defecto | Propiedad |
  |---|---|---|
  | Login por IP | 20 / minuto | `app.ratelimit.login-ip-per-minute` |
  | Login por cuenta | 10 / 15 min | `app.ratelimit.login-account-per-15min` |
  | Registro por IP | 10 / hora | `app.ratelimit.register-ip-per-hour` |

  El límite por cuenta cuenta también los emails que no existen, así que no revela qué cuentas hay.
  El mapa de cubos tiene un tope (10 000 claves, LRU) para que nadie agote la memoria inventando emails.
- **Bloqueo temporal progresivo:** 5 fallos de contraseña seguidos bloquean la cuenta 1 minuto; el siguiente
  bloqueo dura 2, luego 4... hasta 30 minutos (`app.lockout.*`). Un login correcto reinicia contador y nivel.
  El estado está en la BD (`failed_attempts`, `lock_level`, `locked_until`), así que sobrevive a reinicios.
  Con la cuenta bloqueada se rechaza **incluso la contraseña correcta**, con la misma respuesta genérica
  `401 Credenciales inválidas.` que un usuario inexistente o una contraseña errónea.
- **Auditoría** en la tabla `audit_log` (fecha, evento, id de usuario, IP, detalle fijo). Eventos: `LOGIN_OK`,
  `LOGIN_FAILED`, `LOGIN_BLOCKED`, `ACCOUNT_LOCKED`, `RATE_LIMITED`, `LOGOUT`, `LOGOUT_ALL`, `TOKEN_REUSE`.
  Nunca se guardan contraseñas, tokens, hashes ni el email tecleado en un intento fallido; el detalle es un
  literal del código (no texto del usuario), lo que impide inyectar líneas falsas en el rastro.
  Para consultarla, abre `data/app.db` con SQLiteMan: `SELECT * FROM audit_log ORDER BY id DESC;`

## Qué hay implementado (Fase 4)

Un único mecanismo de **token de un solo uso** sirve para verificar el email y para recuperar la contraseña:
256 bits aleatorios, en BD solo su SHA-256 (tabla `one_time_tokens`), caducidad corta, ligado a un propósito
(un token de verificación no vale para resetear y viceversa) y consumo atómico (`UPDATE ... WHERE used_at IS NULL`).
Pedir un enlace nuevo anula los anteriores sin usar.

| Endpoint | Qué hace |
|---|---|
| `POST /api/auth/register` | Siempre `202` y el mismo mensaje. Si el email es nuevo: crea la cuenta (sin verificar) y envía el enlace de verificación (24 h). Si ya existía: no crea nada y envía un aviso "ya tienes cuenta" sin token |
| `POST /api/auth/verify-email` `{token}` | Marca el email como verificado |
| `POST /api/auth/resend-verification` `{email}` | Reenvía el enlace; respuesta idéntica exista o no la cuenta |
| `POST /api/auth/forgot-password` `{email}` | Siempre `202` y el mismo mensaje; solo envía correo si la cuenta existe y está activa (enlace de **20 min**) |
| `POST /api/auth/reset-password` `{token, newPassword}` | Cambia la contraseña con el enlace |

- **Login exige email verificado:** con la contraseña correcta pero sin verificar devuelve `403`.
- **Al resetear la contraseña**, en una sola transacción: se consume el token, se guarda el nuevo hash Argon2id,
  se **revocan todos los refresh tokens**, se sube `token_version` (los access token vigentes dejan de valer),
  se limpian fallos y bloqueos y se marca el email como verificado (recibir el enlace lo demuestra).
  La política de contraseñas se valida **antes** de consumir el token: una contraseña floja no quema el enlace.
- **Mismo resultado exista o no la cuenta:** registro, olvido de contraseña y reenvío devuelven respuestas idénticas.
  En el registro se calcula el hash Argon2 también cuando el email ya existía, para igualar tiempos.
- **Enlaces con el token en el fragmento** (`.../reset-password.html#token=...`): el navegador no envía el
  fragmento al servidor, así que no queda en logs de acceso ni viaja en la cabecera `Referer`. Las páginas que
  lo leen y lo mandan por POST llegan en la Fase 5; hasta entonces se prueba con `curl` copiando el token de la consola.
- **Email simulado:** `EmailService` es una interfaz; `ConsoleEmailService` imprime el mensaje (con el enlace) en la
  consola del servidor. Para uno real bastaría otra implementación (SMTP, SES...) sin tocar el resto.
  *En producción nunca se escribirían enlaces con token en los logs.* (En la consola de Windows los acentos
  pueden verse mal; es solo la codificación de la consola.)
- **Límites nuevos** (además de los de la Fase 3):

  | Límite | Valor por defecto | Propiedad |
  |---|---|---|
  | Peticiones de correo por IP | 10 / hora | `app.ratelimit.mail-ip-per-hour` |
  | Correos por destinatario (exista o no) | 3 / hora | `app.ratelimit.mail-account-per-hour` |
  | Canje de enlaces por IP | 20 / minuto | `app.ratelimit.token-ip-per-minute` |

- **Auditoría nueva:** `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_DONE`, `PASSWORD_RESET_FAILED`, `EMAIL_VERIFIED`
  (sin tokens, contraseñas ni emails).
- **Cambio de contrato respecto a la Fase 1:** el registro pasó de `201`/`409` a `202` uniforme.

## Qué hay implementado (Fase 5)

### Autorización
- **Roles `USER` y `ADMIN`** con `@EnableMethodSecurity` y `@PreAuthorize` en cada controlador protegido.
  El panel tiene **doble barrera**: la regla de URL `/api/admin/**` → `ADMIN` en `SecurityConfig` y el
  `@PreAuthorize("hasRole('ADMIN')")` del controlador; si una se rompe en un cambio futuro, la otra sigue protegiendo.
  Un `USER` recibe `403` y un anónimo `401`.
- **Propiedad del recurso (notas):** `GET/POST /api/notes` y `DELETE /api/notes/{id}`. El propietario sale **siempre
  del token verificado**, nunca del cuerpo (un `userId` enviado por el cliente se ignora) y cada consulta lleva
  `AND user_id = :userId`. Borrar una nota ajena y borrar una inexistente dan el **mismo 404**: no se revela que ese
  id está en uso. Ni siquiera un administrador ve las notas de otros (previene IDOR / BOLA).
- **Panel de administración** (`/api/admin/users`, solo `ADMIN`):

  | Acción | Endpoint | Efecto |
  |---|---|---|
  | Listar usuarios | `GET /api/admin/users` | id, email, rol, activo, verificado, bloqueado, alta (nunca el hash) |
  | Activar / desactivar | `POST /{id}/enabled` `{enabled}` | Al desactivar cierra sus sesiones (refresh tokens + access token) |
  | Cambiar rol | `POST /{id}/role` `{role}` | Efecto inmediato: el rol se lee de la BD en cada petición |
  | Forzar cierre de sesiones | `POST /{id}/logout` | Revoca todos sus refresh tokens y sube `token_version` |

  **Un admin no puede desactivarse ni quitarse el rol a sí mismo** (`409`): se quedaría fuera o dejaría el sistema sin
  administradores. Cada acción se audita (`ADMIN_USER_ENABLED`, `ADMIN_USER_DISABLED`, `ADMIN_ROLE_CHANGED`,
  `ADMIN_FORCE_LOGOUT`) con la cuenta afectada y `detail = admin=<id>`.

### Cabeceras, CORS y CSRF
- **Cabeceras:** CSP estricta (`default-src 'none'; script-src 'self'; style-src 'self'; ...; frame-ancestors 'none'`,
  sin `unsafe-inline`), `Strict-Transport-Security` (1 año, subdominios; solo se envía sobre HTTPS),
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` y `Referrer-Policy: no-referrer`.
- **CORS restrictivo:** el frontend se sirve desde el mismo origen que la API, así que solo se acepta el propio origen
  (`app.base-url`) más los que se listen en `app.cors.allowed-origins` (vacío por defecto). Cualquier otro origen
  recibe `403`, incluso en un POST «simple», y nunca se permiten credenciales.
- **Estrategia CSRF** (el filtro CSRF de Spring está desactivado a propósito, y lo sustituyen tres capas coherentes
  con el uso de cookies): (1) la API se autentica con `Authorization: Bearer`, que el navegador nunca adjunta solo;
  (2) la única cookie, la del refresh token, es `SameSite=Strict` y `Path=/api/auth`, y solo la usan `/refresh` y
  `/logout`; (3) CORS rechaza los orígenes no autorizados. Un token CSRF clásico solo haría falta con sesión por cookie.
- **Errores:** los errores de Spring MVC (404, 405, id no numérico...) devuelven un mensaje fijo con su estado real
  en lugar de un 500, y `AccessDeniedException` de `@PreAuthorize` se traduce a `403`. Nunca salen trazas.

### Frontend
HTML, CSS y JavaScript puros en `src/main/resources/static/`, **sin nada en línea** para cumplir la CSP
(scripts como módulos externos, estilos en `css/style.css`).

| Página | Para qué |
|---|---|
| `index.html` | Iniciar sesión y crear cuenta |
| `notes.html` | Tus notas, cerrar sesión y cerrar sesión en todos los dispositivos |
| `admin.html` | Panel de administración (solo `ADMIN`) |
| `forgot-password.html` / `reset-password.html` / `verify-email.html` | Destinos de los enlaces del email simulado |

- El **access token vive solo en una variable de memoria** (nunca en el almacenamiento del navegador): un XSS no puede
  llevárselo para usarlo después. Al cargar cada página se recupera con la cookie HttpOnly (`/refresh`, que rota el token).
- Los datos se pintan con `textContent`, no como HTML: una nota con `<script>` o `<img onerror>` se ve como texto.
  Un test impide que reaparezcan `innerHTML`, `eval`, `document.write` o almacenamiento del navegador.
- Un solo `/refresh` en vuelo por página, para que dos peticiones simultáneas no parezcan una reutilización.
- **Accesibilidad:** `lang="es"`, enlace «Saltar al contenido», etiquetas `<label>` en todos los campos, regiones con
  encabezados, mensajes anunciados con `aria-live` (`role="alert"` en los errores), foco visible, contraste AA en
  claro y oscuro, diseño adaptable a móvil y tabla con `caption` y `scope`.
- Las páginas de verificación y reseteo piden un clic o un envío de formulario en lugar de actuar al abrirse, para que
  un antivirus o un previsualizador de enlaces no consuma el token de un solo uso. El token viaja en el fragmento de la
  URL y se borra de la barra de direcciones al leerlo.

### Estructura

```
src/main/java/com/example/login/
  admin/     AdminController, AdminService, AdminRepository (vista de lectura), AdminExceptions
  audit/     AuditService, AuditEvent
  auth/      AuthController, AuthService, JwtService, JwtAuthFilter, RefreshTokenService/Repository,
             AccountRecoveryController/Service (verificación y reset), LoginRequest, AuthenticatedUser, excepciones
  notes/     NoteController, NoteRepository (propiedad impuesta en la consulta)
  mail/      EmailService (interfaz), ConsoleEmailService (simulado), AccountMailer (textos y enlaces)
  token/     OneTimeTokenService (tokens de un solo uso)
  config/    SecurityConfig (Argon2id, cadena de filtros), AdminBootstrap
  security/  PasswordPolicy, TokenHasher (token aleatorio + SHA-256), RateLimitService, LoginAttemptService
  user/      User, Role, RegisterRequest, UserRepository (JDBC), UserService, RegistrationController, excepciones
  web/       GlobalExceptionHandler
src/main/resources/  application.yml, schema.sql, static/ (frontend: HTML, css/, js/)
```

### Modelo de datos (`schema.sql`, creado entero desde la Fase 1)

`users`, `refresh_tokens`, `one_time_tokens`, `notes`, `audit_log`. Las fechas son texto ISO-8601 UTC.
`users.email` es único sin distinguir mayúsculas; `users.token_version` permitirá invalidar al instante
los access token (cierre de sesión forzado). `audit_log` no tiene FK para sobrevivir al borrado del usuario.

## Decisiones importantes y simplificaciones

- **SQLite con una sola conexión** (`maximum-pool-size: 1`) porque admite un único escritor. En producción:
  PostgreSQL con pool normal. `PRAGMA foreign_keys` se activa en cada conexión vía URL del driver.
- **CSRF desactivado a propósito**, sustituido por Bearer en cabecera + cookie `SameSite=Strict` + CORS restrictivo
  (explicado en la Fase 5).
- **HS256 (clave simétrica)** por simplicidad con una sola aplicación; con varios servicios se usaría RS256/EdDSA.
- **Una consulta a BD por petición autenticada** a cambio de revocación inmediata (rol, estado y `token_version`).
- **Dos pestañas refrescando a la vez** con el mismo token cuentan como reutilización y cierran la sesión. El
  frontend serializa los refresh dentro de cada página, pero no puede coordinar varias pestañas: es una limitación
  conocida de rotar el refresh token en cada carga de página (una SPA con el token en memoria la reduciría).
- **Frontend multipágina:** el access token en memoria se pierde al cambiar de página, por eso cada carga hace un
  `/refresh`. Se prefiere esto a guardarlo en el almacenamiento del navegador, que un XSS podría leer.
- **Sin protección de «último administrador»** más allá de la regla de autoprotección: con dos admins, uno puede
  degradar al otro. Se considera aceptable aquí.
- **Aviso "verifica tu email" en el login (403):** solo lo recibe quien ya acertó la contraseña, así que no permite
  enumerar cuentas; a cambio, ese usuario sabe que la cuenta existe (es la suya).
- **Rate limiting en memoria:** se pierde al reiniciar y no se comparte entre instancias. En producción:
  Bucket4j sobre Redis, o límites en el proxy/gateway.
- **IP del cliente = `getRemoteAddr()`**, sin confiar en `X-Forwarded-For` (cualquiera puede falsearla). Detrás de un
  proxy habría que configurar las cabeceras de confianza explícitamente.
- **Bloqueo de cuenta = posible denegación de servicio dirigida:** un atacante puede bloquear a propósito a una
  víctima. Se mitiga con bloqueos cortos, progresivos y con tope; la alternativa (CAPTCHA, bloqueo por IP+cuenta) queda fuera.
- **Pequeña diferencia de tiempo residual:** un usuario existente con contraseña errónea hace unas escrituras en BD
  más que uno inexistente (milisegundos frente a las decenas de ms de Argon2).
- **Si falla la escritura de auditoría, falla la petición** (no se traga el error): preferimos no operar sin rastro.
- **Refresh token con caducidad deslizante de 7 días** y sin límite absoluto de la familia; en producción se añadiría un tope.
- Sin `UserDetailsService` automático: se excluye para que Spring no imprima una contraseña aleatoria en el log.

## Secretos

Nada de claves ni credenciales en el código. Todo por variables de entorno; `.env`, `data/` y `target/`
están en `.gitignore`. `.env.example` solo contiene marcadores, nunca valores reales.
La contraseña del admin que uses ahora es de prueba: cámbiala por la definitiva cuando termines de revisar.

## Fase 6 · Revisión de seguridad

Resumen (detalle, evidencias y huecos en [SECURITY-REVIEW.md](SECURITY-REVIEW.md)): la revisión contra ASVS 4.0 nivel 2
es una **autorrevisión**, no una auditoría independiente, y no incluye análisis de dependencias ni escáneres. Encontró
y corrigió tres cosas:

- **Límite de tamaño de las peticiones** (`RequestSizeFilter`, 64 KB por defecto, `app.max-body-bytes`): un JSON de
  3 MB se leía entero antes de rechazarse. Ahora responde `413`, y `411` si el cuerpo no declara tamaño (`chunked`).
- **Vida absoluta de la sesión** (`app.refresh.max-session-days`, 30): la caducidad deslizante permitía mantener una
  sesión robada para siempre renovándola. El token nuevo nunca vive más que el final de la sesión.
- **Aviso por email al cambiar la contraseña**, sin ningún secreto en el mensaje.

Huecos que quedan abiertos y asumidos: contraseñas filtradas sin comprobar, sin segundo factor, sin TLS propio, BD sin
cifrar, sin limpieza de datos caducados y sin análisis de CVE de dependencias.

## Tests (129 en total, todos en verde con `mvn verify`)

Unitarios e integración con SQLite en archivo temporal (`target/test-db/`).

- **Fase 1 (20):** política de contraseñas; registro con hash `$argon2id$`; email duplicado (también con otras
  mayúsculas); restricción UNIQUE de la BD; contraseña corta/larga; email inválido o con intento de inyección SQL;
  cuerpo mal formado; rol enviado por el cliente ignorado; claves foráneas activas; creación idempotente del admin.
- **Fase 2 (28):** JWT (ida y vuelta, caducado, firma alterada, otra clave, `alg:none`, basura, clave ausente o
  corta); login (cookie con `HttpOnly/Secure/SameSite=Strict`, refresh guardado solo como hash, respuesta idéntica
  para usuario inexistente/contraseña errónea/cuenta desactivada, contraseña de 5000 caracteres); token de usuario
  desactivado; rol leído de la BD; rotación; reutilización que revoca la familia sin afectar a otro dispositivo;
  refresh caducado/desconocido/ausente; logout (incluye tokens ya rotados); `logout-all` que invalida también los JWT.

- **Fase 3 (22):** cubos de Bucket4j (capacidad, `Retry-After`, claves independientes, memoria acotada); duración
  del bloqueo (se duplica, con tope y sin desbordar); bloqueo tras 5 fallos que rechaza la contraseña correcta;
  respuesta idéntica para cuenta bloqueada, contraseña errónea y usuario inexistente; bloqueos progresivos de 1 y
  2 minutos; reinicio tras login correcto; aislamiento entre cuentas; límite por cuenta (existente o no), por IP y
  de registro con `429`; y auditoría sin contraseñas, tokens ni emails tecleados.

- **Fase 4 (22):** verificación (el login queda bloqueado hasta verificar; token solo guardado como hash; un solo uso;
  caducado, inventado y gigante rechazados; reenvío que anula el enlace anterior; respuestas idénticas para cuentas
  inexistentes o ya verificadas); registro con email existente (misma respuesta, sin segunda cuenta ni token);
  recuperación (enlace con caducidad de 15-30 min; respuesta idéntica para cuenta existente, inexistente y
  desactivada; nueva contraseña funciona y la vieja no; un solo uso; revoca refresh y access token; limpia el
  bloqueo; contraseña débil sin quemar el enlace; nueva solicitud invalida la anterior; token ligado a su
  propósito; cuenta desactivada tras pedir el enlace); auditoría sin secretos; y límites de correo por destinatario,
  por IP y de canje de enlaces.

- **Fase 5 (31):** autorización por rol (un `USER` recibe `403` en cada endpoint admin, un anónimo `401`, el rol se lee
  de la BD y el ascenso/descenso es inmediato); panel (listado sin secretos, desactivar cierra sesiones y bloquea
  login/refresh, cierre forzado, autoprotección del admin, ids inexistentes o entradas inválidas, auditoría exacta);
  notas (solo ve las propias, 404 idéntico para ajena e inexistente, ni el admin accede a las de otros, el propietario
  sale del token, validaciones, id no numérico, SQL en el título guardado como texto); cabeceras (CSP estricta,
  HSTS sobre HTTPS, nosniff, frame-options, referrer-policy); frontend sin scripts/estilos/manejadores en línea y sin
  APIs peligrosas; errores 404/405/415 genéricos sin filtrar internos; y CORS/CSRF (preflight y POST de origen ajeno
  rechazados sin cookie, origen propio permitido sin credenciales, refresh/logout cross-site rechazados sin consumir el token).

- **Fase 6 (6):** sesión que supera los 30 días no se puede renovar aunque su token siga vigente; el token renovado no
  supera el final de la sesión; aviso de cambio de contraseña sin secretos; cuerpo de más de 64 KB → 413; `chunked` → 411;
  peticiones normales y GET sin afectar.

Los tests de rate limiting usan límites bajos y recrean el contexto tras cada test (los cubos viven en memoria);
el resto de tests cargan límites holgados desde `src/test/resources/config/application.properties`.

Verificado además a mano contra la app arrancada: login, login erróneo, refresh, logout y refresh tras logout
(Fase 2), 5 fallos seguidos que bloquean la cuenta incluso con la contraseña correcta (Fase 3), y registro → email simulado →
verificación → login → olvido de contraseña → reseteo → login con la nueva (Fase 4), y en un navegador real
(Chrome): login desde la interfaz, sesión recuperada al navegar, nota con `<img onerror>` y `<script>` mostrada como
texto, panel admin con los botones de autoprotección deshabilitados y consola sin errores ni violaciones de CSP (Fase 5).
Los límites de tamaño (413 y 411) se comprobaron también contra el servidor real (Fase 6).
No se ha probado en otros navegadores ni con lector de pantalla.
