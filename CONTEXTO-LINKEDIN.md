# Contexto completo del proyecto «Login» — para redactar un post de LinkedIn

> **Cómo usar este archivo:** pásaselo a Claude (claude.ai) junto con las capturas de la versión inicial y del rediseño, y pídele
> un post de LinkedIn. Todo lo de aquí se ha comprobado en git, en el código o en las notas de sesión; lo que **no** se ha
> podido comprobar está marcado como tal en la sección 9. Las instrucciones para quien redacte están en la sección 10.
> No contiene secretos ni datos personales sensibles.

---

## 1. En una frase

Una aplicación web de **autenticación y autorización multiusuario** (Java 21 + Spring Boot + SQLite), construida **por fases con
Claude Code** como proyecto de aprendizaje de seguridad, con una revisión de seguridad contra OWASP ASVS, verificación en dos pasos
(TOTP), 161 tests automáticos y, al final, un **rediseño visual completo** hecho con skills de diseño.

## 2. Qué es y por qué existe

- **Qué es:** un «tablero de notas» donde cada persona tiene su cuenta y sus notas privadas. **El tema es una excusa:** lo que
  importa es el sistema de acceso. Las notas solo existen para tener un recurso privado por usuario con el que demostrar que nadie
  puede ver ni borrar datos ajenos.
- **Por qué:** es un **proyecto de aprendizaje**. Quien lo hace está en formación como desarrollo de software y pidió que cada
  componente se explicara (qué hace, por qué existe y qué ataque evita), que las decisiones con alternativa se marcaran como
  `[TRADE-OFF]` y que se distinguiera lo que es una simplificación didáctica de lo que se haría en producción.
- **Dónde está:** repositorio propio en GitHub (la persona decide si lo enlaza en el post). Se ejecuta en local en
  `http://localhost:8080`; no está desplegado en ningún servidor.

## 3. Cómo se hizo: trabajar con Claude Code, por fases y con reglas

**Método.** Se partió de un encargo detallado (tema, stack, requisitos de seguridad, modo aprendizaje). El prompt original se afinó
antes de empezar (con la skill `prompt-master`) y se hicieron ajustes: por ejemplo, **SQLite en vez de PostgreSQL y sin Docker**,
porque para un proyecto pequeño bastaba y reducía complejidad. A partir de ahí, el trabajo fue **fase a fase**: Claude proponía y
ejecutaba una fase, corría los tests, resumía y **esperaba la confirmación** de la persona antes de seguir.

**Reglas de trabajo acordadas** (las respetó durante todo el proyecto):
- `mvn verify` debe pasar antes de cerrar una fase y se reporta la **salida real**; no se afirma que algo funciona sin ejecutarlo.
- Las versiones de las dependencias se **verifican en Maven Central** antes de fijarlas (no se inventan).
- **Commits en español**, y antes de cada uno se comprueba que no entra ningún secreto, `.env` ni base de datos.
- **Claude nunca hace `git push`**: lo hace la persona tras cada commit.
- **Preguntar antes** de borrar archivos, añadir una dependencia, cambiar el esquema de la BD, instalar software o abrir puertos.
- Solo JDBC con consultas parametrizadas (sin JPA ni SQL concatenado) y **nada de criptografía casera**: Spring Security y el JDK.
- Entregar solo lo pedido: nada de funciones ni dependencias sin un requisito que las justifique.

**Herramientas y skills de Claude Code que intervinieron** (solo las comprobadas):

| Cuándo | Qué | Para qué |
|---|---|---|
| Inicio (6 nov) | Skill `prompt-master` | Afinar el prompt inicial del proyecto |
| Fases 0-6 y TOTP | Claude Code (la Fase 0 salió de un plan escrito antes de tocar código) | Diseño, código, tests, commits por fase |
| Pruebas de interfaz | Chrome DevTools (herramientas MCP) | Probar el frontend real en el navegador: login, 2FA, notas, consola sin errores de CSP |
| Rediseño (9 nov) | Skills `impeccable`, `design-taste-frontend` y `emil-design-eng` | Dirección de diseño, anti-«aspecto de IA genérica» y detalles de interacción |
| Rediseño (9 nov) | Worktree de git (rama aparte) | Aislar el rediseño sin tocar la versión que funcionaba |

> Esa misma mañana se instalaron más skills de diseño (por ejemplo `ui-ux-pro-max`, `find-skills`), pero **no consta que se usaran**
> en el rediseño; no deben citarse como usadas.

## 4. Cronología (fechas y horas reales de los commits)

Todo el backend y el 2FA se construyeron **el mismo día, 6 de noviembre de 2026, en unas 2 h 20 min** (de las ~17:06 a las 19:25),
con confirmación humana entre fases. Es un dato llamativo, pero conviene contarlo con honestidad: es trabajo asistido por IA,
con un plan previo muy detallado.

| Fecha y hora | Fase | Qué se añadió |
|---|---|---|
| 6 nov, 17:25 | 0 | Diseño: tema, paquetes, tablas, modelo de amenazas (STRIDE resumido). `.gitignore` |
| 6 nov, 17:48 | 1 | Scaffolding, SQLite con JDBC, registro, política de contraseñas, hash **Argon2id** |
| 6 nov, 17:58 | 2 | Login, **JWT de 10 min**, **refresh token rotatorio** con detección de reutilización, logout |
| 6 nov, 18:12 | 3 | **Rate limiting** (Bucket4j), bloqueo progresivo de cuentas, auditoría |
| 6 nov, 18:25 | 4 | Verificación de email y recuperación de contraseña (token de un solo uso) |
| 6 nov, 18:39 | 5 | Roles, panel de administración, **propiedad de recursos (anti-IDOR)**, cabeceras, CORS/CSRF, frontend |
| 6 nov, 18:45 | 6 | **Revisión de seguridad contra OWASP ASVS 4.0 nivel 2**, límite de tamaño de peticiones, vida absoluta de sesión, README |
| 6 nov, 19:11 | extra | **Segundo factor TOTP (RFC 6238)** con códigos de recuperación |
| 6 nov, 19:25 | extra | Guía rápida de arranque y pruebas en el README |
| 9 nov, 11:28 | extra | **Rediseño visual completo del frontend** (8 páginas) |
| 9 nov, 13:00-13:09 | docs | `Notas.md` (traspaso), contexto del rediseño rescatado, verificación final |

**Cifras:** 161 tests automáticos, todos en verde con `mvn verify` (comprobado el 9 de noviembre sobre la versión con el rediseño:
`BUILD SUCCESS`, 0 fallos). Unas 3.200 líneas de Java de producción y unas 2.600 de tests. Evolución de los tests por hito:
20 → 48 → 70 → 92 → 123 → 129 → 161.

## 5. Qué hace el sistema (la parte técnica, en lenguaje llano)

**Stack:** Java 21 (compilado con `release 21`, funciona con JDK 23), Spring Boot 3.5.16, Spring Security 6, SQLite con
`sqlite-jdbc`, BouncyCastle (para Argon2), Bucket4j (límites de uso). Frontend en HTML, CSS y JavaScript puros, sin framework ni
bundler.

**Autenticación y sesión**
- Contraseñas guardadas con **Argon2id** (nunca en claro). Política: 12-128 caracteres, sin reglas de composición absurdas.
- Sesión con **dos tokens**: un *access token* (JWT) de solo 10 minutos que vive únicamente en la memoria del navegador, y un
  *refresh token* opaco en cookie `HttpOnly; Secure; SameSite=Strict`, guardado **hasheado** en la base de datos.
- El refresh token **rota en cada uso**; si alguien presenta uno ya usado (posible robo), se revoca toda la familia de sesiones.
  La sesión tiene una vida absoluta de 30 días aunque se renueve.
- «Cerrar sesión en todos los dispositivos» invalida al instante todos los tokens.
- El **rol y el estado de la cuenta se leen de la BD en cada petición** (no van dentro del token), así que desactivar a un usuario
  o cambiarle el rol surte efecto de inmediato. Coste: una consulta por petición, a cambio de revocación inmediata.

**Defensa contra ataques**
- **Fuerza bruta:** límite de intentos por IP y por cuenta, y bloqueo progresivo (1, 2, 4… hasta 30 minutos).
- **Enumeración de usuarios:** mismas respuestas y mismos tiempos para una cuenta existente y una inventada (incluido el registro
  y la recuperación de contraseña).
- **Acceso a datos ajenos (IDOR):** el propietario de una nota sale siempre del token verificado, nunca de lo que envía el cliente,
  y cada consulta filtra por usuario. Borrar una nota ajena y borrar una inexistente dan **el mismo 404**. Ni un administrador ve
  las notas de otros.
- **XSS:** política de seguridad de contenido (CSP) estricta, sin scripts ni estilos en línea; los datos se pintan como texto, no como
  HTML. Hay un test que impide que reaparezcan funciones peligrosas en el frontend.
- **Inyección SQL:** solo consultas parametrizadas.
- **CSRF, CORS, cabeceras de seguridad** y errores sin trazas ni detalles internos.
- **Auditoría** de eventos de seguridad (login, bloqueos, reseteos, 2FA, acciones de administrador) sin contraseñas ni tokens.

**Verificación en dos pasos (TOTP)**
- Implementada **sin dependencias nuevas** (HMAC del JDK), validada contra los **vectores de prueba oficiales del RFC 6238** y
  contrastada con un generador independiente en Python.
- El secreto se guarda **cifrado** (AES-256-GCM); los códigos de recuperación (8) solo como hash y de un solo uso.
- Un código no se puede reutilizar, y los fallos cuentan para el bloqueo de la cuenta.
- Decisión de diseño: **el reseteo de contraseña por email NO quita el 2FA**; si bastara el correo, un correo robado anularía el
  segundo factor. Quien pierde el dispositivo lo recupera a través de un administrador (auditado).

**Roles y administración:** `USER` y `ADMIN`; panel para listar usuarios, activar/desactivar cuentas, cambiar roles, forzar cierre
de sesiones y quitar el 2FA. Un administrador no puede desactivarse ni quitarse el rol a sí mismo.

**Otros:** verificación de email y recuperación de contraseña con **enlaces de un solo uso** (con el token en el fragmento de la URL
para que no quede en logs). Los emails son **simulados** (se imprimen en la consola de la app).

## 6. La revisión de seguridad

Se hizo una **autorrevisión** contra OWASP ASVS 4.0 nivel 2 (informe en `SECURITY-REVIEW.md`). Encontró y corrigió tres cosas:
un JSON de 3 MB se leía entero antes de rechazarse (ahora límite de 64 KB), la caducidad deslizante permitía mantener una sesión
robada para siempre (ahora hay vida absoluta de 30 días) y faltaba el aviso por email al cambiar la contraseña.

**Huecos que se declararon abiertos** (no se ocultan): no se comprueban contraseñas filtradas o comunes, no hay análisis de
vulnerabilidades de dependencias, no hay TLS propio, la BD no está cifrada (solo los secretos TOTP) y no hay limpieza de datos
caducados.

> **Importante para el post:** es una autorrevisión, **no una auditoría independiente ni un test de intrusión**, y no se ha pasado
> ningún escáner. No debe presentarse como «aplicación auditada» ni «100 % segura».

## 7. El rediseño: de la versión inicial a la libreta naranja

### Antes (versión inicial, commit `c6d3a4f`)
Se puede describir con precisión porque el código está en git: una **sola columna** de tarjetas blancas con borde fino sobre un
fondo gris muy claro (`#f6f7f9`), título «Tablero de notas» en una cabecera sencilla, **botones azules** (`#1d4ed8`), tipografía del
sistema, unas 70 líneas de CSS y modo oscuro automático. Era funcional y accesible, pero genérico. Con palabras de quien lo hizo: la web
era *«muy monótona y simple, muy hecha con IA»*, y se pidió algo más natural y profesional.

### Después (commit `97595d2`, 9 de noviembre)
- **Concepto: una libreta de espiral convertida en producto.** Cabecera y panel de acceso en **naranja de cubierta (`#ff6a1a`)
  con tinta negra** (regla firme: nunca blanco sobre naranja, por contraste), lienzo de **papel cuadriculado** con celda fija de
  24 px que sirve de módulo de espaciado, y hojas con **microperforación** en el borde superior (la «firma» del diseño, hecha con
  degradados radiales repetidos en CSS).
- **Pantalla de acceso en dos mitades:** a la izquierda el campo naranja con la marca, el titular **«Tus notas. Solo tuyas.»** y
  tres hechos reales del producto; a la derecha un único formulario sobre hoja con selector «Entrar / Crear cuenta». En móvil, una
  banda naranja compacta arriba y el formulario debajo.
- **Cabecera común** con navegación, avatar y menú de cuenta; **notas** con panel de escritura, hojas con fecha y estado vacío;
  **seguridad** con indicador de estado y pasos numerados para activar el 2FA; **administración** con rol y estado como etiquetas y
  tabla que en móvil pasa a fichas.
- Modo claro y oscuro (en oscuro: cubierta negra, papel grafito, naranja claro de acento), respeto al movimiento reducido y
  tipografía del sistema.
- El CSS pasó de ~70 a ~400 líneas; se rediseñaron las **8 páginas**.

### Cómo se llegó a esa idea (proceso de las skills)
1. **Petición:** usar «todas las skills necesarias» de las instaladas para mejorar una web que se veía monótona y hecha con IA.
2. **Skills:** `design-taste-frontend` (criterio contra el diseño genérico), `impeccable` (dirección de diseño estructurada) y
   `emil-design-eng` (detalles de interacción). Se trabajó en una **rama/worktree de git aparte** para no romper lo que funcionaba.
3. **Contexto de producto:** se redactó un `PRODUCT.md` (a quién sirve, restricciones, qué evitar).
4. **Dirección de diseño («direction contract»):** la herramienta repartió **6 «mundos» visuales candidatos** y se eligió uno.
   Candidatos: cuadrícula de Crouwel, horario de diapositivas, cartera de billetes de la era del jet, manual de acetato, web
   japonesa de alta densidad y Ikeda. **Se eligió la libreta de espiral de cubierta naranja** (estilo Rhodia). De los otros se
   «robaron» ideas concretas: la **disciplina de la retícula visible** de Crouwel (todo alineado a la celda de 24 px), la
   **densidad compacta** de la web japonesa para la tabla de administración y la idea de los billetes de que **los estados se
   «sellan»** y nada desaparece en silencio.
5. **Restricciones técnicas que se mantuvieron:** la CSP **no cambió** (sin estilos ni scripts en línea, sin fuentes ni recursos
   externos, por eso **no hay fuentes web**: `[TRADE-OFF]` consciente, en producción se alojarían en el mismo origen), sin
   dependencias nuevas y sin tocar los IDs que usa el JavaScript. Los tests de guardia siguen en verde.
6. **Verificación:** `mvn verify` en verde tras el rediseño y pruebas manuales en el navegador.

## 8. Qué se aprendió (material para el post)

- **El plan detallado antes del código** (modelo de amenazas, fases, reglas) fue lo que permitió ir rápido sin perder el control.
- **Trabajar por fases con confirmación humana** y exigir salida real de los tests evita el «funciona, confía en mí».
- **La seguridad está en los detalles que no se ven:** respuestas idénticas para cuentas que existen y que no, el dueño sale del token
  y no de la petición, revocar al instante leyendo el rol de la BD, no reutilizar un código TOTP…
- **Ser honesto con los límites** (autorrevisión, emails simulados, sin QR, sin TLS) forma parte del trabajo.
- **El rediseño sin romper nada** fue posible gracias a los tests de guardia, la CSP estricta como restricción creativa y un
  worktree de git aislado.
- **Las skills de diseño** ayudaron a salir del «aspecto de IA genérica» porque obligan a elegir una dirección con una tesis
  concreta, en vez de aplicar el patrón por defecto (tarjeta blanca centrada sobre gris con botón azul).
- Una limitación honesta de trabajar con IA: **el humano decide y verifica** (probó el flujo a mano, subió cada commit, pidió
  correcciones); la IA acelera pero no sustituye el criterio.

## 9. Lo que NO está comprobado o no se hizo (no afirmar lo contrario)

- **No es una auditoría independiente ni un pentest.** Sin escáner de vulnerabilidades, sin análisis de dependencias (CVE).
- **Los emails son simulados:** no llega nada a ninguna bandeja; los enlaces salen en la consola de la app.
- **Sin código QR** para el 2FA (se introduce la clave a mano; evitaba añadir una librería).
- **No está desplegado** ni usa HTTPS propio; corre en local.
- **Revisión visual del rediseño incompleta:** consta la prueba manual de registro, login, 2FA, notas, cierre de sesión y reseteo,
  pero **no una revisión sistemática de cada página** (administración, verificación de email, móvil, modo oscuro). Tampoco se ha
  probado en navegadores distintos de Chrome ni con lector de pantalla.
- **El contrato de diseño preveía una revisión final y un `DESIGN.md`** (sistema de diseño documentado) que **no se llegaron a
  generar**.
- Se desconoce qué conversación exacta decidió cada detalle estético; el proceso de la sección 7 sale de los artefactos que dejó
  la herramienta (`PRODUCT.md` y el contrato de dirección), no de una grabación.
- Las **capturas no están en el repositorio**: hay que sacarlas aparte (ver sección 11).

## 10. Instrucciones para quien redacte el post

- **Autoría:** el proyecto es de alguien en formación como desarrollo de software; el post debe sonar a **aprendizaje en primera
  persona**, no a producto comercial. Ajusta el género al de la persona; este archivo no lo indica a propósito.
- **Tono:** cercano, concreto, sin humo. Mejor 2-3 ideas con un dato real cada una que una lista larga. Español.
- **Datos que sí puedes usar** (todos comprobados): 161 tests en verde; Argon2id; JWT de 10 minutos con refresh rotatorio;
  TOTP validado con los vectores del RFC 6238; revisión contra OWASP ASVS 4.0 nivel 2 (como autorrevisión); backend construido en
  una tarde de 6 de noviembre y rediseño el 9; 8 páginas rediseñadas; CSS de ~70 a ~400 líneas; concepto «libreta de espiral
  naranja y papel cuadriculado».
- **Gancho posible:** el contraste entre una app «correcta pero aburrida» y la misma app con una idea de diseño; o «lo difícil de la
  seguridad es lo que no se ve».
- **No digas:** que está auditada, que es «imposible de hackear», que está en producción, que el rediseño lo hizo la IA «sola»
  (hubo dirección, decisiones y verificación humanas), ni que se usaron skills que no figuran en la sección 3.
- **Etiquetas orientativas:** #Java #SpringBoot #Ciberseguridad #OWASP #ClaudeCode #Diseño #AprendiendoEnPúblico
  (a criterio de la persona).
- **Capturas:** usa «antes» (azul, tarjetas blancas, una columna) y «después» (naranja, papel cuadriculado, dos mitades). Pide a
  la persona confirmar que no se ven emails reales, contraseñas ni datos personales en las capturas.
- Si falta algún dato para el post, **pregunta en vez de inventarlo**.

## 11. Cómo sacar las capturas (para la persona)

- **Después (versión actual):** `mvn spring-boot:run`, abrir `http://localhost:8080`. Páginas con más impacto: acceso (`/`),
  notas (con alguna nota creada), seguridad (2FA activado) y administración.
- **Antes (versión inicial):** el código antiguo está en el commit `c6d3a4f`. Con la app parada, se puede obtener una copia en una
  carpeta aparte (`git worktree add ..\Login-antes c6d3a4f`), arrancarla allí con el mismo `.env` y capturar; después se elimina la
  copia. (No se ha hecho todavía; se puede pedir a Claude Code.)
- Cuidado con datos reales en pantalla (emails, notas).
