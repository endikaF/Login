# Revisión de seguridad (OWASP ASVS 4.0, nivel 2)

Revisión de los puntos de ASVS que aplican a este proyecto, hecha al cierre de la Fase 6 y actualizada al añadir el
segundo factor TOTP.

**Alcance y límites (léelo antes de fiarte del resultado):**
- Es una **autorrevisión** del código por quien lo escribió, contrastada con tests y pruebas manuales. No es una
  auditoría independiente ni un test de intrusión.
- Se organiza por **capítulos de ASVS** (V2 Autenticación, V3 Sesión...) y parafrasea los requisitos; **no se ha
  recorrido ASVS punto por punto** ni se cita la numeración exacta de cada requisito.
- **No se ha ejecutado** ningún análisis de vulnerabilidades de dependencias (SCA), ni análisis estático (SAST),
  ni se ha probado con un escáner dinámico.
- Estados: **Cumple** (implementado y probado), **Parcial**, **Pendiente** (hueco conocido) y **N/A**.

## Modelo de amenazas

| Amenaza | Mitigación | Dónde |
|---|---|---|
| Fuerza bruta / relleno de credenciales | Rate limit por IP y cuenta, bloqueo progresivo, Argon2id | Fase 3 |
| Enumeración de usuarios | Respuestas, códigos y límites idénticos; Argon2 también si no existe; registro y recuperación uniformes | Fases 2-4 |
| Robo del refresh token | HttpOnly + Secure + SameSite=Strict, solo su hash en BD, rotación y revocación de la familia ante reutilización, vida absoluta de 30 días | Fases 2, 6 |
| Robo del access token | Vida de 10 min, solo en memoria del navegador, `token_version` permite invalidarlo al instante | Fases 2, 5 |
| XSS | CSP estricta sin `unsafe-inline`, salida con `textContent`, test que veta APIs peligrosas | Fase 5 |
| CSRF | Bearer en cabecera + cookie `SameSite=Strict` + CORS restrictivo | Fase 5 |
| Acceso a datos ajenos (IDOR) | El propietario sale del token; toda consulta filtra por `user_id`; 404 idéntico | Fase 5 |
| Escalada de privilegios | `@PreAuthorize` + regla de URL, rol leído de la BD en cada petición, autoprotección del admin | Fase 5 |
| Inyección SQL | Solo consultas parametrizadas | Todas |
| Enlace de recuperación interceptado | Un solo uso, 20 min, hasheado, en el fragmento de la URL, revoca todas las sesiones y avisa por email | Fases 4, 6 |
| DoS por contraseña o cuerpo enorme | Máximo 128 caracteres antes de hashear; cuerpo máximo de 64 KB; rate limits | Fases 1, 3, 6 |
| Fuga de información en errores | `GlobalExceptionHandler` con mensajes fijos, sin trazas | Todas |

Fuera de alcance: compromiso del servidor o del equipo, TLS (se asume un proxy), DoS volumétrico de red.

## V2 · Autenticación

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Longitud mínima 12 y máxima 128, sin reglas de composición | Cumple | `PasswordPolicy` + `PasswordPolicyTest` |
| Se permite pegar contraseñas y gestores de contraseñas | Cumple | `type=password` con `autocomplete` correcto |
| Hash con algoritmo adaptativo, sal única | Cumple | Argon2id 19 MiB / 2 iteraciones / p=1, sal de 16 B |
| Contraseñas nunca en claro ni en logs | Cumple | `toString` enmascarado; comprobado que el log de ejecución no contiene secretos |
| Sin credenciales por defecto | Cumple | El admin sale de `ADMIN_EMAIL`/`ADMIN_PASSWORD`; sin ellas no se crea ninguno |
| Mensajes de error genéricos en login | Cumple | `AuthIntegrationTest` (usuario inexistente / mala / desactivada, idénticas) |
| Anti-automatización (límites y bloqueo) | Cumple | `BruteForceIntegrationTest`, `RateLimitIntegrationTest` |
| Recuperación: un solo uso, caducidad corta, hasheado, sin pistas | Cumple | `AccountRecoveryIntegrationTest` (20 min, SHA-256) |
| Aviso al usuario tras cambiar la contraseña | Cumple | Añadido en esta revisión; test dedicado |
| Verificación del email | Cumple | El login lo exige |
| Rechazar contraseñas comunes o filtradas | **Pendiente** | No hay lista de contraseñas filtradas. El mínimo de 12 caracteres mitiga, no sustituye. En producción: consulta k-anonymity a Have I Been Pwned o una lista local |
| Cambio de contraseña estando autenticado (pidiendo la actual) | **Pendiente** | No implementado; solo existe el reseteo por email. Fuera de lo pedido |
| Segundo factor (MFA) | Cumple (opcional por usuario) | TOTP RFC 6238 con vectores oficiales; no reutilizable; fuerza bruta contada; ver «Segundo factor» abajo. No es obligatorio para nadie |
| Reencriptar el hash al subir los parámetros | Pendiente | Los hashes incluyen sus parámetros, pero no se rehashean al iniciar sesión |

## V3 · Gestión de sesión

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Tokens de sesión aleatorios con suficiente entropía | Cumple | 256 bits de `SecureRandom` |
| El logout invalida la sesión en el servidor | Cumple | Revoca la familia; test incluye tokens ya rotados |
| Cerrar todas las sesiones | Cumple | `logout-all` y cierre forzado por admin |
| Caducidad por inactividad | Cumple | Access token 10 min; refresh deslizante 7 días |
| **Caducidad absoluta** | Cumple | Añadido en esta revisión: 30 días, el token nuevo nunca la supera; 2 tests |
| Detección de robo (reutilización) | Cumple | Revoca toda la familia; no afecta a otros dispositivos |
| Cookie `HttpOnly`, `Secure`, `SameSite=Strict`, ámbito mínimo | Cumple | `Path=/api/auth` |
| Prefijo de cookie `__Host-` | Parcial | No se usa: exige `Path=/` y aquí se restringe a `/api/auth`, que es más estricto en otro eje |
| JWT: algoritmo fijo, firma, caducidad y emisor validados | Cumple | `JwtServiceTest` (incluye `alg:none` y otra clave) |
| Tokens fuera del almacenamiento del navegador | Cumple | Test que veta su uso |
| Invalidar sesiones al cambiar rol, contraseña o estado | Cumple | `token_version`; rol y estado se leen de la BD |

## V4 · Control de acceso

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Denegar por defecto | Cumple | `anyRequest().authenticated()` |
| Autorización en el servidor, no en el cliente | Cumple | Los botones del panel son comodidad; el servidor devuelve 403 |
| Control a nivel de objeto (IDOR) | Cumple | `NotesOwnershipIntegrationTest` |
| Separación de funciones de administración | Cumple | Doble barrera; `AdminIntegrationTest` |
| El administrador no puede degradarse a sí mismo | Cumple | 409 |
| Protección CSRF coherente con el uso de cookies | Cumple | Documentada y probada (ver README, Fase 5) |
| Último administrador | Parcial | Con dos admins, uno puede degradar al otro |

## V5 · Validación, sanitización y codificación

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Validación de entrada en el servidor | Cumple | Bean Validation + `PasswordPolicy`; tests de entradas inválidas |
| Consultas parametrizadas | Cumple | Todo JDBC con parámetros; los fragmentos SQL dinámicos son literales del código |
| Codificación de salida / XSS | Cumple | `textContent`; probado en navegador real con `<img onerror>` y `<script>` |
| Límite de tamaño de las peticiones | Cumple | Añadido en esta revisión: 64 KB, 413 y 411; comprobado contra el servidor real |
| Deserialización segura | Cumple | Records con campos fijos, sin tipos polimórficos; los campos extra se ignoran |

## V7 · Gestión de errores y registro

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Errores genéricos sin trazas ni detalles internos | Cumple | Incluye 404/405/415 y `AccessDenied`; test que busca fugas |
| Eventos de seguridad registrados | Cumple | `audit_log`: login, bloqueos, límites, logout, reutilización, recuperación, acciones de admin |
| Sin datos sensibles en los registros | Cumple | Tests comprueban que no hay contraseñas, tokens ni emails tecleados |
| Sin inyección en registros | Cumple | El detalle es un literal del código, nunca texto del usuario |
| Registro protegido contra modificación | Parcial | La tabla es solo de escritura desde la app, pero nada impide un `UPDATE` directo en la BD. Mejora: trigger que lo prohíba o envío a un sistema externo |
| Retención, rotación y consulta de registros | Pendiente | `audit_log` y los tokens caducados crecen sin limpieza |

## V8 · Protección de datos

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Sin datos sensibles en la URL | Cumple | Token de los enlaces en el fragmento, borrado de la barra; `Referrer-Policy: no-referrer` |
| No cachear respuestas sensibles | Cumple | `Cache-Control: no-store` |
| Cifrado en reposo | **Pendiente** | `data/app.db` no está cifrado; los emails están en claro (hacen falta para escribir al usuario). Depende del disco del servidor |
| Permisos del archivo de datos | Pendiente | No se gestionan; hay que restringir el acceso a `data/` con los permisos de Windows |

## V9 · Comunicaciones

| Control | Estado | Evidencia / comentario |
|---|---|---|
| TLS en todas las conexiones | **Pendiente** | La app habla HTTP en `127.0.0.1`. En producción debe ir detrás de un proxy con TLS |
| HSTS | Cumple | 1 año con subdominios (Spring solo lo envía sobre HTTPS); probado con petición segura |
| Cookies solo por HTTPS | Cumple | `Secure` (desactivable solo con `app.cookie.secure=false` en desarrollo) |

## V10 · Código y dependencias · V14 · Configuración

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Secretos fuera del código y del repositorio | Cumple | Variables de entorno; `.env`, `data/`, `target/` ignorados; escaneo de secretos en cada commit |
| Falla al arrancar sin configuración segura | Cumple | `JWT_SECRET` ausente o corta aborta el arranque |
| Cabeceras de seguridad | Cumple | CSP, nosniff, frame-options, referrer-policy, HSTS |
| Sin información de versión en cabeceras | Cumple | Sin `Server` ni `X-Powered-By` (comprobado) |
| Métodos HTTP innecesarios deshabilitados | Cumple | `TRACE` rechazado; `405` genérico |
| Sin endpoints de depuración | Cumple | Sin Actuator ni consolas |
| Escucha solo en localhost | Cumple | `server.address: 127.0.0.1` |
| Dependencias mínimas y actualizadas | Parcial | Versiones verificadas en Maven Central al inicio, sin dependencias no justificadas. **No se ha ejecutado un análisis de CVE** (p. ej. OWASP Dependency-Check) |
| Subida de archivos | N/A | No existe |

## Segundo factor (TOTP)

| Control | Estado | Evidencia / comentario |
|---|---|---|
| Algoritmo estándar y verificado | Cumple | RFC 6238 con los 6 vectores oficiales; contraste manual con un generador independiente (Python) |
| Secreto no recuperable desde la BD sola | Cumple | AES-256-GCM con clave del entorno; comprobado en la BD real |
| Un código no se puede repetir | Cumple | `last_used_step` con `UPDATE` atómico; incluye el código del alta |
| Resistencia a fuerza bruta del código | Cumple | Fallos cuentan para bloqueo progresivo y límites; el paso de contraseña no reinicia el contador |
| El factor intermedio no da acceso | Cumple | Token `typ=mfa` rechazado como access token y viceversa; 5 min; se revalida |
| Activar/desactivar exige reautenticación | Cumple | Contraseña (y código para desactivar) |
| Códigos de recuperación | Cumple | 80 bits, solo hash, un solo uso |
| Aviso al usuario al cambiar el 2FA | Cumple | Email al activar y desactivar |
| El reseteo por email no anula el 2FA | Cumple | Probado; la recuperación pasa por un administrador, que queda auditado |
| Código QR | Pendiente | No hay librería; clave en texto y enlace `otpauth://` |
| Regenerar códigos de recuperación | Pendiente | Hay que desactivar y reactivar |
| Cifrado con clave gestionada/rotable | Pendiente | `TOTP_ENCRYPTION_KEY` en una variable de entorno; sin rotación |
| Resistente a suplantación (phishing) | No | TOTP puede ser reenviado en tiempo real por una web falsa; WebAuthn/passkeys sí lo evitan |

## Límites conocidos de diseño (simplificaciones de aprendizaje)

- **Rate limiting en memoria** (Bucket4j): se pierde al reiniciar y no se comparte entre instancias. Producción: Redis o el proxy.
- **IP del cliente = `getRemoteAddr()`**: no se confía en `X-Forwarded-For`. Detrás de un proxy hay que configurarlo.
- **Argon2 sin límite de concurrencia:** muchas IPs distintas podrían ocupar mucha memoria a la vez (19 MiB por hash). Mejora: un semáforo.
- **HS256 simétrico** y una sola clave; producción: clave asimétrica con rotación.
- **Email simulado:** imprime enlaces con token en la consola. Producción: SMTP real y nunca token en logs.
- **Un bloqueo de cuenta puede usarse para molestar a la víctima** (bloqueos cortos, progresivos y con tope).
- **Diferencia de milisegundos** residual entre usuario existente e inexistente en login y olvido de contraseña.
- **Limpieza:** no hay tarea que borre usuarios sin verificar, tokens caducados ni registros antiguos.
- **Varias pestañas** refrescando a la vez pueden confundirse con una reutilización y cerrar la sesión.
- **SQLite** con una sola conexión; producción: PostgreSQL.

## Cambios hechos durante la revisión

1. **Límite de tamaño de las peticiones** (hueco real: un JSON de 3 MB se leía entero antes de rechazarse).
2. **Vida absoluta de la sesión** (la caducidad deslizante permitía mantener una sesión robada indefinidamente).
3. **Aviso por email al cambiar la contraseña.**

## Prioridades si esto pasara a producción

1. TLS detrás de un proxy y `X-Forwarded-*` configurado.
2. Rechazar contraseñas filtradas, hacer obligatorio el segundo factor para administradores y valorar WebAuthn.
3. Análisis de dependencias en CI y actualización periódica.
4. Rate limiting compartido (Redis) y límite de concurrencia de Argon2.
5. Tareas de limpieza y registro de auditoría fuera de la BD, de solo añadir.
6. PostgreSQL, cifrado en reposo y copias de seguridad.
