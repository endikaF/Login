---
version: 1
slug: "src-main-resources-static-index-html"
primary_target: "src/main/resources/static/index.html"
related_targets: []
---

# Rediseño completo del frontend (8 páginas) — modo Operate

Scope: toda la interfaz estática (acceso/registro, notas, seguridad, administración, recuperar y restablecer contraseña, verificar email). Modo Operate con una puerta de entrada (acceso) más expresiva. Audiencia: personas con cuenta propia que guardan notas privadas y administradores. Éxito: entrar, escribir y encontrar notas sin fricción; seguridad que se siente sólida. Restricciones: CSP estricta (sin estilos/scripts en línea, sin recursos externos, solo `data:` para imágenes), sin fuentes web, sin dependencias, JS sin APIs de HTML en cadena. Pendiente de decidir: nada bloqueante.

## Direction contract

THESIS: la libreta de espiral de cubierta naranja y papel cuadriculado (Rhodia) convertida en producto de trabajo: una carcasa naranja comprometida con tinta negra sobre un lienzo de papel cuadriculado frío. Rechaza el arreglo por defecto de tarjeta blanca centrada sobre gris con botón azul y titulares sin carácter.

OWN-WORLD: naranja de cubierta (#ff6a1a) como campo de la carcasa y de la acción principal, siempre con texto negro (nunca blanco sobre naranja); tinta casi negra; papel blanco con cuadrícula azul pálido de celda fija (24px) en el lienzo; microperforación (fila de agujeros) en el borde superior de cada hoja; sans del sistema en pesos 800 apretados para títulos y tabular para cifras; monoespaciada solo para claves y códigos. Modo oscuro: cubierta negra, papel grafito, naranja claro como acento.

STORY: el visitante entiende en una pantalla que sus notas son suyas y privadas, entra o crea cuenta con un solo foco, y una vez dentro ve una libreta ordenada: nueva nota a la izquierda, hojas a la derecha, cuenta y seguridad a un clic.

FIRST VIEWPORT: acceso en dos mitades a 1440x900. Izquierda, campo naranja completo con la marca (monograma de libreta), titular enorme en negro «Tus notas. Solo tuyas.», tres líneas de hechos verdaderos del producto con iconos y una hoja de libreta decorativa con perforación asomando por abajo. Derecha, lienzo cuadriculado con un único formulario sobre hoja: selector «Entrar / Crear cuenta», campos, botón naranja a todo el ancho. En móvil, banda naranja compacta arriba y formulario debajo.

FORM: candidato 3 de mi lista ordenada de mundos de la audiencia (libreta Rhodia), asignado por la tirada. seed:cec539d45cf9f500. Challengers pesados: cuadrícula de Crouwel (competitivo; aporta la disciplina de la retícula visible: todo se alinea a una celda de 24px), horario de diapositivas (declinado), cartera de billetes jet-age (declinado; disciplina robada: los estados se sellan, nada desaparece en silencio), manual de acetato (declinado), alta densidad japonesa (competitivo para la tabla de administración: densidad compacta), Ikeda (declinado). Raises: celda de 24px como módulo de espaciado (Crouwel); estados con marca visible y persistente en administración (jet-age).

SIGNATURE MOVE: la microperforación del borde superior de cada hoja (notas, formularios, hoja decorativa), construida con degradado radial repetido; el campo naranja de la carcasa.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
