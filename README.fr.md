[English](README.md) · [Español](README.es.md) · Français · [Deutsch](README.de.md)

# TV Feeder : les tops nationaux sur l'écran d'accueil du téléviseur

## Le problème, et ce que cette application y apporte

Ni Netflix ni Prime Video ne publient de Top 10 par pays dans le TV Provider
Android TV : ils publient *continue watching* et une chaîne d'aperçu
éditorialisée. `TvContract` n'a aucune table pour un classement, donc le lanceur
d'origine et Projectivy ne peuvent afficher que ce que les applications choisissent
de publier, et une rangée « Top series España » ne peut pas exister sans une
application qui la publie. Cette application prend en charge ces deux rangées
classées, les publie dans le TV Provider comme ses propres chaînes interrogeables,
et laisse intactes *continue watching* ainsi que les rangées de tous les autres
paquets.

TV Feeder est une petite application Android TV à installer manuellement. Elle
construit une rangée **Top series España** et une rangée **Top películas España**
à partir des sources officielles de Netflix et de JustWatch, les publie comme ses
propres chaînes du TV Provider et ouvre l'application correspondante lorsqu'une
carte est sélectionnée. Elle fonctionne sur le boîtier lui-même : sans PC, sans
serveur, sans clé d'API et sans compte.

## Ce qu'elle publie

| Rangée | Producteur de la rangée | Source |
|---|---|---|
| Netflix · Top series España | cette application | la page Top 10 Espagne de Netflix Tudum (`SERIES`) |
| Netflix · Top películas España | cette application | la page Top 10 Espagne du même site (`MOVIES`) |
| Prime Video · Top series / películas España | cette application | les titres populaires de JustWatch, `country: ES`, offres comprises dans l'abonnement |

*Seguir viendo* n'est pas touchée : Netflix et Prime Video publient eux-mêmes
cette rangée. Le document est construit à partir des deux pages espagnoles et des
deux requêtes JustWatch, jamais à partir du jeu de données hebdomadaire mondial de
Netflix de 33 Mo, qu'aucun boîtier TV ne devrait avoir à télécharger.

Chaque carte est résolue sur l'appareil avant d'être publiée : une sélection ouvre
donc Netflix ou Prime Video au lieu d'un lien mort. Une carte dont le lien ne se
résout pas n'est pas publiée, et le rapport de synchronisation la compte.

Les cartes Prime Video utilisent une image fixe en paysage de JustWatch (480 px)
comme image de carte et l'affiche en portrait (592 px) comme affiche, car une
affiche 2:3 était étirée dans la carte 16:9 ; un titre sans image de fond utilise
son affiche. Les cartes Netflix ne changent pas. Amazon ne publie aucun Top 10 Prime
Video ordonné et par pays qui puisse être lu de façon anonyme, donc JustWatch reste
la source Prime : [feeder.fr.md](docs/feeder.fr.md) détaille ce qui a été vérifié.

## Prérequis

| Élément | Détail |
|---|---|
| Appareil | un boîtier Android TV ou Google TV, par exemple un Mi Box S, sous **Android 9 (API 28) ou plus récent** |
| Applications | **Netflix** et **Prime Video** installées, pour que les cartes aient une application à ouvrir |
| Autorisation | `READ_TV_LISTINGS`, accordée à l'exécution dans la boîte de dialogue de la première synchronisation |
| Réseau | accès HTTPS à `www.netflix.com` et `apis.justwatch.com` |

## Compiler et installer manuellement

```bash
bash tools/build-apk.sh                                  # app/build/toptv-feeder.apk
adb install -r app/build/toptv-feeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Laissez ensuite le champ URL **vide**, appuyez sur **Sync** et acceptez la boîte
de dialogue des listes TV. Les rangées apparaissent dans le lanceur qui lit le TV
Provider ; dans Projectivy, il faut d'abord activer ses propres chaînes.

Sans ADB, copiez `app/build/toptv-feeder.apk` sur une clé USB, autorisez les sources
inconnues pour le gestionnaire de fichiers du téléviseur et installez-la depuis
cette clé.

Le document est mis en cache dans `files/last_good.json`, dans le répertoire de
données privé de l'application : une actualisation qui échoue laisse donc en place
les rangées que vous voyez déjà.

Installation pas à pas sur un appareil réel, avec captures : [install.fr.md](docs/install.fr.md).

## Quand la synchronisation a lieu

| Déclencheur | Moment |
|---|---|
| démarrage | au démarrage du boîtier, dès que le réseau est disponible |
| quotidien | toutes les 24 heures, sur n'importe quel réseau |
| manuel | lorsque vous appuyez sur **Sync** dans l'activité |

Une actualisation est tout ou rien : si l'une des quatre sources échoue, le rapport
la nomme et les rangées publiées restent intactes au lieu d'être remplacées par des
données partielles.

## Destination

Le bouton **Destination** de l'activité change la table vers laquelle les rangées
sont publiées et synchronise immédiatement :

- **Destination: recommendation channels** (par défaut) publie des chaînes d'aperçu
  et des programmes d'aperçu, la surface de recommandations habituelle.
- **Destination: Watch Next** publie dans Watch Next, à côté de *Seguir viendo*,
  pour les lanceurs qui ignorent les chaînes d'aperçu.

## Langue

L'écran suit la langue du téléviseur, et le bouton **Language** de l'activité la
fait tourner : celle du téléviseur → English → Español (`Idioma` une fois l'écran en
espagnol). Le choix est enregistré et survit aux redémarrages, et le rapport de
synchronisation est écrit dans la langue de l'écran au moment où la synchronisation
s'exécute. Seuls l'anglais et l'espagnol sont livrés, donc un téléviseur dans toute
autre langue reçoit l'anglais.

## Vérification

Les contrôles sont hors ligne : uniquement des fixtures, sans réseau et sans
téléviseur.

```bash
python3 tests/test_docs.py          # four languages present, interlinked and in sync
```

## Documentation et licence

[feeder.md](docs/feeder.md) est le guide complet : sources, format du document, mode
autonome, résolution des cartes, diagnostic sur le boîtier et limites connues. Le
projet est sous [licence MIT](LICENSE).
