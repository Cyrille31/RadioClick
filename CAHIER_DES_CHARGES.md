# RadioClic — Cahier des charges (nom provisoire)

Application Android (Samsung Galaxy S23) — marque **CGExcel** — auteur **Cyrille Gindre** — © 2026
Licence : **MIT + BAL 1.0 — Bonne Action License**

> Document destiné à Claude Code. Consigne : lire ce cahier des charges en entier, poser les questions bloquantes s'il y en a, puis réaliser le projet complet, prêt à être compilé par GitHub Actions.

---

## 1. Objectif

Un clic sur l'icône de l'application ouvre un **tableau de boutons**. Chaque bouton lance **une émission ou un enchaînement d'émissions** (par exemple : journal de 8h, puis revue de presse, puis une chronique). L'utilisateur configure lui-même les boutons : place, titre, contenu.

## 2. Écran principal

- Grille de boutons (tuiles) en plein écran, gros boutons lisibles.
- Chaque tuile affiche : titre, couleur choisie, icône ou image optionnelle (pochette du podcast par défaut).
- Un appui lance la lecture immédiatement.
- Un mini-lecteur en bas d'écran pendant la lecture : titre de l'élément en cours, position dans l'enchaînement (« 2 / 3 »), boutons lecture/pause, précédent, suivant, stop.
- Un bouton **⚙ Paramétrage** (en haut à droite) ouvre le mode configuration.

## 3. Paramétrage

### 3.1 Disposition
- Nombre de colonnes réglable (1 à 4).
- Mode édition : réorganisation des tuiles par **glisser-déposer**.
- Ajout, duplication et suppression de tuiles (avec confirmation).

### 3.2 Édition d'une tuile
- **Titre** (texte libre).
- **Couleur** (palette) et image optionnelle.
- **Liste de lecture** : liste ordonnée d'éléments, réorganisable par glisser-déposer. Une tuile peut contenir un seul élément ou plusieurs qui s'enchaînent.

### 3.3 Types d'éléments
1. **Podcast (flux RSS)** — options :
   - « Dernier épisode » (par défaut) ;
   - « Uniquement s'il est du jour » : si le dernier épisode date d'avant aujourd'hui, l'élément est sauté et un court message l'indique (« Journal de 8h pas encore en ligne »).
2. **Radio en direct** (URL de flux audio) — option de durée maximale (ex. 10 min) avant de passer à l'élément suivant ; sans durée, le direct est le dernier élément.

### 3.4 Trouver une émission sans connaître d'URL
- **Recherche de podcasts** par mots-clés via l'API publique iTunes Search (sans clé) : afficher nom, éditeur, pochette ; un appui récupère l'URL du flux RSS. Prévoir aussi la saisie manuelle d'une URL RSS.
- Aperçu de la liste des derniers épisodes du flux choisi, pour vérifier que c'est le bon.
- **Recherche de radios** via l'API publique Radio Browser (radio-browser.info) ; saisie manuelle d'URL de flux possible aussi.

### 3.5 Sauvegarde
- Configuration enregistrée localement (JSON ou Room).
- **Export / import** de la configuration en fichier JSON (pour sauvegarde ou changement de téléphone).

## 4. Lecture

- Media3 / ExoPlayer avec `MediaSessionService` : lecture en arrière-plan, écran éteint, commandes dans la notification, sur l'écran de verrouillage et au Bluetooth (voiture, casque).
- L'enchaînement est construit au moment de l'appui : résolution de chaque flux RSS (en parallèle), puis lecture dans l'ordre. Un élément en erreur (réseau, flux vide) est sauté avec un message, sans bloquer la suite.
- Lancer une tuile pendant une lecture remplace l'enchaînement en cours.
- Gestion du focus audio (pause pendant un appel, reprise ensuite).

## 5. Raccourcis (bonus)

- Appui long sur une tuile → « Ajouter à l'écran d'accueil » : crée un raccourci épinglé qui lance directement cette tuile sans passer par la grille.

## 6. Technique

- Kotlin, Jetpack Compose, Material 3, interface **en français**.
- minSdk 26, targetSdk = dernière version stable.
- Nom de paquet : `com.cgexcel.radioclic` (à adapter si le nom change).
- Pas de compte, pas de publicité, pas de collecte de données ; seule permission réseau + notifications + service de premier plan (lecture média).

## 7. Compilation et dépôt GitHub

- Workflow GitHub Actions `.github/workflows/build.yml` : compile l'APK à chaque envoi sur la branche principale et le publie comme artefact téléchargeable.
- **Signature stable** (keystore fourni par secrets GitHub) pour que chaque nouvelle version s'installe par-dessus la précédente sans désinstaller.
- `.gitignore` adapté à Android.
- Rappel : le dépôt est alimenté **par le navigateur**, qui ignore les dossiers et fichiers cachés. Lister clairement à la fin du travail les fichiers cachés (`.github/…`, `.gitignore`) à placer manuellement.

## 8. Licence et mentions

- Fichier `LICENSE` : licence MIT suivie du texte « BAL 1.0 — Bonne Action License ».
- Dans le **README** et l'écran **« À propos »** (accessible depuis le paramétrage), à côté de la mention de licence, ajouter la phrase :
  « En échange, une seule chose vous est demandée, sur l'honneur : faire une bonne action chaque jour. Aider un voisin, sourire à un inconnu, ramasser un papier… c'est vous qui voyez. »
- Écran « À propos » : nom, version, CGExcel, Cyrille Gindre, © 2026, lien vers https://cgexcel.wordpress.com/.
- En-tête (cartouche) dans les fichiers source : CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0.

## 9. Critères de recette

1. Créer une tuile « Journal 8h » avec le podcast du journal de 8h : un appui joue l'épisode du jour.
2. Créer une tuile « Matinale » avec trois éléments : ils s'enchaînent sans intervention.
3. Éteindre l'écran pendant la lecture : la lecture continue, commandes visibles sur l'écran de verrouillage.
4. Déplacer, renommer, supprimer des tuiles : la grille reste correcte après fermeture et réouverture de l'appli.
5. Exporter la configuration, tout supprimer, réimporter : tout revient.
6. Couper le réseau puis lancer une tuile : message clair, pas de plantage.
