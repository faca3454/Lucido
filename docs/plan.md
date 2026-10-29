# Plan de nuevas funciones

Estado: **acordado, sin programar**. Las decisiones salen de la charla de diseño; lo marcado como
⚠️ *Pendiente* falta definir.

Recordatorio de cómo está armada la app hoy:
- `MainActivity` es un WebView que abre Instagram y YouTube web.
- `assets/block.user.js` es el script de bloqueo que se inyecta en las páginas. Se actualiza solo
  desde `main` sin sacar un APK nuevo.
- `AppGuard` es un servicio de accesibilidad: si se abre la app oficial, salta a Brainrotless.

---

## 1. Reels que te mandan: sin poder bajar al siguiente ✅

**Hecho y probado en el teléfono.**

**Problema:** hoy abrís un reel desde un chat y podés seguir deslizando a otros reels.
La guardia actual falla por alguna de estas causas:
- Instagram cambia de reel sin cambiar la URL.
- El deslizamiento se toma como "toque reciente" y se deja pasar.
- La vuelta tarda (revisa cada 400 ms y recarga la página entera).

**Solución (capas 1 y 2; descartado el reproductor embebido):**
- **Capa 1 – La pantalla no se mueve.** En `/reel/…` y `/reels/…` el script bloquea el gesto
  vertical: `touchmove` con `preventDefault` (`passive: false`), `touch-action` / `overscroll-behavior`
  en los contenedores, rueda y teclas de flecha. Tocar, pausar, likear y comentar siguen andando.
- **Capa 2 – Si igual cambia, vuelve al mismo reel.** La guardia de URL queda sin la excepción de
  "tocaste hace poco": una vez abierto un reel, cualquier otro id vuelve al original. Como es el
  respaldo de la capa 1, la vuelta sigue recargando la página: es más lenta pero no falla.
- Por ahora solo reels. Historias y posts con varios videos quedan para más adelante.

Solo toca `block.user.js`, así que se puede publicar sin sacar un APK nuevo.

---

## 2. Instagram: ajustes de uso ✅

**Hecho; falta probar en el teléfono.** Las redirecciones de bloqueo (Reels, Explorar → mensajes)
siguen recargando la página: sin poder probar el router de Instagram, una navegación "suave" podría
dejar el contenido bloqueado en pantalla con otra URL. Como los accesos a esas pantallas están
ocultos, casi nunca pasa.

### 2.1 Videos solo al tocar (ahorro de datos real)
- Ajuste global, prendido o apagado. Solo Instagram.
- El script intercepta `HTMLMediaElement.play()`: si no hubo un toque sobre ese video, no
  reproduce. Pone `preload="none"` para que no descargue nada y muestra un botón ▶ encima.
- **Excepción:** los reels que abrís desde un mensaje arrancan solos (ya tocaste para abrirlos).
- No usar solo `mediaPlaybackRequiresUserGesture` del WebView: rompería esa excepción.

### 2.2 Feed de seguidos
- Reemplaza al viejo "Bloquear feed de inicio" con 3 opciones: normal / solo seguidos / bloqueado.
  Por defecto, normal (hasta confirmar que el feed de seguidos anda en el teléfono).
- `/` → `/?variant=following`: solo cuentas que seguís, en orden cronológico, y termina solo con
  "Ya viste todo". Sin tope.
- Los videos de ese feed siguen la misma regla del punto 1.
- Hay que confirmar en un teléfono que `?variant=following` funciona en Instagram web móvil.

### 2.3 Sacar los carteles de "Usá la app"
- Ocultar con CSS (y cerrar si hace falta) los banners de "Abrir en la app" / "Usá la app de
  Instagram".

---

## 3. Modo dormir ✅

**Hecho; falta probar en el teléfono.** Quedan para más adelante, porque dependen de otras partes:
- Detectar si cambian la hora del teléfono: solo importa con el compromiso (punto 5); sin compromiso
  el modo se puede apagar desde Ajustes.
- ~~Sacar al usuario de una app oficial que ya estaba abierta (con la pausa) justo cuando empieza la
  noche~~: hecho en el punto 4.
- La pausa de emergencia de noche (opción B) es parte del compromiso (punto 5).

- **Horario configurable por día de la semana**; por defecto, el mismo para todos.
  Ej.: 00:00 a 06:30.
- **Aviso antes de empezar**, con anticipación configurable (ej. 15 min), por notificación
  (`AlarmManager`).
- **Al llegar la hora te saca en el acto**, sin tiempo de gracia.
- **Qué queda permitido de noche ("modo solo mensajes"):**
  - Instagram: solo `/direct/…`. Cualquier otra ruta vuelve al inbox y se ocultan los accesos a
    inicio, explorar, reels, historias y perfiles. Los mensajes se leen; **los reels que te mandan
    no se abren**.
  - YouTube: bloqueado entero (pantalla "Hora de dormir 🌙, se desbloquea a las 06:30").
  - Apps oficiales: `AppGuard` ya las redirige a Brainrotless, que aplica lo mismo.
  - El botón de "pausar el bloqueo" (tile) no funciona en ese horario.
- **Trampa de cambiar la hora del teléfono:** se detecta con `elapsedRealtime` y el broadcast
  `TIME_SET`.

El "modo solo mensajes" es el mismo que usa el límite diario (punto 5.3).

---

## 4. Navegadores bloqueados de noche (anti-gooning) ✅

**Hecho; falta probar en el teléfono.** Ajuste "Bloquear navegadores" dentro de Modo dormir
(prendido por defecto), que muestra qué navegadores detectó. Además de los que se declaran
navegador, cuenta las apps que abren cualquier link web. Si la noche empieza con un navegador o una
app oficial abierta, te saca en el acto.

- **Solo durante el horario de dormir.** No hay filtro 24/7.
- **Qué apps cuentan:** solo las que son navegadores (Chrome, Brave, Opera GX, Firefox, Samsung
  Internet…). Se detectan solas con `Intent.makeMainSelectorActivity(ACTION_MAIN,
  CATEGORY_APP_BROWSER)`, así que se incluyen los que se instalen después. Telegram, X, Google,
  etc. **no** se bloquean.
- `AppGuard` pasa a escuchar todas las apps (se filtra en código). Si un navegador pasa a primer
  plano en horario bloqueado: `GLOBAL_ACTION_HOME` + aviso.
- Consecuencia aceptada: de noche, los links externos que abrís desde IG Lite tampoco abren.
- Hay que actualizar la sección de privacidad del README: el servicio ve qué app está en primer
  plano en todo el teléfono, pero sigue sin leer contenido.
- Manifest: `<queries>` para la categoría de navegadores.

---

## 5. Compromiso

**5.1 a 5.3 hechos; falta probar en el teléfono.** Decisiones tomadas al programarlo:
- El límite diario solo corre durante un compromiso (fuera de él, "Uso de hoy" igual muestra el tiempo).
- La pausa de emergencia se puede usar al llegar al límite **o de noche** (aunque no se haya llegado
  al límite), como se acordó para la noche.
- Para empezar un compromiso hace falta tener el modo dormir prendido o un límite elegido.
- La confirmación tiene 5 segundos de espera antes de poder tocar "Empezar".
- Cambiar la hora del teléfono durante el compromiso se compensa (TrustedClock): no adelanta el
  final ni saltea la noche. Cambiar la zona horaria no se compensa.
- El uso de las apps oficiales con la pausa del bloqueo también suma al límite.
- **Reels dentro del límite** (pedido después): con un límite corriendo, Instagram deja ver y
  scrollear Reels hasta llegar al límite. De noche no, y la pausa de emergencia tampoco los abre
  (se usa pasado el límite). Sin compromiso con límite, los Reels siguen bloqueados como siempre.
  Ajuste "Reels dentro del límite diario", prendido por defecto.
- Los APK de prueba tienen "Terminar compromiso (solo prueba)"; los de Releases no.

### 5.1 Configuración
- Duración: **mínimo 7 días**, máximo lo que elija el usuario.
- Antes de confirmar, un aviso claro de lo que no se va a poder cambiar.
- **Queda bloqueado durante el compromiso:** horario de dormir, bloqueo de navegadores y límite
  diario.
- **Se puede endurecer pero no aflojar:** acostarse antes, despertarse más tarde, bajar el límite.
- No afecta los ajustes de Instagram (videos solo al tocar, feed de seguidos).

### 5.2 Pausas de emergencia
- Cantidad: `floor(días / 2)`. Ej.: 7 días → 3 pausas; 30 días → 15.
- Duran **15 minutos** cada una.
- Máximo **2 por día**, también en compromisos de una semana.
- Desbloquean **todo**: Instagram, YouTube y navegadores.
- El tiempo de pausa **no cuenta** para el límite diario.
- **Solo se pueden usar cuando se pasó el límite diario.** Antes de llegar al límite, el botón no
  aparece.
- **De noche (horario de dormir) la pausa abre Instagram completo** (feed, perfiles, reels que te
  mandan) durante 15 minutos. Navegadores y YouTube siguen bloqueados.

### 5.3 Límite diario de uso
- Se define dentro del compromiso: minutos por día para **Instagram + YouTube juntos**.
- Al llegar al límite: **YouTube se para por completo** e **Instagram queda en "modo solo
  mensajes"** (igual que de noche). Los navegadores **no** se bloquean por el límite, solo de noche.
- Avisos cuando queda poco (ej. a los 10 y a los 2 minutos).
- El contador se reinicia **a la hora de despertarse**, no a medianoche.
- Se puede bajar durante el compromiso, no subir.
- **Todo suma al límite**, también el tiempo en mensajes. Una vez alcanzado, los mensajes se
  siguen pudiendo usar (modo solo mensajes).
- Medición: tiempo con IG Lite / YT Lite en primer plano (pantalla encendida), guardado por día.

### 5.4 Al terminar ✅

**Hecho; falta probar en el teléfono.** Notificación a la hora de terminar y, al abrir la app,
resumen (uso por día antes y durante, cuánto bajó, días que llegó al límite, pausas usadas) con
"Empezar otro" / "Ahora no". El uso de antes se calcula una sola vez y se guarda; si no hay permiso,
el resumen muestra solo el uso durante. El último resumen queda en Ajustes → "Último compromiso".
- **No se renueva solo.** Pregunta si querés empezar otro compromiso.
- **Estadística final:** cuánto se redujo el uso. Pide el permiso *Acceso a datos de uso*
  (`PACKAGE_USAGE_STATS`) para leer cuánto usabas Instagram y YouTube oficiales en las semanas
  anteriores a instalar Brainrotless, y lo compara con el uso durante el compromiso.

### 5.5 Difícil de sacar (molesto, no imposible) ✅

**Hecho; falta probar en el teléfono.** Limitación: la espera con cartel solo se puede poner al
quitar la protección contra desinstalación. Para la accesibilidad no se puede detectar que alguien
está por apagarla sin leer el contenido de la pantalla (y eso no lo hacemos); en su lugar, el aviso
fijo vuelve a aparecer si lo cerrás y IG Lite / YT Lite no abren. En Android 14+, el sistema deja
deslizar los avisos fijos: por eso se vuelven a publicar.
La app va a ser pública, así que no usamos el modo "propietario del dispositivo".
> Después se sacó el administrador de dispositivo (y su espera de 2 minutos): Play Protect lo
> toma como señal de app peligrosa. Queda el escudo de la accesibilidad contra desinstalar.
- **Administrador de dispositivo:** para desinstalar hay que quitarle ese permiso primero; al
  intentarlo, aviso de "vas a romper tu compromiso de X días".
- **Accesibilidad apagada:** notificación fija que no se puede cerrar. Mientras siga apagada,
  IG Lite y YT Lite no abren.
- **Espera con cartel** de unos minutos antes de poder hacer cualquiera de las dos cosas, para
  cortar el impulso.

---

## 6. Fluidez ✅

**Hecho; falta probar en el teléfono.**
- Dos WebViews (Instagram y YouTube) vivas, con fundido de 180 ms al cambiar; la de fondo pausa
  sus videos. Tocar un ícono vuelve a donde estabas en esa plataforma, sin recargar.
- Transiciones suaves (ajuste en "Comodidad"): fundido entre páginas que se recargan
  (`@view-transition`) y al navegar dentro del sitio (`startViewTransition`, sin demorar el cambio
  de URL y nunca en páginas de video).
- Deslizar para responder (experimental, ajuste en "Comodidad"): se prueba primero el botón que
  aparece al pasar el mouse y después el menú de mantener presionado. Depende del HTML de Instagram.
- También: Shorts dentro del límite diario, igual que los Reels.

- **Sin saltos bruscos:** las redirecciones del script usan `history` en lugar de
  `location.replace` (que recarga la página y hace flash).
- Fondo oscuro del WebView mientras carga, para que no haya flash blanco.
- Dos WebViews (IG y YT) que se mantienen vivos, con transición suave al cambiar, en vez de
  recargar uno solo.
- Transiciones entre páginas con View Transitions (`document.startViewTransition`), a prueba y
  error.
- Más adelante: deslizar un mensaje para responder (frágil, depende del HTML de Instagram).

---

## 7. Pedidos de octubre de 2026

Estado: **acordado**. La tanda A va solo en `block.user.js` (les llega a todos sin reinstalar); la
tanda B necesita un APK nuevo (1.3.0). Ningún cambio renombra ajustes guardados: los ajustes nuevos
arrancan con su valor por defecto y el compromiso en curso no se toca.

### 7.1 Anuncios (tanda A; el interruptor, tanda B)
- No se puede usar un bloqueador hecho: la WebView no lleva extensiones, y los bloqueadores por DNS o
  de todo el teléfono no ven estos anuncios (vienen de los mismos servidores que los videos y las
  publicaciones). Se escribe en el script con la receta de uBlock Origin, sin copiar su código (GPL).
- YouTube: ocultar las tarjetas patrocinadas; borrar `adPlacements`, `playerAds` y `adSlots` de los
  datos del video antes de que YouTube los lea; si igual aparece un anuncio, silenciarlo y saltarlo.
- Instagram: ocultar las publicaciones con "Publicidad" / "Sponsored" y saltar las historias
  patrocinadas.
- Prendido por defecto (`igBlockAds`, `ytBlockAds`); el interruptor llega con la 1.3.0.
- Si YouTube cambia la forma de meter anuncios: mirar qué cambió uBlock Origin y copiar la idea.

### 7.2 Historias en "Solo seguidos" (tanda A)
- Instagram no muestra la fila de historias en `/?variant=following`. Primero se dibujó una propia
  arriba de ese feed (con los datos de la de Instagram), pero sus historias no tenían la fila de
  Instagram detrás: al terminar cada persona salía al inicio y había cortes y recargas. No se puede
  abrir Instagram dentro de un marco (`X-Frame-Options: DENY`).
- **Decidido después de la cuarta prueba (@version 20):** "Solo seguidos" pasa a ser el inicio normal
  de Instagram (su fila y su visor de historias, tal cual) sin lo que no es de cuentas que seguís: las
  publicaciones con "Seguir" o "Sugerencias para ti" arriba y los carruseles de sugerencias se achican a
  1 px; desde "Estás al día" todo lo que sigue desaparece (`display:none`, para que Instagram deje de
  cargar sugerencias) y ahí termina. El orden es el de Instagram, no estrictamente cronológico. El
  ajuste guardado sigue siendo `ig_feed = following`.
- "Bloqueado" sigue igual: el inicio vuelve al inbox.

### 7.3 Videos solo al tocar que no gasten datos (tanda A)
- Antes solo frenaba la reproducción: Instagram igual bajaba los videos con su propio reproductor.
- `preload="none"` y sin autoplay desde que se crea el video, y los pedidos de video quedan en
  espera hasta que tocás uno (o abrís un reel o una historia).
- Para comparar antes y después, los APK de prueba miden los datos que baja la app.

### 7.4 Estadísticas con el ícono escondido (tanda B)
- Con "Esconder el ícono" el inicio se apaga y no había otro camino a las estadísticas (los widgets
  tampoco abrían). Caminos nuevos: atajo "Estadísticas" al mantener presionado IG Lite / YT Lite,
  los widgets abren las estadísticas si el ícono está escondido, una fila en Ajustes y la comparación
  con antes en la pantalla del compromiso.

**Primera prueba en el teléfono (@version 16):** YouTube sin anuncios ✅. Instagram: ocultar la
publicación con `display:none` cortaba la carga del resto del feed, y las historias patrocinadas no se
salteaban (probablemente la etiqueta quedaba después de los primeros 400 textos de la página). Ahorro de datos: 8 MB
en 5 s. Arreglos en @version 17: la publicación se achica a 1 px sin salir de la página (y nunca un
`<article>` con otros adentro o más alto que dos pantallas); la etiqueta de las historias se busca en
toda la página, a la vista y arriba, y se avanza con "Siguiente", la flecha o un toque (también
táctil); los videos de los carruseles ya no cuentan como "visor" (podían abrir todos los pedidos); al tocar
salen 2 pedidos y no 4, y mientras ves uno el comienzo de los otros (`bytestart=0`) sigue esperando.
El informe de "Medir" suma lo que hizo el script en cada página.

**Segunda prueba (@version 17), con video:** el ahorro de datos retiene casi todo (685 pedidos en espera,
466 descartados; lo que salió sería de las historias, que se ven a propósito). En "Solo seguidos" el feed
saltaba: la fila de historias se volvía a poner antes de la "primera" publicación, y al bajar Instagram
saca las de arriba (quedaba en el medio). Al abrir una historia (foto) la pantalla giraba a horizontal: la
regla nueva de pantalla completa era para YouTube. Al salir de una historia, la página se recargaba
entera: lo más probable, Instagram volvía al inicio dentro del sitio y el script lo mandaba a
"Seguidos" recargando.
Arreglos en @version 18: la fila va arriba de la lista y no se mueve; volver al inicio pasa a "Seguidos"
dentro del sitio; al terminar las historias de alguien abiertas desde la fila siguen las de la próxima
persona sin ver (no si las cerraste con la X, deslizando para abajo o con atrás); la rotación de la
pantalla completa, solo en YouTube. "Datos bajados" (APK de prueba) reemplaza a "Medir" para estas
pruebas: MB de la app y, por página, cuánto bajó por tipo (imágenes, video, código), los pedidos de video
y lo que hizo el script, aunque la página se recargue.

**Tercera prueba (@version 18):** sin anuncios en YouTube e Instagram ✅, pantalla completa de YouTube ✅,
ahorro de datos ✅ (Instagram 11 MB en un par de minutos: 8,5 de imágenes y 0 de video, con 113 pedidos
de video en espera). Pero al volver de unas historias quedaba el feed normal ("Instagram", con
sugerencias) con la URL de seguidos, y con dos filas de historias: Instagram cambiaba la URL sin cambiar
de feed. Arreglos en @version 19: si Instagram va a "/" en "Solo seguidos", va directo con
`?variant=following` (así arma ese feed); si a los 1,5 s se ve un feed sin el título "Seguidos", recarga en
seguidos (una vez cada 30 s como mucho, y nunca sin publicaciones a la vista, como en el login); la fila
propia solo se dibuja con el feed de seguidos a la vista.

**Cuarta prueba (@version 19):** pasa a la persona que sigue, pero entre una y otra se ve un instante el
inicio normal, y al final recarga: no es la experiencia del feed normal. Se cambia el enfoque (ver 7.2).

**Quinta prueba (@version 20):** las historias andan como en el feed normal ✅. Pero se ven cuentas que no
seguís: Instagram mezcla recomendaciones sin marcarlas (millo_ush, sin "Seguir" ni "Sugerencia"), la
marca que sí pone es "Sugerencia para ti" (en singular) y tampoco se ocultó un anuncio, así que en el
inicio las publicaciones probablemente no son `<article>`. Arreglos en @version 21: se compara el autor
de cada publicación con la lista de cuentas que seguís (`/api/v1/friendships/<id>/following/`, guardada
en el teléfono un día; tus publicaciones también van), las publicaciones se buscan como `<article>` o,
si no hay, como el contenedor más grande con un solo "Me gusta" (también para los anuncios), y "Sugerencia
para ti" en singular. "Datos bajados" dice cuántos `<article>` y "Me gusta" hay en el inicio y los errores
del script.

**Sexta prueba (@version 21):** el inicio sí usa `<article>` (8) y los anuncios se ocultan (21), pero la
lista de cuentas que seguís nunca llegó (sin nota: el pedido se cortaba sin avisar), así que solo se
ocultaban las marcadas (51), y con tantas publicaciones achicándose la pantalla saltaba. Arreglos en
@version 22: el feed se filtra desde los datos (sección 18: `xdt_api__v1__feed__timeline__connection`, al
leerlos con `JSON.parse` o `Response.json`), así lo que no va ni se dibuja: anuncios y, en "Solo cuentas que
seguís", lo de cuentas que no seguís (según `friendship_status` o la lista) y las sugerencias; dos páginas
seguidas sin cuentas que seguís terminan el feed. Lo que se oculta en la página se mira apenas aparece,
antes de dibujarse. La lista busca tu cuenta en la cookie `ds_user_id` o en `current_user`, y cada falla
queda anotada en "Datos bajados".

### 7.5 Pantalla completa de YouTube como en la app oficial (adelantada a la tanda A)
- Video horizontal en pantalla completa: queda acostado aunque el teléfono esté vertical (gira solo
  entre los dos lados acostados). Video vertical: vertical.
- Con la rotación del teléfono prendida, acostarlo mirando un video entra solo a pantalla completa y
  enderezarlo sale. Con la rotación apagada, solo con el botón.
- Hecho: la del botón queda en `sensorLandscape` (o vertical si el video lo es). La de acostar el
  teléfono no es la del navegador (esa solo se puede pedir con un toque): el script estira el
  reproductor a toda la pantalla (`__lucidoTheater`) y la app esconde las barras; sale al enderezarlo,
  con atrás o al irse del video.

---

## Orden de trabajo

1. **Reels sin scroll** (punto 1). Solo el script.
2. **Instagram:** videos solo al tocar, feed de seguidos, sin carteles de "Usá la app" y
   redirecciones sin recarga (puntos 2 y parte del 6).
3. **Modo dormir** + "modo solo mensajes" + aviso previo (punto 3).
4. **Navegadores bloqueados de noche** (punto 4).
5. **Compromiso:** pausas, límite diario y medición de uso (5.1 a 5.3).
6. **Final del compromiso y protección:** estadística, renovación y difícil de sacar (5.4 y 5.5).
7. **Fluidez:** dos WebViews y transiciones (resto del punto 6).

## Notas técnicas

- Las reglas remotas se descargan de `main`: los cambios del script les llegan a todos recién
  cuando se mergean a `main` y se sube `@version`.
- Configuración nueva que el script necesita (`restricted`, `noAutoplay`, `followingFeed`) se pasa
  por `window.__noscrollCfg` desde `Rules.build`. El estado "de noche" o "límite alcanzado" cambia
  durante la sesión, así que la app tiene que reinyectar o avisarle al script en vivo.
- Permisos nuevos: `PACKAGE_USAGE_STATS`, administrador de dispositivo, `<queries>` de navegadores.
- El README se actualiza con cada función (qué hace y privacidad).
