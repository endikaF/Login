# Login — tablero de notas con autenticación y autorización

Proyecto de aprendizaje: web con backend Java cuyo núcleo es la **autenticación y autorización
multiusuario**. El tema (notas personales) solo aporta un recurso privado por usuario para poder
probar que nadie accede a datos ajenos.

**Estado:** Fase 4 de 6 completada (verificación de email y recuperación de contraseña).

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Diseño, modelo de datos, amenazas | Hecha |
| 1 | Scaffolding, SQLite + JDBC, registro, Argon2id | Hecha |
| 2 | Login, JWT, refresh token rotatorio, logout | Hecha |
| 3 | Fuerza bruta, rate limiting, bloqueo, auditoría | Hecha |
| 4 | Verificación de email y recuperación de contraseña | Hecha |
| 5 | Roles, panel admin, propiedad, cabeceras, CORS/CSRF, frontend | Pendiente |
| 6 | Revisión ASVS nivel 2, README final, TOTP opcional | Pendiente |

## Stack (versiones verificadas en Maven Central el 2026-11-06)

- Java 21 (se compila con `release 21`; funciona con tu JDK 23), Maven 3.9+
- Spring Boot **3.5.16** (Spring Security 6.5.x), `spring-boot-starter-web/security/validation/jdbc`
- SQLite con `sqlite-jdbc` **3.54.1.0**, acceso solo por `JdbcTemplate`/`NamedParameterJdbcTemplate` (sin JPA)
- BouncyCastle `bcprov-jdk18on` **1.86** (necesario para `Argon2PasswordEncoder`; el JDK no incluye Argon2)
- `spring-security-oauth2-jose` (versión gestionada por Spring Boot) para firmar y validar JWT con Nimbus, sin criptografía casera
- Bucket4j `bucket4j_jdk17-core` **8.22.0** (verificada en Maven Central) para el rate limiting en memoria

## Ejecución en Windows

Requisitos: JDK 21 o superior y Maven en el `PATH` (`java -version`, `mvn -v`).

1. Copia `.env.example` a `.env` (está en `.gitignore`, **nunca se sube**):
   ```powershell
   Copy-Item .env.example .env
   ```
2. Edita `.env` y rellena (Spring lo carga solo si existe):
   - `ADMIN_EMAIL` y `ADMIN_PASSWORD` (mínimo 12 caracteres). Sin ellas no se crea administrador.
   - `JWT_SECRET`, **obligatoria**: sin ella (o con menos de 32 caracteres) la app no arranca.
     Genérala con `openssl rand -base64 48` (Git Bash). Si la cambias, los access token emitidos dejan de valer.
3. Arranca:
   ```powershell
   mvn spring-boot:run
   ```
   La app escucha solo en `http://127.0.0.1:8080`.
4. Tests: `mvn verify`.

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

### Estructura

```
src/main/java/com/example/login/
  audit/     AuditService, AuditEvent
  auth/      AuthController, AuthService, JwtService, JwtAuthFilter, RefreshTokenService/Repository,
             AccountRecoveryController/Service (verificación y reset), LoginRequest, AuthenticatedUser, excepciones
  mail/      EmailService (interfaz), ConsoleEmailService (simulado), AccountMailer (textos y enlaces)
  token/     OneTimeTokenService (tokens de un solo uso)
  config/    SecurityConfig (Argon2id, cadena de filtros), AdminBootstrap
  security/  PasswordPolicy, TokenHasher (token aleatorio + SHA-256), RateLimitService, LoginAttemptService
  user/      User, Role, RegisterRequest, UserRepository (JDBC), UserService, RegistrationController, excepciones
  web/       GlobalExceptionHandler
src/main/resources/  application.yml, schema.sql
```

### Modelo de datos (`schema.sql`, creado entero desde la Fase 1)

`users`, `refresh_tokens`, `one_time_tokens`, `notes`, `audit_log`. Las fechas son texto ISO-8601 UTC.
`users.email` es único sin distinguir mayúsculas; `users.token_version` permitirá invalidar al instante
los access token (cierre de sesión forzado). `audit_log` no tiene FK para sobrevivir al borrado del usuario.

## Decisiones importantes y simplificaciones

- **SQLite con una sola conexión** (`maximum-pool-size: 1`) porque admite un único escritor. En producción:
  PostgreSQL con pool normal. `PRAGMA foreign_keys` se activa en cada conexión vía URL del driver.
- **CSRF desactivado a propósito:** la API se autentica con `Authorization: Bearer`, que el navegador no adjunta
  solo. La única cookie (refresh) es `SameSite=Strict` y solo la usan `/refresh` y `/logout`. La estrategia
  completa, con comprobación de `Origin`, se cierra en la Fase 5.
- **HS256 (clave simétrica)** por simplicidad con una sola aplicación; con varios servicios se usaría RS256/EdDSA.
- **Una consulta a BD por petición autenticada** a cambio de revocación inmediata (rol, estado y `token_version`).
- **Dos pestañas refrescando a la vez** con el mismo token cuentan como reutilización y cierran la sesión. El
  frontend (Fase 5) debe serializar los refresh.
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

## Tests (92 en total, todos en verde con `mvn verify`)

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

Los tests de rate limiting usan límites bajos y recrean el contexto tras cada test (los cubos viven en memoria);
el resto de tests cargan límites holgados desde `src/test/resources/config/application.properties`.

Verificado además a mano contra la app arrancada: login, login erróneo, refresh, logout y refresh tras logout
(Fase 2), 5 fallos seguidos que bloquean la cuenta incluso con la contraseña correcta (Fase 3), y registro → email simulado →
verificación → login → olvido de contraseña → reseteo → login con la nueva (Fase 4).
