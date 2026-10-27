# Lúcido

**Instagram y YouTube, sin piloto automático.**

Lúcido es una app para Android que abre Instagram y YouTube sin el scroll infinito de Reels y Shorts, y te ayuda a usarlos menos: modo dormir, horas de foco, un límite diario y estadísticas para ver cómo vas. Gratis, sin anuncios y sin cuentas: todo queda en tu teléfono.

<p align="center">
  <img src="docs/capturas/inicio.png" width="200" alt="Inicio de Lúcido">
  <img src="docs/capturas/estadisticas.png" width="200" alt="Estadísticas: antes vs ahora y racha">
  <img src="docs/capturas/dormir.png" width="200" alt="Modo dormir">
  <img src="docs/capturas/compromiso.png" width="200" alt="Compromiso con límite diario">
</p>

<p align="center">
  <a href="https://github.com/faca3454/Lucido/releases/latest"><b>⬇ Descargar la última versión</b></a> · Android 8 o más nuevo
</p>



## Qué hace

**Sin scroll infinito**
- Los reels y shorts que te mandan tus amigos se ven, pero no podés deslizar al siguiente.
- El feed de Instagram, a tu elección: normal, solo las cuentas que seguís (en orden, se termina y con las historias arriba) o bloqueado.
- Explorar bloqueado y sin carteles de "Usá la app". Si querés, los videos arrancan y se descargan solo cuando los tocás (ahorra datos).
- Sin anuncios: YouTube sin los de los videos ni los de entre videos, e Instagram sin publicaciones ni historias patrocinadas.

**Modo dormir y modo foco**
- **De noche**, en tu horario (puede ser distinto cada día): Instagram queda solo para mensajes, YouTube se bloquea y, si querés, también los navegadores. Te avisa unos minutos antes.
- **En tus horas de foco**, los días que elijas: Instagram solo para mensajes y, si querés, YouTube bloqueado.

**Compromiso**
- Elegís cuántos días (7 o más) y un **límite diario** para Instagram y YouTube juntos. Mientras dure, solo podés hacerlo más estricto.
- Al llegar al límite, Instagram queda solo para mensajes y YouTube se bloquea hasta que te despertás.
- **Pausas de emergencia** de 15 minutos para cuando de verdad lo necesitás.
- Al terminar, un resumen de cuánto bajó tu uso.
- Si querés, mientras te quede tiempo, IG Lite y YT Lite abren las apps oficiales de Instagram y YouTube; al terminarse, volvés a las Lite.

**Estadísticas**
- Antes vs ahora: cuánto usabas Instagram y YouTube antes de Lúcido y cuánto ahora.
- Uso por día, Instagram vs YouTube, rachas y un resumen los domingos.

**Notificaciones**
- Los mensajes de Instagram y YouTube se abren en Lúcido, no en las apps oficiales.
- Con la foto de quien te escribe y juntos como conversación. Cuando Instagram lo permite, podés responder sin abrir nada.

**IG Lite y YT Lite, como una app**
- Vuelven al instante a donde estabas, y las notificaciones abren el chat directo.
- Los links de afuera se abren encima, sin salir. Fotos desde la galería, audios con el micrófono y la pantalla prendida mientras ves un video.
- Deslizar para responder en los chats.
- Videos de YouTube en pantalla completa como en la app oficial: acostados aunque tengas el teléfono derecho y, con la rotación prendida, acostar el teléfono los pone en pantalla completa.

**Y además**
- Aviso cuando llevás mucho rato seguido.
- Widgets para la pantalla de inicio.
- Links de Instagram y YouTube (de WhatsApp, Telegram, etc.) que se abren en Lúcido.
- En español e inglés, con modo claro y oscuro.

## Instalar

1. Bajá `Lucido-X.Y.Z.apk` de la [última versión](https://github.com/faca3454/Lucido/releases/latest) (en *Assets*).
2. Abrilo y permití instalar apps de esa fuente. Si Play Protect avisa, tocá *Instalar igual*: pasa con cualquier app que no venga de Play Store.
3. Abrí **Lúcido**. Una guía corta te lleva por el idioma y con qué querés arrancar. No pide permisos de entrada: cada función pide el suyo cuando la activás.

Después de instalar vas a ver tres íconos: **Lúcido** (el inicio y la configuración), **IG Lite** (Instagram) e **YT Lite** (YouTube).

- **Las apps oficiales de Instagram y YouTube tienen que seguir instaladas**: son las que reciben las notificaciones. Si las abrís, Lúcido te trae de vuelta (salvo que actives *Apps oficiales dentro del límite*).
- **¿Un permiso aparece gris?** Android lo hace con las apps que no vienen de Play Store. Al lado de cada permiso que pide Lúcido hay un botón *¿Un permiso aparece gris?* que explica cómo destrabarlo.
- **En Xiaomi o Samsung**: Ajustes → Batería → Lúcido → *Sin restricciones*, para que el sistema no la apague.
- **Actualizaciones**: la app te avisa cuando hay una versión nueva, y se instala encima sin perder nada.

## Preguntas frecuentes

**¿Es una app oficial de Instagram o YouTube?**
No. Lúcido no tiene relación con Instagram, Meta, YouTube ni Google. Abre las versiones web de Instagram y YouTube, como un navegador.

**¿Es seguro iniciar sesión?**
Iniciás sesión en las páginas de Instagram y YouTube, igual que en un navegador, y la sesión queda guardada en tu teléfono. Lúcido no tiene servidores ni cuentas propias, y el código está acá para que cualquiera pueda revisarlo.

**¿Por qué pide permisos?**
Para sacar los Reels y los Shorts no hace falta ninguno. Cada función pide el suyo recién cuando la activás (el modo dormir, el foco y el compromiso piden Accesibilidad; los mensajes, acceso a notificaciones; grabar un audio, el micrófono) y para qué es cada uno está en [Privacidad](#privacidad).

**¿Puedo desinstalarla?**
Sí. Durante un compromiso o el modo dormir cuesta más a propósito: Lúcido bloquea las pantallas de desinstalar y detener la app. Siempre se puede arrancar el teléfono en modo seguro y desinstalarla desde ahí.

**¿Hay versión para iPhone?**
No hay app. Se puede usar el bloqueo de Reels y Shorts en Safari con la app gratuita *Userscripts*, cargando [`block.user.js`](app/src/main/assets/block.user.js).

## Privacidad

Todo corre en tu teléfono. No hay servidor, cuentas, anuncios ni analíticas, y Lúcido no manda tus datos a ningún lado. Solo se conecta a Instagram y YouTube (para mostrarlos) y a este repositorio (para actualizar las reglas de bloqueo y avisar de versiones nuevas).

Para qué es cada permiso (ninguno se pide hasta que activás la función que lo usa):

- **Acceso a notificaciones** (*Mensajes en Lúcido*): para copiar las notificaciones de Instagram y YouTube y que se abran en Lúcido. Las de otras apps no se tocan.
- **Accesibilidad** (*modo dormir, foco, compromiso o bloquear las apps oficiales*): para saber qué app se abrió, redirigir Instagram y YouTube oficiales, contar ese tiempo en tu límite y bloquear los navegadores de noche (si lo elegiste). Durante un compromiso o el modo dormir, también mira las pantallas de Ajustes y del instalador para que no puedas desinstalar o detener Lúcido en un impulso. No lee lo que escribís ni el contenido de Instagram o YouTube.
- **Acceso a datos de uso** (*Estadísticas*): para leer cuánto usabas Instagram y YouTube oficiales antes de instalar Lúcido, para el "antes vs ahora".
- **Micrófono** (*audios en los chats*): solo cuando grabás un audio en Instagram. Android lo pregunta la primera vez.
- **Notificaciones** (*modo dormir, compromiso, mensajes y resumen semanal*): para avisarte antes del modo dormir, cuando llegás al límite y el resumen de los domingos.

Las reglas de bloqueo se descargan de este repositorio por HTTPS para seguir funcionando cuando Instagram o YouTube cambian algo. Si preferís que no, apagá *Actualizar reglas de bloqueo* en Ajustes.
