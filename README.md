# Login — tablero de notas con autenticación y autorización

Proyecto de aprendizaje: web con backend Java cuyo núcleo es la **autenticación y autorización
multiusuario**. El tema (notas personales) solo aporta un recurso privado por usuario para poder
probar que nadie accede a datos ajenos.

**Estado:** Fase 1 de 6 completada (scaffolding, SQLite + JDBC, registro y hash de contraseñas).

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Diseño, modelo de datos, amenazas | Hecha |
| 1 | Scaffolding, SQLite + JDBC, registro, Argon2id | Hecha |
| 2 | Login, JWT, refresh token rotatorio, logout | Pendiente |
| 3 | Fuerza bruta, rate limiting, bloqueo, auditoría | Pendiente |
| 4 | Verificación de email y recuperación de contraseña | Pendiente |
| 5 | Roles, panel admin, propiedad, cabeceras, CORS/CSRF, frontend | Pendiente |
| 6 | Revisión ASVS nivel 2, README final, TOTP opcional | Pendiente |

## Stack (versiones verificadas en Maven Central el 2026-11-06)

- Java 21 (se compila con `release 21`; funciona con tu JDK 23), Maven 3.9+
- Spring Boot **3.5.16** (Spring Security 6.5.x), `spring-boot-starter-web/security/validation/jdbc`
- SQLite con `sqlite-jdbc` **3.54.1.0**, acceso solo por `JdbcTemplate`/`NamedParameterJdbcTemplate` (sin JPA)
- BouncyCastle `bcprov-jdk18on` **1.86** (necesario para `Argon2PasswordEncoder`; el JDK no incluye Argon2)

## Ejecución en Windows

Requisitos: JDK 21 o superior y Maven en el `PATH` (`java -version`, `mvn -v`).

1. Copia `.env.example` a `.env` (está en `.gitignore`, **nunca se sube**):
   ```powershell
   Copy-Item .env.example .env
   ```
2. Edita `.env` y rellena `ADMIN_EMAIL` y `ADMIN_PASSWORD` (mínimo 12 caracteres). Spring carga `.env`
   solo si existe. Sin estas variables simplemente no se crea administrador.
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
  el email (minúsculas), fuerza siempre el rol `USER` y deja `email_verified = 0`.
- **Política de contraseñas:** 12–128 caracteres, sin reglas de composición. Se comprueba *antes* de
  hashear, para que una contraseña enorme no consuma CPU (DoS).
- **Argon2id** (`Argon2PasswordEncoder(16, 32, 1, 19456, 2)`): sal 16 B, hash 32 B, paralelismo 1,
  19 MiB de memoria, 2 iteraciones (mínimo recomendado por OWASP). Los parámetros van dentro del hash,
  así que pueden subirse luego sin invalidar los existentes. Nunca se guarda ni se loguea la contraseña.
- **Primer admin** creado al arrancar desde `ADMIN_EMAIL`/`ADMIN_PASSWORD`. Es idempotente y, si la
  contraseña incumple la política, el arranque falla.
- **Errores centralizados** (`GlobalExceptionHandler`): mensajes fijos, sin trazas; el detalle va al log.
- **Denegar por defecto:** solo el registro es público; el resto exige autenticación.

### Estructura

```
src/main/java/com/example/login/
  config/    SecurityConfig (Argon2id, cadena de filtros), AdminBootstrap
  security/  PasswordPolicy
  user/      Role, RegisterRequest, UserRepository (JDBC), UserService, RegistrationController, excepciones
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
- **CSRF desactivado a propósito:** no hay sesión por cookie; la API usará `Authorization: Bearer`, que el
  navegador no adjunta solo. La estrategia completa (cookie de refresh `SameSite=Strict`) se documenta en la Fase 5.
- **Registro con email duplicado → 409 genérico.** Permite saber si un email existe. Es una simplificación
  temporal: en la Fase 4, con el email de verificación, se responderá igual en ambos casos.
- Sin `UserDetailsService` automático: se excluye para que Spring no imprima una contraseña aleatoria en el log.

## Secretos

Nada de claves ni credenciales en el código. Todo por variables de entorno; `.env`, `data/` y `target/`
están en `.gitignore`. `.env.example` solo contiene marcadores, nunca valores reales.
La contraseña del admin que uses ahora es de prueba: cámbiala por la definitiva cuando termines de revisar.

## Tests (Fase 1: 20, todos en verde con `mvn verify`)

Unitarios de la política de contraseñas e integración con SQLite en archivo temporal: registro correcto
con hash `$argon2id$`, email duplicado (también con otras mayúsculas), restricción UNIQUE de la BD,
contraseña corta/larga, email inválido o con intento de inyección SQL, cuerpo mal formado, rol enviado
por el cliente ignorado, claves foráneas activas, ruta protegida sin autenticar y creación idempotente del admin.
