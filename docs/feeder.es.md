[English](feeder.md) · Español · [Français](feeder.fr.md) · [Deutsch](feeder.de.md)

# TV feeder: las filas de ranking que las apps no pueden darte

El lanzador de fábrica y Projectivy solo pueden mostrar contenido que una app
publique en el **TV Provider**. Netflix publica *seguir viendo* y un canal de vista
previa curado; Prime Video publica *seguir viendo*. Ninguno de los dos publica su
ranking nacional, porque `TvContract` no tiene ninguna tabla para eso.

Este feeder cubre ese hueco: una app mínima instalada a mano descarga el ranking de
las fuentes oficiales **ella misma**, guarda el documento en su propio directorio
privado de archivos y publica sus propios canales. No hace falta tener ningún PC
encendido. `tools/fetch_top.py` sigue siendo la implementación de referencia y
todavía funciona como fuente externa opcional (ver *Modo heredado: un top.json
externo*).

## Qué obtienes

| Fila | Productor | Fuente |
|---|---|---|
| Seguir viendo | Netflix / Prime Video, ellos mismos | sus propios programas de Watch Next, sin tocar por este proyecto |
| Netflix · Top series España | este feeder | la página Top 10 de Netflix Tudum para España (`SERIES`), país ES |
| Netflix · Top películas España | este feeder | la página Top 10 de España del mismo sitio (`MOVIES`) |
| Prime Video · Top series / películas España | este feeder | títulos populares de JustWatch, con Prime Video incluido, país ES |

Prime Video no publica ningún ranking propio por país, así que las filas de Prime
usan JustWatch: una consulta GraphQL `popularTitles` limitada a `country: ES` y a
los paquetes que están **incluidos** en la suscripción (`prv`, y `pva` para el plan
con anuncios). Los alquileres se excluyen filtrando por `prv`: en JustWatch `amz` es
la tienda y `amp` todo el grupo Amazon, y los dos cuelan alquileres en la lista. La
app ejecuta exactamente esa consulta; el script de Python solo la replica.

No hacen falta credenciales, y cada elemento lleva el enlace profundo que JustWatch
ya conoce: `standardWebURL` de la oferta incluida, que es
`https://app.primevideo.com/detail?gti=<gti>`. Eso es lo que abre el título en la
app de Prime Video, así que las filas no necesitan ninguna tabla de ids manual.
JustWatch no expone ninguna imagen de fondo, así que `thumb` reutiliza el póster: la
app cuenta un thumb ausente como carátula degradada y reportaría esas filas como
tales.

La ruta de JustWatch está cubierta por comprobaciones sin conexión contra respuestas
**capturadas** (recortadas a tres títulos y a las ofertas de Prime), más una
aserción de que un endpoint que falla produce un aviso en lugar de filas. En la caja,
una fuente que falla aborta toda la actualización, que es la opción más estricta de
las dos.

```bash
python3 tools/fetch_top.py --out top.json      # same rows, built on a PC
```

## Inicio rápido

La caja no necesita PC ni URL:

1. `adb install -r app/build/tvfeeder.apk`
2. `adb shell am start -n com.alberto.tvfeeder/.MainActivity`
3. Deja el campo de URL **vacío**, pulsa **Sync** y acepta el diálogo de
   `READ_TV_LISTINGS`.

La app descarga las dos páginas Top 10 de Netflix España y las dos consultas de
JustWatch por HTTPS, construye el documento, sustituye la copia en caché y publica
las filas. La ejecución de arranque y la tarea diaria lo repiten sin tener la
pantalla abierta. Una instalación sin red alguna sigue recuperando la copia en caché
(ver *Modo autónomo*).

Esas dos páginas se leen **en lugar de** el `all-weeks-countries.tsv` semanal de
Netflix, que pesa 33 MB y tiene 521.000 líneas: la caja lo rechazó por el límite de
cuerpo, y mantener ese cuerpo en el heap de un televisor una vez al día no es una
opción. Cada entidad de la página ya lleva ranking, semana, título, carátula e id de
vídeo, así que no hay que unir nada entre descargas (S4).

### Instalar sin ADB, desde el propio televisor

1. `Settings > Device Preferences > Security & restrictions > Unknown sources` y
   permítelo para la app con la que vayas a descargar.
2. Copia `app/build/tvfeeder.apk` a un lápiz USB, o sírvelo desde cualquier máquina
   que ya esté en la LAN, e instálalo desde ahí.
3. Abre **TV Feeder**, deja la URL vacía y pulsa **Sync**.

### Modo heredado: un top.json externo

Escribir una URL en el campo mantiene el modo anterior: la app descarga ese documento
en lugar de descargar nada por su cuenta. Es la forma de fijar un documento o de
servir uno construido sin conexión con `tools/fetch_top.py`, y ya no es el modo por
defecto: en la primera ejecución tras la actualización la URL de LAN guardada se
descarta una vez, de modo que una caja actualizada arranca en modo autónomo por sí
sola.

## Modo autónomo

| Pregunta | Respuesta |
|---|---|
| ¿Dónde vive el documento? | en `files/last_good.json`, dentro del directorio privado de datos de la app, inalcanzable para otras apps y para `adb` sin root |
| ¿Cuándo se sustituye? | solo cuando el documento recién construido se ha parseado; se escribe en `last_good.json.tmp`, se sincroniza con fsync y se renombra, así un fallo nunca deja medio archivo |
| ¿Y si falla una fuente? | falla toda la actualización. Cuatro filas o ninguna: el informe nombra la fuente que falla y las filas publicadas se quedan como están |
| ¿Y si la caja está sin conexión? | no se publica nada y las filas que ves se mantienen. La caché solo se republica en un **arranque en frío** (nada publicado desde que se crearon los datos de la app), así una ejecución diaria sin red nunca hace cambiar de estado al proveedor |
| ¿De qué se construye una tarjeta de Netflix? | de una entidad de la página: ranking, semana, título, póster e id de vídeo vienen del mismo payload, así una página que se actualiza tarde no puede mezclarlos. El conjunto de datos mundial de 33 MB nunca se descarga |
| ¿Cómo se elige la tabla? | el botón **Destination** de la actividad alterna entre canales de recomendaciones y Watch Next, y sincroniza de inmediato; sustituyó a cambiar la URL |

Los títulos de las series salen del `top10Video.parentShow.title` de la entidad
("Below", no "Below: Limited Series"); las películas no tienen serie padre, así que
se usa su propio `top10Video.title`. Netflix no siempre nombra igual un título en las
dos fuentes: para la semana de los fixtures el conjunto de datos llama a la película
del puesto 10 "Ice Age: Collision Course" y la página llama al mismo vídeo "Ice Age 5:
Collision Course". En la caja gana la página, porque es el único nombre que la caja
puede leer; `tests/test_autonomous_feeder.py` registra esa única diferencia permitida
y falla si cualquier otro título se desvía.

Una fuente que falla se nombra en el informe, por ejemplo:

```
FAILED refresh of on-box sources (Netflix Tudum + JustWatch ES): HTTP 503 from https://apis.justwatch.com/graphql
Last good copy cached at 2026-11-11 18:20 (held back, not republished).
Nothing published, previous rows left untouched (S5).
```

## Por qué las tarjetas del top no abrían sus apps

Un lanzador lanza `COLUMN_INTENT_URI`; el código anterior publicaba
`intent://www.netflix.com/watch/81652678#Intent;scheme=https;package=com.netflix.ninja;end`,
que solo resuelve si esa app registra un filtro para ese esquema, ese host **y** esa
ruta. Los elementos sin enlace recibían `intent:#Intent;package=…;end`: sin acción,
sin categoría y sin componente, lo que normalmente no resuelve a nada. Enlaces que
parecían correctos eran tarjetas muertas.

Ahora cada tarjeta se resuelve primero en el dispositivo:

1. `PackageManager.queryIntentActivities` sobre el enlace profundo, fijado al paquete
   de la fila (`ACTION_VIEW` + la URL https).
2. Si nada lo maneja, el intent de lanzamiento de la propia app
   (`getLaunchIntentForPackage`), para que la tarjeta siga abriendo Netflix o Prime
   Video.
3. Si ninguno resuelve, la tarjeta **no se publica** y la línea del informe de la
   fila la cuenta: `2 without a resolvable link`. Una fila que se queda sin nada que
   abrir se reporta como `no card opens its app` y las filas anteriores se mantienen.

El valor publicado es `Intent.toUri(Intent.URI_INTENT_SCHEME)` del intent que
resolvió, así un lanzador recibe una acción más un componente. El título viaja como
extra: la app destino lo ignora, y evita que las diez tarjetas de una misma app se
junten en una sola.

## Contrato de datos

`tools/fetch_top.py` escribe el documento que lee la app, y la app escribe la misma
forma en su caché:

```json
{
  "schema": 1,
  "generated": "2026-10-23T20:00:00Z",
  "rows": [
    {
      "channelId": "netflix-top-series-es",
      "title": "Netflix · Top series España",
      "app": "com.netflix.ninja",
      "source": "netflix-tudum",
      "week": "2026-10-18",
      "target": "preview",
      "items": [
        {"title": "Below", "poster": "https://...", "thumb": "https://...",
         "link": "https://www.netflix.com/watch/81652678"}
      ],
      "notices": []
    }
  ]
}
```

El generador fija `target` en cada fila con `--target preview|watch_next` (por
defecto `preview`), así un documento puede alimentar cualquiera de las dos tablas. En
la caja el mismo ajuste es el botón **Destination**, que no necesita recompilar ni un
PC.

`target` selecciona la tabla del proveedor y es el único ajuste que merece la pena
entender:

- `preview` (por defecto) — un canal `TYPE_PREVIEW` más programas de vista previa. Es
  la superficie estándar de "recomendaciones".
- `watch_next` — programas de Watch Next, la misma tabla en la que escriben las apps
  oficiales. Úsalo si un lanzador ignora los canales de vista previa; el precio es
  que la fila queda junto a *Seguir viendo* en lugar de tener su propia fila.

`link` es el enlace profundo que la app intenta primero. Las filas de Netflix llevan
`https://www.netflix.com/watch/<id>`, que la app de Netflix registra. Las filas de
Prime llevan el `standardWebURL` que JustWatch ya conoce,
`https://app.primevideo.com/detail?gti=<gti>`. En cualquier caso el intent publicado
es el que resolvió el dispositivo, no una suposición.

## Verificación

Comprobaciones sin conexión, sin red y sin necesidad de televisor:

```bash
python3 tests/test_fetch_top.py          # 10/10, the Python reference logic
python3 tests/test_app.py                # 3/3, static guards on the app sources
python3 tools/fetch_top.py --offline tests/fixtures --out /tmp/top.json
```

El constructor que corre en la caja se comprueba compilando `TopSource.java` con el
JDK y ejecutando `tests/java/TopSourceCheck.java` sobre los mismos fixtures, y
comparando después su documento con el documento de referencia que produce
`tools/fetch_top.py` a partir de esos fixtures. Necesita un jar de `org.json` solo
para las pruebas; la suite nunca lo descarga:

```bash
TVFEEDER_JSON_JAR=/path/json-20240303.jar python3 tests/test_autonomous_feeder.py
```

El jar es `org.json:json:20240303` de Maven Central, usado solo para la ejecución en
el host: el código de producción toma `org.json` de la plataforma, exactamente igual
que antes. Esto demuestra el parseo del payload de las páginas, los títulos de la
serie padre, el aborto todo o nada y la forma del documento, y falla si la app vuelve
a descargar el conjunto de datos mundial (S4). **No** demuestra que Netflix o
JustWatch respondan a un cliente Android, y la resolución de tarjetas en `Feeder`
necesita la caja (ver *Límites*).

En la caja, usa el botón **Diagnostics** de la app. Los comandos `content query` de
abajo no leen nada desde un shell de PC: `TvProvider` limita cada consulta al paquete
que llama, y `adb shell` no es un paquete, así que ve cero filas.

```bash
# returns "No result found" from a PC shell, by design
adb shell content query --uri content://android.media.tv/channel \
  --projection package_name,display_name,type,searchable
```

Lo que un PC sí puede ver es la sincronización en sí y el intent que la app resolvió
para cada tarjeta:

```bash
adb logcat -d -s TvFeeder:* | tail -8
```

```
Published 4 rows, 40 items, 0 without artwork, 0 errors
- netflix-top-series-es (watch_next): 10 items, week 2026-10-18
  opens: intent:https://www.netflix.com/watch/81652678#Intent;action=android.intent.action.VIEW;component=com.netflix.ninja/.ui.MainActivity;S.title=Below;end
```

Esa línea `opens:` es lo que dispara un toque en el televisor; también puedes
lanzarla desde un shell de PC.

```bash
adb shell am start -a android.intent.action.VIEW \
  -n com.netflix.ninja/.ui.MainActivity -d https://www.netflix.com/watch/81652678
```

## Por qué la app es como es

- `WRITE_EPG_DATA` y `READ_EPG_DATA` son permisos `normal`, así que cualquier app
  puede tenerlos; `ACCESS_ALL_EPG_DATA` es `signature|privileged` y
  deliberadamente no se solicita.
- `TvProvider` lanza `SecurityException` cuando quien llama sin `ACCESS_ALL_EPG_DATA`
  pasa una `selection` no vacía a `query`, `update` o `delete`. Por eso la app pasa
  siempre `null` como selección: el proveedor ya las limita al paquete que llama.
- `Channels.COLUMN_BROWSABLE` se rechaza igual: en la Mi Box S toda inserción fallaba
  con `SecurityException: Not allowed to access Channels.COLUMN_BROWSABLE` hasta que
  la app dejó de escribir esa columna, que `TvProvider` solo permite a quien tiene
  `ACCESS_ALL_EPG_DATA`. La columna ya vale 1 por defecto, así que no se pierde nada.
  `tests/test_app.py` lo protege.
- Una `selection` no vacía también se rechaza, en `query`, `update` y `delete`:
  `SecurityException: Selection not allowed for
  content://android.media.tv/channel/<id>`. Los canales sobreviven a la primera
  sincronización (ruta de inserción) y fallan en la segunda (ruta de actualización)
  salvo que se quite la selección. `tests/test_app.py` lo protege.
- Cambiar de `target` no es gratis: `clearOwnPrograms()` vacía ambas tablas, así que
  tras una sincronización **Destination → Watch Next** los **canales** de vista
  previa **siguen pero pierden todos sus elementos** y un lanzador los muestra como
  filas vacías. Publica el mismo target que muestra el lanzador y pulsa **Sync**
  después de cambiar.
- Cada tarjeta lleva un intent que el dispositivo ya resolvió (S3a): el enlace
  profundo si alguna actividad lo maneja, el intent de lanzamiento de la app si no, y
  nada en absoluto si ninguno resuelve. El título viaja como extra `S.title` para que
  las diez tarjetas de una app mantengan URIs distintas y un lanzador no las fusione.
- La descarga autónoma corre en el hilo del job con timeouts de conexión y lectura de
  20 s por petición, cinco peticiones, todo o nada. Nunca corre en el hilo de UI.
- Leer las filas de *otro* paquete exige `android.permission.READ_TV_LISTINGS`
  (`dangerous`, concedido en tiempo de ejecución) **y** que la fila tenga
  `searchable=1`, y por eso el feeder marca así sus propios canales y programas.
- Borrar y volver a publicar ocurre en una sola pasada al principio de cada
  sincronización, y solo cuando al menos una fila es válida, así una descarga fallida
  nunca borra las filas que ya ves.

## Límites

- La sincronización en la caja funciona. El 2026-11-11 publicó 4 filas y 40
  elementos, 0 sin carátula y 0 errores, para la semana de Netflix 2026-11-08,
  construidos con las dos páginas de España y las dos consultas a JustWatch. El
  conjunto de datos mundial de 33 MB no se descarga nunca, y el cuerpo de una página
  son ~200 KB frente al límite de 1 MiB (S4).
- Verificado en una Mi Box S (MIBOX4, Android TV 9) **antes** de este cambio:
  instalación, la concesión de `READ_TV_LISTINGS`, la publicación de 4 canales y 40
  elementos de Watch Next, y la programación de arranque y de la tarea en ejecuciones
  posteriores. El orden de foco y la pantalla de informe se probaron con un D-pad.
- La resolución de intents de tarjeta (S3a) está demostrada en el dispositivo: una
  sonda `app_process` parseó las cadenas publicadas con `Intent.parseUri` en flags 0
  y `URI_INTENT_SCHEME` y las resolvió a `com.netflix.ninja/.MainActivity` y
  `com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity`, mientras
  que el intent de paquete sin acción anterior no resolvía a nada. Queda abierto un
  toque humano en el televisor, y un lanzador que oculta los canales de vista previa
  no muestra nada hasta que su propio interruptor de canales está activado
  (Projectivy, ver abajo).
- www.netflix.com quedó inalcanzable desde esta conexión durante unos diez minutos
  en las pruebas: sus direcciones de borde daban timeout en un connect TCP directo
  mientras `apis.justwatch.com` respondía 200. La app reportó el fallo y no tocó las
  filas publicadas, que es el comportamiento especificado (L15).
- El generador de Python necesita `curl` porque netflix.com corta las peticiones
  HTTP/1.1 de clientes programáticos. La caja usa `HttpURLConnection`, así que si
  Netflix las corta igual, el informe muestra `FAILED refresh of on-box sources …: …`
  y las filas anteriores se mantienen. El modo top.json externo es entonces el
  recurso de reserva hasta que el transporte funcione.
- `targetSdkVersion 28` bloquea HTTP en claro por defecto, así que el manifiesto
  tiene que poner `android:usesCleartextTraffic="true"` para el modo externo; sin eso
  toda sincronización falla con `IOException: Cleartext HTTP traffic to ... not
  permitted`. `tests/test_app.py` lo protege.
- La resolución de tarjetas llama a `PackageManager.queryIntentActivities`, que
  necesita un elemento `<queries>` en cuanto `targetSdkVersion` llegue a 30. En 28
  todos los paquetes son visibles, y por eso hoy el manifiesto no necesita cambios.
- La carátula de Netflix viene de su CDN; si esas URL cambian, las filas se publican
  sin carátula y el informe las cuenta.
- Un lanzador que ignore los canales `TYPE_PREVIEW` no mostrará las filas en
  absoluto. Projectivy es uno de ellos hasta que su propio interruptor **Categorías /
  Canales → Habilitar canales** está activado; con `target` en `watch_next` las filas
  caen en su fila *Continuar viendo*, junto a lo que publican las apps oficiales.
- Los títulos de las tarjetas son los títulos de página de Netflix. Normalmente
  coinciden palabra por palabra con el conjunto de datos semanal, pero no siempre
  (ver *Modo autónomo*), así que un título en el televisor puede diferir del de la
  vista de conjunto de datos de `netflix.com/tudum/top10`.
