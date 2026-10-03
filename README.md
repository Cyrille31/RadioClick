# RadioClic

**Un clic, une émission.** Application Android (testée pour Samsung Galaxy S23) qui ouvre une grille de gros boutons. Chaque bouton lance une émission ou un enchaînement d'émissions : journal de 8h, revue de presse, chronique, radio en direct…

CGExcel — Cyrille Gindre — © 2026 — Licence **MIT + BAL 1.0 — Bonne Action License**

> En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour. Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez.

Site : https://cgexcel.wordpress.com/

---

## Fonctions

- **Grille de tuiles** plein écran (1 à 4 colonnes) : titre, couleur, image ou pochette du podcast.
- **Un appui = lecture immédiate.** Les flux RSS sont résolus en parallèle, puis lus dans l'ordre.
- **Mini-lecteur** : élément en cours, position (« 2 / 3 »), lecture/pause, précédent, suivant, stop.
- **Lecture en arrière-plan** (Media3 / ExoPlayer) : écran éteint, notification, écran de verrouillage, Bluetooth (voiture, casque), pause pendant un appel puis reprise.
- **Types d'éléments**
  - *Podcast (RSS)* : dernier épisode, ou « uniquement s'il est du jour » (sinon l'élément est sauté avec un message, par ex. « Journal de 8h pas encore en ligne »).
  - *Radio en direct* : URL de flux, avec durée maximale optionnelle avant l'élément suivant.
- **Recherche sans URL** : podcasts via l'API iTunes Search (avec aperçu des derniers épisodes), radios via Radio Browser. La saisie manuelle d'une URL reste possible.
- **Paramétrage** (⚙ en haut à droite) : colonnes, glisser-déposer des tuiles et des éléments, ajout, duplication, suppression (avec confirmation).
- **Export / import** de la configuration en JSON (sauvegarde, changement de téléphone).
- **Raccourcis** : appui long sur une tuile > « Ajouter à l'écran d'accueil ».
- Sans compte, sans publicité, sans collecte de données.

## Utilisation rapide

1. Ouvrir ⚙ **Paramétrage** > **Ajouter une tuile**.
2. Donner un titre, choisir une couleur.
3. **Podcast** > **Rechercher un podcast** (ex. « journal 8h France Inter ») > toucher le bon résultat > vérifier l'aperçu des épisodes > choisir « Uniquement s'il est du jour » si besoin > **Ajouter**.
4. Ajouter d'autres éléments pour un enchaînement (ils se réordonnent avec la poignée ≡).
5. **Enregistrer la tuile**, revenir à la grille, appuyer : c'est parti.

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
        { "type": "podcast", "id": "…", "title": "Journal de 8h", "feedUrl": "https://…", "onlyToday": true },
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
