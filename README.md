# RadioClic

**Un clic, une émission.** Application Android (testée pour Samsung Galaxy S23) qui ouvre une grille de gros boutons. Chaque bouton lance une émission ou un enchaînement d'émissions : journal de 8h, revue de presse, chronique, radio en direct…

CGExcel — Cyrille Gindre — © 2026 — Licence **MIT + BAL 1.0 — Bonne Action License**

> En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour. Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez.

Site : https://cgexcel.wordpress.com/

---

## Fonctions

- **Aucune adresse à connaître** : on tape ce qu'on veut écouter (« journal France Inter 8h », « revue de presse », « France Culture »…), on choisit dans la liste, la tuile est créée. À chaque appui, l'application retrouve elle-même **le dernier épisode publié** — même pour les émissions Radio France, qui ne publient pas de flux RSS dans l'annuaire (les épisodes sont alors lus depuis la fiche Apple Podcasts de l'émission).
- **Sans publicité** (réglable dans le paramétrage) : pour les émissions Radio France, le fichier original de l'émission est lu directement, sans la publicité que le serveur de diffusion peut insérer au début ; pour les directs Radio France, le flux HLS officiel (sans publicité au lancement) remplace le flux « icecast ».
- **Recherche ciblée** : la case « Radio en direct » choisit entre émissions enregistrées (décochée) et stations en direct (cochée).
- **Radios en direct en un appui** : France Inter, franceinfo, France Culture, France Musique, FIP, Mouv', RTL, Europe 1, RMC, RFI, Nostalgie… proposées dans l'écran **+** ; toute autre station se trouve par la recherche.
- **Grille de tuiles** plein écran (1 à 4 colonnes) : titre, couleur, image ou pochette du podcast.
- **Un appui = lecture immédiate.** Les flux RSS sont résolus en parallèle, puis lus dans l'ordre.
- **Mini-lecteur** : élément en cours, date et heure de l'épisode, position (« 2 / 3 »), lecture/pause, précédent, suivant, stop.
- **Chrono et barre de temps** pour les émissions en replay : temps écoulé / restant, curseur déplaçable, boutons reculer / avancer (15 s par défaut, réglable : 5, 10, 15, 30 ou 60 s).
- **Retour en arrière dans les directs** (15 min par défaut, réglable jusqu'à 1 h), avec bouton « Revenir au direct » :
  - Radio France : jusqu'à la limite réglée, même avant le lancement de la tuile (segments conservés par Radio France) ;
  - autres radios (flux MP3 / AAC) : le direct est enregistré au fur et à mesure sur le téléphone, on peut donc revenir sur ce qui a déjà été reçu depuis le lancement, et une pause reprend là où on s'était arrêté.
- **Tuiles déplaçables sur l'écran principal**, comme les icônes d'Android : appui long puis glisser ; un appui long sans bouger ouvre le menu de la tuile.
- **Logos des radios** retrouvés automatiquement (annuaire Radio Browser, site de la station, pochettes Apple Podcasts), même pour les tuiles déjà créées.
- **Lecture en arrière-plan** (Media3 / ExoPlayer) : écran éteint, notification, écran de verrouillage, Bluetooth (voiture, casque), pause pendant un appel puis reprise.
- **Types d'éléments**
  - *Podcast (RSS)* : dernier épisode, ou « uniquement s'il est du jour » (sinon l'élément est sauté avec un message, par ex. « Journal de 8h pas encore en ligne »).
  - *Radio en direct* : URL de flux, avec durée maximale optionnelle avant l'élément suivant.
- **Recherche unique** (bouton **+** de l'écran principal) : émissions via l'annuaire iTunes / Apple Podcasts (avec aperçu des derniers épisodes), radios via Radio Browser. La saisie manuelle d'une adresse reste possible en option « avancée ».
- **Paramétrage** (⚙ en haut à droite) : colonnes, glisser-déposer des tuiles et des éléments, ajout, duplication, suppression (avec confirmation).
- **Export / import** de la configuration en JSON (sauvegarde, changement de téléphone).
- **Raccourcis** : appui long sur une tuile > « Ajouter à l'écran d'accueil ».
- Sans compte, sans publicité, sans collecte de données.

## Utilisation rapide

1. Toucher **+** en haut de l'écran principal.
2. Taper ce que vous voulez écouter, par exemple **journal France Inter 8h**.
3. Toucher **Journal de 08h00 — France Inter** : les derniers épisodes s'affichent pour vérifier. Choisir « Seulement s'il est du jour » si besoin, puis **Créer la tuile**.
4. C'est tout : chaque appui sur la tuile lit le journal le plus récent.

Pour un enchaînement (ex. journal, puis revue de presse, puis une chronique) : ⚙ **Paramétrage** > toucher la tuile > **Ajouter une émission ou une radio**, autant de fois que voulu ; les éléments se réordonnent avec la poignée ≡. Couleur, titre et image se changent au même endroit.

Une erreur (réseau coupé, flux vide, flux illisible) ne bloque pas l'enchaînement : l'élément est sauté et un court message l'indique.

## Compilation (GitHub Actions)

Le workflow `.github/workflows/build.yml` compile l'APK à chaque envoi sur `main` (et sur les branches `claude/**`).
L'APK se télécharge depuis l'onglet **Actions** > dernière exécution > **Artifacts** > `RadioClic-1.0.N`.
Le numéro de version augmente automatiquement à chaque compilation.

### Signature stable (indispensable pour les mises à jour)

Pour que chaque nouvelle version s'installe **par-dessus** la précédente sans désinstaller, l'APK doit toujours être signée avec la même clé.

1. Créer une fois un keystore (sur un PC avec Java) :
   ```
   keytool -genkeypair -v -keystore radioclic.jks -alias radioclic -keyalg RSA -keysize 4096 -validity 36500
   ```
2. L'encoder en base64 :
   - Linux / macOS : `base64 -w0 radioclic.jks > radioclic.b64` (macOS : `base64 -i radioclic.jks -o radioclic.b64`)
   - Windows (PowerShell) : `[Convert]::ToBase64String([IO.File]::ReadAllBytes("radioclic.jks")) | Out-File radioclic.b64`
3. Dans le dépôt GitHub : **Settings > Secrets and variables > Actions > New repository secret**, créer :

   | Secret | Valeur |
   |---|---|
   | `KEYSTORE_BASE64` | contenu de `radioclic.b64` |
   | `KEYSTORE_PASSWORD` | mot de passe du keystore |
   | `KEY_ALIAS` | `radioclic` |
   | `KEY_PASSWORD` | mot de passe de la clé |

4. **Conserver précieusement** `radioclic.jks` et ses mots de passe (hors du dépôt) : sans eux, plus de mise à jour possible.

Sans ces secrets, la compilation fonctionne quand même mais l'APK est signée avec une clé de débogage temporaire (avertissement dans le journal de compilation).

### Compilation locale (facultatif)

Android Studio (ou JDK 17 + SDK Android) : `./gradlew assembleRelease` ou `./gradlew assembleDebug`.

## Technique

- Kotlin, Jetpack Compose, Material 3, interface en français.
- minSdk 26, targetSdk 36. Paquet : `com.cgexcel.radioclic`.
- Lecture : Media3 (ExoPlayer, HLS, `MediaSessionService`).
- Réseau : OkHttp ; RSS analysé avec `XmlPullParser` ; JSON avec kotlinx.serialization.
- Dernier épisode : flux RSS si disponible, sinon fiche Apple Podcasts de l'émission (bloc JSON `serialized-server-data` : titre, date, lien audio de chaque épisode récent).
- Configuration : fichier JSON local (`config.json`, écriture atomique).
- Permissions : Internet et état du réseau, notifications, service de premier plan (lecture média), maintien d'éveil pendant la lecture (écran éteint).

### Organisation du code

```
app/src/main/java/com/cgexcel/radioclic/
├── MainActivity.kt          écran principal (Compose)
├── ShortcutActivity.kt      lancement direct d'une tuile depuis un raccourci
├── RadioClicApp.kt          application (chargeur d'images)
├── model/                   configuration (tuiles, éléments) et réponses des API
├── data/ConfigRepository.kt enregistrement local, export / import JSON
├── net/                     HTTP, analyse RSS, recherche iTunes et Radio Browser
├── playback/                service Media3, construction des enchaînements, messages
└── ui/                      écrans : grille, mini-lecteur, paramétrage, éditeurs, recherche, À propos
```

### Format d'export

```json
{
  "format": "radioclic-config",
  "version": 1,
  "columns": 2,
  "tiles": [
    {
      "id": "…", "title": "Matinale", "color": 4280170079, "imageUrl": null,
      "items": [
        { "type": "podcast", "id": "…", "title": "Journal de 08h00", "feedUrl": "", "appleId": 541446017, "onlyToday": true },
        { "type": "live", "id": "…", "title": "France Inter", "streamUrl": "https://…", "maxMinutes": 10 }
      ]
    }
  ]
}
```

Les images choisies dans la galerie du téléphone restent sur ce téléphone : après un import sur un autre appareil, la tuile reprend la pochette par défaut (les images par adresse web sont conservées).

## Licence

MIT + BAL 1.0 — Bonne Action License (voir [LICENSE](LICENSE)).

En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour. Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez.
