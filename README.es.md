[English](README.md) · Español · [Français](README.fr.md) · [Deutsch](README.de.md)

# TV Feeder: los top nacionales en la pantalla de inicio del televisor

## El problema y lo que resuelve esta app

Ni Netflix ni Prime Video publican un Top 10 por país en el TV Provider de Android
TV: publican *seguir viendo* y un canal de vista previa curado. `TvContract` no
tiene ninguna tabla para un ranking, así que el lanzador de fábrica y Projectivy
solo pueden mostrar lo que las apps deciden publicar, y una fila "Top series
España" no puede existir sin una app que la publique. Esta app es dueña de esas dos
filas de ranking, las publica en el TV Provider como canales propios y
consultables, y deja intactos *seguir viendo* y las filas de cualquier otro
paquete.

TV Feeder es una pequeña aplicación de Android TV que se instala manualmente.
Construye una fila **Top series España** y otra **Top películas España** con las
fuentes oficiales de Netflix y JustWatch, las publica como canales propios del TV
Provider y abre la aplicación correspondiente cuando se pulsa una tarjeta. Se
ejecuta en la propia caja: sin PC, sin servidor, sin clave de API y sin cuenta.

## Qué publica

| Fila | Productor de la fila | Fuente |
|---|---|---|
| Netflix · Top series España | esta aplicación | la página Top 10 de Netflix Tudum para España (`SERIES`) |
| Netflix · Top películas España | esta aplicación | la página Top 10 de España del mismo sitio (`MOVIES`) |
| Prime Video · Top series / películas España | esta aplicación | títulos populares de JustWatch, `country: ES`, ofertas incluidas en la suscripción |

*Seguir viendo* no se toca: esa fila la publican Netflix y Prime Video por su
cuenta. El documento se construye a partir de las dos páginas de España y de las
dos consultas a JustWatch, nunca a partir del conjunto de datos semanal mundial de
Netflix de 33 MB, que ninguna caja de TV debería tener que descargar.

Cada tarjeta se resuelve en el dispositivo antes de publicarse, así que al pulsarla
se abre Netflix o Prime Video en lugar de un enlace muerto. Una tarjeta cuyo
enlace no resuelve no se publica, y el informe de sincronización la cuenta.

## Requisitos

| Elemento | Detalle |
|---|---|
| Dispositivo | una caja Android TV o Google TV, por ejemplo una Mi Box S, con **Android 9 (API 28) o posterior** |
| Aplicaciones | **Netflix** y **Prime Video** instaladas, para que las tarjetas tengan algo que abrir |
| Permiso | `READ_TV_LISTINGS`, concedido en tiempo de ejecución en el diálogo que muestra la primera sincronización |
| Red | acceso HTTPS a `www.netflix.com` y `apis.justwatch.com` |

## Compilar e instalar manualmente

```bash
bash tools/build-apk.sh                                  # app/build/tvfeeder.apk
adb install -r app/build/tvfeeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Después deja el campo de URL **vacío**, pulsa **Sync** y acepta el diálogo de
listados de TV. Las filas aparecen en el lanzador que lee el TV Provider; en
Projectivy hay que habilitar antes sus propios canales.

Sin ADB, copia `app/build/tvfeeder.apk` a un lápiz USB, permite orígenes
desconocidos para el gestor de archivos del televisor e instálala desde ahí.

El documento se guarda en caché en `files/last_good.json`, dentro del directorio
privado de datos de la aplicación, de modo que una actualización fallida deja
intactas las filas que ya ves.

## Cuándo sincroniza

| Disparador | Momento |
|---|---|
| arranque | al encender la caja, cuando la red está disponible |
| diario | cada 24 horas, con cualquier red |
| manual | al pulsar **Sync** en la actividad |

Una actualización es todo o nada: si falla una de las cuatro fuentes, el informe la
nombra y las filas publicadas se quedan como están en lugar de ser sustituidas por
datos parciales.

## Destino de las filas

El botón **Destination** de la actividad cambia la tabla de destino de las filas y
sincroniza al instante:

- **Destination: recommendation channels** (por defecto) publica canales de vista
  previa y programas de vista previa, la superficie de recomendaciones habitual.
- **Destination: Watch Next** publica en Watch Next, junto a *Seguir viendo*, para
  los lanzadores que ignoran los canales de vista previa.

## Verificación

Las comprobaciones son sin conexión: solo fixtures, no hace falta red ni
televisor.

```bash
python3 tests/test_docs.py          # four languages present, interlinked and in sync
```

## Documentación y licencia

[feeder.md](docs/feeder.md) es la guía completa: fuentes, formato del documento, modo
autónomo, resolución de tarjetas, diagnóstico en la caja y los límites conocidos.
El proyecto tiene [licencia MIT](LICENSE).
