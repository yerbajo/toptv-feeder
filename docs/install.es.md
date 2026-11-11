[English](install.md) · Español · [Français](install.fr.md) · [Deutsch](install.de.md)

# Instalar TV Feeder en un dispositivo Android TV

Esta guía instala la app en un dispositivo real: un televisor con Android TV o
Google TV, un TV stick o un decodificador. Hace falta un APK, una instalación y una
sincronización. El resto de la app se describe en [feeder.es.md](feeder.es.md).

## 1. Qué necesitas

| Elemento | Detalle |
|---|---|
| Dispositivo | Android TV o Google TV con **Android 9 (API 28) o posterior**: una Mi Box S, un stick tipo Chromecast o cualquier caja que tenga ADB o un gestor de archivos |
| Aplicaciones | **Netflix** y **Prime Video** instaladas en el mismo dispositivo, para que las tarjetas tengan una app que abrir |
| Red | el dispositivo en la misma red que tu PC mientras instalas. Después solo necesita HTTPS hacia `www.netflix.com` y `apis.justwatch.com` |
| PC, solo para compilar | el SDK de Android **build-tools 34.0.0** y **platforms/android-34**; `javac`, `keytool` y `zip` en el `PATH` |

Un `app/build/tvfeeder.apk` ya compilado no necesita ningún PC.

## 2. Compilar el APK

```bash
bash tools/build-apk.sh
```

El script busca el SDK en dos rutas fijas y acepta overrides cuando el tuyo está en
otro sitio:

```bash
TVFEEDER_BUILD_TOOLS=/opt/android/build-tools/34.0.0 \
TVFEEDER_ANDROID_JAR=/opt/android/platforms/android-34/android.jar \
bash tools/build-apk.sh
```

La salida es `app/build/tvfeeder.apk`. Antes de declarar el éxito, el script revisa
el archivo construido: `aapt2 dump badging` para el nombre de paquete
`com.alberto.tvfeeder`, `sdkVersion` y `targetSdkVersion` 28, los permisos
`INTERNET`, `READ_TV_LISTINGS`, `RECEIVE_BOOT_COMPLETED`, `READ_EPG_DATA` y
`WRITE_EPG_DATA`, y una `MainActivity` launchable y leanback-launchable; rechaza un
APK que pida `ACCESS_ALL_EPG_DATA`; comprueba en el manifiesto `BootReceiver` y los
filtros `BOOT_COMPLETED` y `QUICKBOOT_POWERON`; y ejecuta `apksigner verify`. Al
final imprime el `sha256` del APK.

## 3. Instalar por ADB

Activa antes ADB en el dispositivo: `Settings > Device Preferences > About`, pulsa
OK sobre **Build** siete veces, entra en `Developer options` y activa **USB
debugging** (o **Network debugging**), y acepta el aviso RSA en el televisor.

```bash
adb connect <BOX_IP>:5555
adb install -r app/build/tvfeeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Si el puerto sigue cerrado, la caja no tiene depuración de red y necesita antes una
conexión USB: conéctala al PC, acepta el aviso, ejecuta `adb tcpip 5555` y
desconéctala.

## 4. Instalar sin ADB

Copia `app/build/tvfeeder.apk` a un lápiz USB y conéctalo al televisor, o sirve el
archivo desde el PC:

```bash
cd app/build && python3 -m http.server 8000
```

En el televisor, abre `http://<PC_IP>:8000/tvfeeder.apk` con el gestor de archivos o
con la app **Downloader**, permite orígenes desconocidos para esa app
(`Settings > Security & restrictions > Unknown sources`) e instala.

## 5. Primera ejecución

Abre **TV Feeder**, deja el campo de URL vacío, pulsa **Sync** y acepta el diálogo
de `READ_TV_LISTINGS`. El informe dice:

```
OK 2026-11-11 20:25 · Published 4 rows, 40 items, 0 without artwork, 0 errors
Source: on-box sources (Netflix Tudum + JustWatch ES)
```

![La actividad después de una sincronización](img/app-sync.jpg)

Los números significan:

| Valor | Significado |
|---|---|
| `4 rows` | se publicaron las dos filas de Netflix y las dos de Prime Video |
| `40 items` | diez tarjetas por fila; una tarjeta cuyo enlace no resolvía a nada se descarta antes de publicar, así que puede ser menor |
| `0 without artwork` | tarjetas cuyo póster o miniatura llegó vacío; se publican igual, y el número avisa de que la carátula está degradada |
| `0 errors` | filas que fallaron por completo; cualquier valor distinto de cero nombra la fila debajo del resumen |

Debajo del resumen, cada línea de fila lleva su semana y la URI `opens:` de su
primera tarjeta: esa es la cadena que lanza el lanzador.

## 6. Ver las filas

Las tarjetas aparecen en orden de ranking: la primera tarjeta es el puesto 1. La
app publica las tarjetas de atrás hacia delante, así que el puesto 1 es la última
que se inserta y lleva el mayor peso de vista previa para los lanzadores que
respetan `COLUMN_WEIGHT`; en la Mi Box, la fila Netflix · Top series España mostró
el puesto 1 (*A Couple of Lies*) hasta el puesto 7 en orden, y la fila Prime · Top
películas España abrió con su primer título publicado (*Proyecto Salvación*). El
lanzador sigue decidiendo cuántas de las diez tarjetas caben en pantalla, y un
lanzador que no respete ni el orden de inserción ni el peso puede reordenarlas.

En **Projectivy**, activa antes sus propios canales:
`Categorías / Canales → Habilitar canales`. Las filas del feeder aparecen entonces
junto a la fila de apps y a *Continuar viendo*:

![Inicio de Projectivy](img/launcher-home.jpg)

Los lanzadores que leen canales de vista previa muestran las filas sin más ajustes.
Si tu lanzador ignora los canales de vista previa, pulsa **Destination** hasta que
diga **Destination: Watch Next**, que publica las mismas filas en Watch Next; pulsa
**Sync** otra vez después, porque el cambio vacía la otra tabla.

![Las cuatro filas en Projectivy](img/launcher-rows.jpg)

## 7. El día a día

La app sincroniza al arrancar, cada 24 horas y cuando pulsas **Sync**. Una
actualización que falla mantiene las filas ya publicadas y el informe lo dice: no se
borran las filas anteriores y el último documento bueno sigue en
`files/last_good.json`.

## 8. Problemas y soluciones

| Síntoma | Causa | Qué hacer |
|---|---|---|
| No aparece ninguna fila | el lanzador oculta los canales, o **Destination** apunta a la otra tabla | activa los canales propios del lanzador (Projectivy: `Categorías / Canales → Habilitar canales`), o cambia **Destination** y pulsa **Sync** |
| El informe está OK pero las tarjetas no hacen nada | falta la app destino, o la tarjeta se descartó porque su enlace no resolvió en el dispositivo | instala Netflix o Prime Video; el informe cuenta las tarjetas descartadas como `N without a resolvable link` |
| `FAILED refresh of on-box sources …` | no hay ruta HTTPS a `www.netflix.com` o a `apis.justwatch.com`; las caídas puntuales del edge ocurren | no hay nada que reparar en la app: las filas anteriores siguen publicadas. Reinténtalo más tarde con **Sync** |
| **Diagnostics** muestra 0 items por canal | los canales existen pero los programas no se publicaron, o la tabla en pantalla es la del otro **Destination** | ejecuta **Sync** con el **Destination** que muestra el lanzador y vuelve a leer **Diagnostics** |
| `SecurityException` o un error de cleartext en el informe | no se concedió `READ_TV_LISTINGS`, o la URL externa de top.json es `http` en claro | concede el diálogo en la siguiente sincronización; para la URL externa opcional usa `https` cuando sea posible |

El botón **Diagnostics** imprime cada fila del proveedor que puede leer, con su
`package_name`, su tipo y su indicador `searchable`, los items por canal y el total
de items de Watch Next, que es como se distingue una fila vacía de una que falta.

## 9. Desinstalar

```bash
adb uninstall com.alberto.tvfeeder
```

Desinstalar elimina la app, sus canales y el documento en caché. Las filas
desaparecen del lanzador en su siguiente actualización.
