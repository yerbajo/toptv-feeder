[English](install.md) · [Español](install.es.md) · Français · [Deutsch](install.de.md)

# Installer TV Feeder sur un appareil Android TV

Ce guide installe l'application sur un appareil réel : un téléviseur sous Android
TV ou Google TV, une clé TV ou un boîtier. Il faut un APK, une installation et une
synchronisation. Le reste de l'application est décrit dans
[feeder.fr.md](feeder.fr.md).

## 1. Ce qu'il vous faut

| Élément | Détail |
|---|---|
| Appareil | Android TV ou Google TV sous **Android 9 (API 28) ou plus récent** : une Mi Box S, une clé de type Chromecast ou n'importe quel boîtier disposant d'ADB ou d'un gestionnaire de fichiers |
| Applications | **Netflix** et **Prime Video** installées sur le même appareil, pour que les cartes aient une application à ouvrir |
| Réseau | l'appareil sur le même réseau que votre PC pendant l'installation. Ensuite, il n'a besoin que d'HTTPS vers `www.netflix.com` et `apis.justwatch.com` |
| PC, uniquement pour compiler | le SDK Android **build-tools 34.0.0** et **platforms/android-34** ; `javac`, `keytool` et `zip` dans le `PATH` |

Un `app/build/toptv-feeder.apk` déjà compilé ne demande aucun PC.

## 2. Compiler l'APK

```bash
bash tools/build-apk.sh
```

Le script cherche le SDK à deux emplacements fixes et accepte des overrides quand le
vôtre est ailleurs :

```bash
TVFEEDER_BUILD_TOOLS=/opt/android/build-tools/34.0.0 \
TVFEEDER_ANDROID_JAR=/opt/android/platforms/android-34/android.jar \
bash tools/build-apk.sh
```

La sortie est `app/build/toptv-feeder.apk`. Avant d'annoncer le succès, le script
contrôle le fichier produit : `aapt2 dump badging` pour le nom de paquet
`com.alberto.tvfeeder`, `sdkVersion` et `targetSdkVersion` 28, les permissions
`INTERNET`, `READ_TV_LISTINGS`, `RECEIVE_BOOT_COMPLETED`, `READ_EPG_DATA` et
`WRITE_EPG_DATA`, et une `MainActivity` launchable et leanback-launchable ; il
refuse un APK qui demande `ACCESS_ALL_EPG_DATA` ; il vérifie dans le manifeste
`BootReceiver` et les filtres `BOOT_COMPLETED` et `QUICKBOOT_POWERON` ; et il
exécute `apksigner verify`. Il affiche le `sha256` de l'APK à la fin.

## 3. Installer via ADB

Activez d'abord ADB sur l'appareil : `Settings > Device Preferences > About`,
appuyez sur OK sur **Build** sept fois, ouvrez `Developer options`, activez **USB
debugging** (ou **Network debugging**) et acceptez l'invite RSA sur le téléviseur.

```bash
adb connect <BOX_IP>:5555
adb install -r app/build/toptv-feeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Si le port reste fermé, le boîtier n'a pas le débogage réseau et a besoin d'abord
d'une connexion USB : branchez-le au PC, acceptez l'invite, lancez `adb tcpip 5555`,
puis débranchez.

## 4. Installer sans ADB

Copiez `app/build/toptv-feeder.apk` sur une clé USB et branchez-la au téléviseur, ou
servez le fichier depuis le PC :

```bash
cd app/build && python3 -m http.server 8000
```

Sur le téléviseur, ouvrez `http://<PC_IP>:8000/toptv-feeder.apk` avec le gestionnaire de
fichiers ou avec l'application **Downloader**, autorisez les sources inconnues pour
cette application (`Settings > Security & restrictions > Unknown sources`), puis
installez.

## 5. Premier lancement

Ouvrez **TV Feeder**, laissez le champ URL vide, appuyez sur **Sync** et acceptez la
boîte de dialogue `READ_TV_LISTINGS`. Le rapport affiche :

```
OK 2026-11-11 20:25 · Published 4 rows, 40 items, 0 without artwork, 0 errors
Source: on-box sources (Netflix Tudum + JustWatch ES)
```

![L'activité après une synchronisation, dans la langue du téléviseur](img/app-sync.jpg)

`JustWatch ES` est le code du pays choisi : un autre pays change ce code, les titres
des rangées et les pages que lit l'application.

Les nombres signifient :

| Valeur | Signification |
|---|---|
| `4 rows` | les deux rangées Netflix et les deux rangées Prime Video ont été publiées |
| `40 items` | dix cartes par rangée ; une carte dont le lien ne se résolvait à rien est écartée avant publication, donc le chiffre peut être plus bas |
| `0 without artwork` | cartes dont l'affiche ou la vignette est revenue vide ; elles sont quand même publiées, et le chiffre signale une illustration dégradée |
| `0 errors` | rangées qui ont échoué entièrement ; toute valeur non nulle nomme la rangée sous le résumé |

Sous le résumé, chaque ligne de rangée porte sa semaine et l'URI `opens:` de sa
première carte : c'est la chaîne que le lanceur déclenche.

### La langue de l'écran

Le bouton **Language** affiche `Language: the TV's own` et fait tourner l'écran
entre la langue du téléviseur → English → Español. Le choix est enregistré, survit
aux redémarrages, et le rapport est écrit dans la langue de l'écran, donc un écran
espagnol imprime les mêmes lignes en espagnol. Seuls l'anglais et l'espagnol sont
livrés ; un téléviseur dans toute autre langue reçoit l'anglais.

Sur un écran espagnol, ces boutons s'appellent `Sincronizar`, `Diagnóstico`,
`Destino` et `Idioma` ; les étapes ci-dessus nomment donc le bouton, pas son
libellé.

### Le pays de l'écran

Le bouton **Pays** affiche le choix courant et ouvre la liste des pays pris en
charge : Espagne, Allemagne, Pays-Bas, France, Italie, Royaume-Uni, États-Unis,
Portugal, Belgique, Autriche, Suisse, Suède, Pologne, Irlande, Mexique, Brésil et
Argentine. L'application démarre avec le pays de la locale du téléviseur lorsqu'il
figure dans cette liste, et avec l'Espagne sinon. Le choix est enregistré comme la
langue, et les titres des rangées portent le nom du pays dans la langue de l'écran :
un écran espagnol avec l'Allemagne sélectionnée affiche `Netflix · Top series
Alemania`, un écran anglais `Netflix · Top series Germany`. Changer de pays ou de
langue supprime les anciennes rangées de cette application, donc aucune rangée vide
du pays précédent ne reste.

## 6. Afficher les rangées

Les cartes apparaissent dans l'ordre du classement : la première carte est le rang
1. L'application publie les cartes classées de la dernière à la première, donc le
rang 1 est la dernière insérée et porte le poids d'aperçu le plus élevé pour les
lanceurs qui respectent `COLUMN_WEIGHT` ; sur la Mi Box, la rangée Netflix · Top
series España affichait le rang 1 (*A Couple of Lies*) jusqu'au rang 7 dans l'ordre,
et la rangée Prime · Top películas España s'ouvrait sur son premier titre publié
(*Proyecto Salvación*). Le lanceur décide toujours combien des dix cartes tiennent à
l'écran, et un lanceur qui ne respecte ni l'ordre d'insertion ni le poids peut
encore les réordonner.

Dans **Projectivy**, activez d'abord ses propres chaînes :
`Categorías / Canales → Habilitar canales`. Les rangées du feeder apparaissent alors
à côté de la rangée d'applications et de *Continuar viendo* :

![Accueil de Projectivy](img/launcher-home.jpg)

Les lanceurs qui lisent les chaînes d'aperçu affichent les rangées sans autre
réglage. Si votre lanceur ignore les chaînes d'aperçu, appuyez sur **Destination**
jusqu'à ce qu'il affiche **Destination: Watch Next**, qui publie les mêmes rangées
dans Watch Next ; appuyez de nouveau sur **Sync** ensuite, car le changement vide
l'autre table.

![Les quatre rangées dans Projectivy](img/launcher-rows.jpg)

## 7. Au quotidien

L'application synchronise au démarrage, toutes les 24 heures et lorsque vous
appuyez sur **Sync**. Une source qui échoue ou revient vide est nommée dans le
rapport et les autres rangées sont publiées quand même, donc un pays où JustWatch
est maigre peut ne publier que les rangées Netflix ; quand rien ne peut être publié,
les rangées déjà présentes dans le lanceur restent et le dernier document valide
reste dans `files/last_good-<cc>.json`.

## 8. Dépannage

| Symptôme | Cause | Que faire |
|---|---|---|
| Aucune rangée | le lanceur masque les chaînes, ou **Destination** cible l'autre table | activez les chaînes propres au lanceur (Projectivy : `Categorías / Canales → Habilitar canales`), ou changez **Destination** et appuyez sur **Sync** |
| Le rapport est OK mais les cartes ne font rien | l'application cible manque, ou la carte a été écartée parce que son lien ne s'est pas résolu sur l'appareil | installez Netflix ou Prime Video ; le rapport compte les cartes écartées comme `N without a resolvable link` |
| `FAILED refresh of on-box sources …` | pas de route HTTPS vers `www.netflix.com` ou `apis.justwatch.com` ; des pannes passagères du edge arrivent | rien à réparer dans l'application : le rapport nomme la source et les autres rangées sont publiées quand même, et quand rien n'est publié les anciennes rangées restent. Réessayez plus tard avec **Sync** |
| **Diagnostics** affiche 0 items par chaîne | les chaînes existent mais les programmes n'ont pas été publiés, ou la table affichée est celle de l'autre **Destination** | lancez **Sync** avec la **Destination** affichée par le lanceur, puis relisez **Diagnostics** |
| `SecurityException` ou une erreur de cleartext dans le rapport | `READ_TV_LISTINGS` n'a pas été accordée, ou l'URL externe top.json est en `http` clair | accordez la boîte de dialogue à la synchronisation suivante ; pour l'URL externe facultative, utilisez `https` quand c'est possible |

Le bouton **Diagnostics** affiche chaque rangée du fournisseur qu'il peut lire, avec
son `package_name`, son type et son indicateur `searchable`, les items par chaîne et
le total des items Watch Next, ce qui permet de distinguer une rangée vide d'une
rangée absente.

## 9. Désinstaller

```bash
adb uninstall com.alberto.tvfeeder
```

La désinstallation retire l'application, ses chaînes et le document en cache. Les
rangées disparaissent du lanceur à sa prochaine actualisation.
