[English](feeder.md) · [Español](feeder.es.md) · Français · [Deutsch](feeder.de.md)

# TV feeder : les rangées classées que les applications ne peuvent pas fournir

Le lanceur d'origine et Projectivy ne peuvent afficher que le contenu qu'une
application publie dans le **TV Provider**. Netflix publie *continue watching* et
une chaîne d'aperçu éditorialisée ; Prime Video publie *continue watching*. Aucun
des deux ne publie son classement national, car `TvContract` n'a aucune table pour
cela.

Ce feeder comble ce manque : une petite application installée manuellement récupère
le classement auprès des sources officielles **elle-même**, conserve le document
dans son propre répertoire de fichiers privé et publie ses propres chaînes. Aucun
PC ne doit rester allumé. `tools/fetch_top.py` reste l'implémentation de référence
et fonctionne toujours comme source externe facultative (voir *Héritage : un
top.json externe*).

## Ce que vous obtenez

| Rangée | Producteur | Source |
|---|---|---|
| Seguir viendo | Netflix / Prime Video eux-mêmes | leurs propres programmes Watch Next, non modifiés par ce projet |
| Netflix · Top series España | ce feeder | la page Top 10 Espagne de Netflix Tudum (`SERIES`), pays ES |
| Netflix · Top películas España | ce feeder | la page Top 10 Espagne du même site (`MOVIES`) |
| Prime Video · Top series / películas España | ce feeder | les titres populaires de JustWatch, Prime Video compris, pays ES |

Prime Video ne publie aucun classement propre à un pays, donc les rangées Prime
utilisent JustWatch : une requête GraphQL `popularTitles` limitée à `country: ES`
et aux offres **comprises** dans l'abonnement (`prv`, et `pva` pour l'offre avec
publicité). Les locations sont exclues en filtrant sur `prv` : chez JustWatch `amz`
est la boutique et `amp` tout le groupe Amazon, et les deux laissent passer des
locations dans la liste. L'application exécute exactement cette requête ; le script
Python ne fait que la refléter.

Aucun identifiant n'est nécessaire, et chaque élément porte le lien profond que
JustWatch connaît déjà : `standardWebURL` de l'offre incluse, soit
`https://app.primevideo.com/detail?gti=<gti>`. C'est ce lien qui ouvre le titre
dans l'application Prime Video, donc les rangées n'ont besoin d'aucune table d'ids
manuelle. JustWatch n'expose aucune image de fond, donc `thumb` réutilise l'affiche
: l'application compte une vignette absente comme une illustration dégradée et
signalerait ces rangées comme telles.

Le chemin JustWatch est couvert par des contrôles hors ligne sur des réponses
**capturées** (réduites à trois titres et aux offres Prime), plus une assertion
selon laquelle un endpoint en échec produit un avis au lieu de rangées. Sur le
boîtier, une source en échec interrompt toute l'actualisation, ce qui est la plus
stricte des deux options.

```bash
python3 tools/fetch_top.py --out top.json      # same rows, built on a PC
```

## Démarrage rapide

Le boîtier n'a besoin ni de PC ni d'URL :

1. `adb install -r app/build/tvfeeder.apk`
2. `adb shell am start -n com.alberto.tvfeeder/.MainActivity`
3. Laissez le champ URL **vide**, appuyez sur **Sync** et acceptez la boîte de
   dialogue `READ_TV_LISTINGS`.

L'application récupère les deux pages Top 10 Espagne de Netflix et les deux
requêtes JustWatch en HTTPS, construit le document, remplace la copie en cache et
publie les rangées. L'exécution au démarrage et la tâche quotidienne la répètent
sans écran ouvert. Une installation sans aucun réseau récupère encore la copie en
cache (voir *Mode autonome*).

Ces deux pages sont lues **au lieu de** l'`all-weeks-countries.tsv` hebdomadaire de
Netflix, qui pèse 33 Mo et compte 521 000 lignes : le boîtier l'a rejeté à cause du
plafond de taille, et garder ce corps dans le tas mémoire d'un téléviseur une fois
par jour n'est pas envisageable. Chaque entité de page porte déjà le rang, la
semaine, le titre, l'affiche et l'id de vidéo, donc rien n'a besoin d'être joint
entre plusieurs téléchargements (S4).

### Installer sans ADB, depuis le téléviseur lui-même

1. `Settings > Device Preferences > Security & restrictions > Unknown sources` et
   autorisez-le pour l'application avec laquelle vous allez télécharger.
2. Copiez `app/build/tvfeeder.apk` sur une clé USB, ou servez-le depuis n'importe
   quelle machine déjà présente sur le réseau local, et installez-le depuis là.
3. Ouvrez **TV Feeder**, laissez l'URL vide, appuyez sur **Sync**.

### Héritage : un top.json externe

Saisir une URL dans le champ conserve le mode précédent : l'application télécharge
ce document au lieu de récupérer quoi que ce soit. C'est la manière de figer un
document ou d'en servir un construit hors ligne avec `tools/fetch_top.py`, et ce
n'est plus le mode par défaut : à la première exécution après la mise à jour, l'URL
LAN enregistrée est abandonnée une fois, de sorte qu'un boîtier mis à jour démarre
seul en mode autonome.

## Mode autonome

| Question | Réponse |
|---|---|
| Où vit le document ? | dans `files/last_good.json`, dans le répertoire de données privé de l'application, inaccessible aux autres applications et à `adb` sans root |
| Quand est-il remplacé ? | seulement une fois le document fraîchement construit analysé ; il est écrit dans `last_good.json.tmp`, synchronisé par fsync puis renommé, de sorte qu'un incident ne laisse jamais un fichier à moitié écrit |
| Et si une source échoue ? | toute l'actualisation échoue. Quatre rangées ou aucune : le rapport nomme la source en échec et les rangées publiées restent telles quelles |
| Et si le boîtier est hors ligne ? | rien n'est publié et les rangées que vous voyez restent. Le cache n'est republié qu'à un **démarrage à froid** (rien de publié depuis la création des données de l'application), donc une exécution quotidienne hors ligne ne fait jamais varier le fournisseur |
| Avec quoi construit-on une carte Netflix ? | avec une entité de page : rang, semaine, titre, affiche et id de vidéo viennent tous de la même charge utile, donc une page mise à jour en retard ne peut pas les désaccorder. Le jeu de données mondial de 33 Mo n'est jamais téléchargé |
| Comment la table est-elle choisie ? | le bouton **Destination** de l'activité bascule entre les chaînes de recommandations et Watch Next, et synchronise immédiatement ; il a remplacé le changement d'URL |

Les titres de séries viennent du `top10Video.parentShow.title` de l'entité
(« Below », et non « Below: Limited Series ») ; les films n'ont pas de série
parente, donc c'est leur propre `top10Video.title` qui est utilisé. Netflix ne
nomme pas toujours un titre de la même façon dans les deux sources : pour la
semaine des fixtures, le jeu de données appelle le film classé 10e « Ice Age:
Collision Course » et la page appelle la même vidéo « Ice Age 5: Collision Course
». Sur le boîtier, la page gagne, parce que c'est le seul nom que le boîtier peut
lire ; `tests/test_autonomous_feeder.py` enregistre cette unique différence
autorisée et échoue si un autre titre diverge.

Une source en échec est nommée dans le rapport, par exemple :

```
FAILED refresh of on-box sources (Netflix Tudum + JustWatch ES): HTTP 503 from https://apis.justwatch.com/graphql
Last good copy cached at 2026-11-11 18:20 (held back, not republished).
Nothing published, previous rows left untouched (S5).
```

## Pourquoi les cartes du top n'ouvraient pas leur application

Un lanceur déclenche `COLUMN_INTENT_URI` ; l'ancien code publiait
`intent://www.netflix.com/watch/81652678#Intent;scheme=https;package=com.netflix.ninja;end`,
qui ne se résout que si cette application enregistre un filtre pour ce schéma, cet
hôte **et** ce chemin. Les éléments sans lien recevaient
`intent:#Intent;package=…;end` : sans action, sans catégorie et sans composant, ce
qui ne se résout normalement à rien. Des liens d'apparence correcte étaient des
cartes mortes.

Désormais, chaque carte est d'abord résolue sur l'appareil :

1. `PackageManager.queryIntentActivities` sur le lien profond, épinglé au paquet de
   la rangée (`ACTION_VIEW` + l'URL https).
2. Si rien ne le gère, l'intent de lancement de l'application
   (`getLaunchIntentForPackage`), pour que la carte ouvre quand même Netflix ou
   Prime Video.
3. Si aucun des deux ne se résout, la carte **n'est pas publiée** et la ligne de
   rapport de la rangée la compte : `2 without a resolvable link`. Une rangée à qui
   il ne reste rien à ouvrir est signalée comme `no card opens its app` et les
   rangées précédentes restent.

La valeur publiée est `Intent.toUri(Intent.URI_INTENT_SCHEME)` de l'intent qui s'est
résolu, donc un lanceur reçoit une action et un composant. Le titre voyage comme
extra : l'application cible l'ignore, et il empêche les dix cartes d'une même
application de fusionner en une seule.

## Contrat de données

`tools/fetch_top.py` écrit le document que lit l'application, et l'application
écrit la même forme dans son cache :

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

Le générateur fixe `target` sur chaque rangée avec `--target preview|watch_next`
(`preview` par défaut), donc un document peut alimenter l'une ou l'autre table. Sur
le boîtier, le même réglage est le bouton **Destination**, qui ne demande ni
recompilation ni PC.

`target` sélectionne la table du fournisseur et c'est le seul réglage qui vaut la
peine d'être compris :

- `preview` (par défaut) — une chaîne `TYPE_PREVIEW` et des programmes d'aperçu.
  C'est la surface « recommandations » standard.
- `watch_next` — des programmes Watch Next, la même table que celle où écrivent les
  applications officielles. À utiliser si un lanceur ignore les chaînes d'aperçu ;
  la contrepartie est que la rangée se place à côté de *Seguir viendo* au lieu
  d'avoir sa propre rangée.

`link` est le lien profond que l'application essaie en premier. Les rangées Netflix
portent `https://www.netflix.com/watch/<id>`, que l'application Netflix enregistre.
Les rangées Prime portent le `standardWebURL` que JustWatch connaît déjà,
`https://app.primevideo.com/detail?gti=<gti>`. Dans les deux cas, l'intent publié
est celui que l'appareil a résolu, pas une supposition.

## Vérification

Contrôles hors ligne, sans réseau et sans téléviseur :

```bash
python3 tests/test_fetch_top.py          # 10/10, the Python reference logic
python3 tests/test_app.py                # 3/3, static guards on the app sources
python3 tools/fetch_top.py --offline tests/fixtures --out /tmp/top.json
```

Le constructeur embarqué sur le boîtier est vérifié en compilant `TopSource.java`
avec le JDK et en exécutant `tests/java/TopSourceCheck.java` sur les mêmes
fixtures, puis en comparant son document avec le document de référence que produit
`tools/fetch_top.py` à partir de ces fixtures. Il a besoin d'un jar `org.json`
réservé aux tests ; la suite ne le télécharge jamais :

```bash
TVFEEDER_JSON_JAR=/path/json-20240303.jar python3 tests/test_autonomous_feeder.py
```

Le jar est `org.json:json:20240303` de Maven Central, utilisé uniquement pour
l'exécution sur l'hôte : le code de production prend `org.json` sur la plateforme,
exactement comme avant. Cela prouve l'analyse de la charge utile des pages, les
titres de la série parente, l'interruption tout ou rien et la forme du document, et
échoue si l'application se remet à télécharger le jeu de données mondial (S4). Cela
ne prouve **pas** que Netflix ou JustWatch répondent à un client Android, et la
résolution des cartes dans `Feeder` nécessite le boîtier (voir *Limites*).

Sur le boîtier, utilisez le bouton **Diagnostics** de l'application. Les commandes
`content query` ci-dessous ne lisent rien depuis un shell de PC : `TvProvider`
limite chaque requête au paquet appelant, et `adb shell` n'est pas un paquet, donc
il voit zéro rangée.

```bash
# returns "No result found" from a PC shell, by design
adb shell content query --uri content://android.media.tv/channel \
  --projection package_name,display_name,type,searchable
```

Ce qu'un PC peut encore voir, c'est la synchronisation elle-même et l'intent que
l'application a résolu pour chaque carte :

```bash
adb logcat -d -s TvFeeder:* | tail -8
```

```
Published 4 rows, 40 items, 0 without artwork, 0 errors
- netflix-top-series-es (watch_next): 10 items, week 2026-10-18
  opens: intent:https://www.netflix.com/watch/81652678#Intent;action=android.intent.action.VIEW;component=com.netflix.ninja/.ui.MainActivity;S.title=Below;end
```

Cette ligne `opens:` est ce qu'un appui sur le téléviseur déclenche ; vous pouvez
aussi la lancer depuis un shell de PC.

```bash
adb shell am start -a android.intent.action.VIEW \
  -n com.netflix.ninja/.ui.MainActivity -d https://www.netflix.com/watch/81652678
```

## Pourquoi l'application est ainsi

- `WRITE_EPG_DATA` et `READ_EPG_DATA` sont des permissions `normal`, donc n'importe
  quelle application peut les détenir ; `ACCESS_ALL_EPG_DATA` est
  `signature|privileged` et n'est délibérément pas demandée.
- `TvProvider` lève `SecurityException` lorsqu'un appelant sans
  `ACCESS_ALL_EPG_DATA` passe une `selection` non vide à `query`, `update` ou
  `delete`. L'application passe donc toujours des sélections `null` : le
  fournisseur les limite déjà au paquet appelant.
- `Channels.COLUMN_BROWSABLE` est refusé de la même façon : sur la Mi Box S, chaque
  insertion échouait avec `SecurityException: Not allowed to access
  Channels.COLUMN_BROWSABLE` jusqu'à ce que l'application cesse d'écrire cette
  colonne, que `TvProvider` n'autorise qu'aux détenteurs d'`ACCESS_ALL_EPG_DATA`.
  La colonne vaut déjà 1 par défaut, donc rien n'est perdu. `tests/test_app.py` le
  protège.
- Une `selection` non vide est également refusée, sur `query`, `update` et
  `delete` : `SecurityException: Selection not allowed for
  content://android.media.tv/channel/<id>`. Les chaînes survivent à la première
  synchronisation (chemin d'insertion) et échouent à la deuxième (chemin de mise à
  jour) si la sélection n'est pas retirée. `tests/test_app.py` le protège.
- Changer de `target` n'est pas gratuit : `clearOwnPrograms()` vide les deux tables,
  donc après une synchronisation **Destination → Watch Next**, les **chaînes**
  d'aperçu **restent mais perdent tous leurs éléments** et un lanceur les affiche
  comme des rangées vides. Publiez la même cible que celle affichée par le lanceur,
  et appuyez sur **Sync** après le changement.
- Chaque carte porte un intent que l'appareil a déjà résolu (S3a) : le lien profond
  si une activité le gère, l'intent de lancement de l'application sinon, et rien du
  tout si aucun des deux ne se résout. Le titre voyage comme extra `S.title` pour
  que les dix cartes d'une même application gardent des URI distinctes et qu'un
  lanceur ne les fusionne pas.
- La récupération autonome s'exécute sur le thread du job avec des délais de
  connexion et de lecture de 20 s par requête, cinq requêtes, tout ou rien. Elle ne
  s'exécute jamais sur le thread de l'interface.
- Lire les rangées d'une *autre* application exige
  `android.permission.READ_TV_LISTINGS` (`dangerous`, accordée à l'exécution) **et**
  que la rangée porte `searchable=1`, et c'est pourquoi le feeder marque ainsi ses
  propres chaînes et programmes.
- La suppression et la republication se font en une seule passe au début de chaque
  synchronisation, et seulement lorsqu'au moins une rangée est valide, donc un
  téléchargement en échec n'efface jamais les rangées que vous voyez déjà.

## Limites

- La synchronisation sur le boîtier fonctionne. Le 2026-11-11, elle a publié 4
  rangées et 40 éléments, 0 sans illustration et 0 erreur, pour la semaine Netflix
  2026-11-08, à partir des deux pages espagnoles et des deux requêtes JustWatch. Le
  jeu de données mondial de 33 Mo n'est jamais téléchargé, et le corps d'une page
  fait ~200 Ko face au plafond de 1 Mio (S4).
- Vérifié sur une Mi Box S (MIBOX4, Android TV 9) **avant** ce changement :
  installation, l'octroi de `READ_TV_LISTINGS`, la publication de 4 chaînes et de
  40 éléments Watch Next, et la planification au démarrage et par tâche des
  exécutions suivantes. L'ordre de focus et l'écran de rapport ont été exercés avec
  une croix directionnelle.
- La résolution des intents de carte (S3a) est prouvée sur l'appareil : une sonde
  `app_process` a analysé les chaînes publiées avec `Intent.parseUri` aux flags 0 et
  `URI_INTENT_SCHEME` et les a résolues vers `com.netflix.ninja/.MainActivity` et
  `com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity`, alors
  que l'ancien intent de paquet sans action ne se résolvait à rien. Restent ouverts
  un appui humain sur le téléviseur, et le fait qu'un lanceur qui masque les chaînes
  d'aperçu n'affiche rien tant que son propre interrupteur de chaînes n'est pas
  activé (Projectivy, voir ci-dessous).
- www.netflix.com est devenu injoignable depuis cette connexion pendant une dizaine
  de minutes lors des essais : ses adresses de bord expiraient sur une connexion TCP
  brute tandis que `apis.justwatch.com` répondait 200. L'application a signalé
  l'échec et n'a pas touché les rangées publiées, ce qui est le comportement
  spécifié (L15).
- Le générateur Python a besoin de `curl` parce que netflix.com réinitialise les
  requêtes HTTP/1.1 des clients scriptés. Le boîtier utilise `HttpURLConnection`,
  donc si Netflix le réinitialise de la même façon, le rapport affiche
  `FAILED refresh of on-box sources …: …` et les rangées précédentes restent. Le
  mode top.json externe est alors la solution de repli jusqu'à ce que le transport
  fonctionne.
- `targetSdkVersion 28` bloque le HTTP en clair par défaut, donc le manifeste doit
  définir `android:usesCleartextTraffic="true"` pour le mode externe ; sans cela,
  chaque synchronisation échoue avec `IOException: Cleartext HTTP traffic to ...
  not permitted`. `tests/test_app.py` le protège.
- La résolution des cartes appelle `PackageManager.queryIntentActivities`, qui
  exige un élément `<queries>` dès que `targetSdkVersion` atteint 30. En 28, tous
  les paquets sont visibles, et c'est pourquoi le manifeste n'a besoin d'aucun
  changement aujourd'hui.
- L'affiche Netflix vient de son CDN ; si ces URL changent, les rangées sont
  publiées sans illustration et le rapport les compte.
- Un lanceur qui ignore les chaînes `TYPE_PREVIEW` n'affichera pas ces rangées du
  tout. Projectivy en fait partie tant que son propre interrupteur **Categorías /
  Canales → Habilitar canales** n'est pas activé ; avec `target` réglé sur
  `watch_next`, les rangées arrivent dans sa rangée *Continuar viendo*, à côté de
  ce que publient les applications officielles.
- Les titres des cartes sont ceux des pages de Netflix. Ils correspondent
  généralement mot pour mot au jeu de données hebdomadaire, mais pas toujours (voir
  *Mode autonome*), donc un titre affiché sur le téléviseur peut différer de celui
  de la vue jeu de données de `netflix.com/tudum/top10`.
