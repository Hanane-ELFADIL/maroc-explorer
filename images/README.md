# Dossier images

Structure : `images/<collection>/<slug>.<ext>` (cities, attractions, hotels, restaurants, activities, events, transport).

* **Photos réelles** : au démarrage, l'application télécharge automatiquement pour chaque élément une photo libre
  (Wikipédia / Wikimedia Commons) et l'enregistre ici en `.jpg`. Chaque photo n'est utilisée qu'une seule fois.
  Auteur, licence et lien sont dans `credits.tsv` et affichés sur les pages ville / lieu.
* **Vos propres photos** : déposez `images/<collection>/<slug>.jpg` (ou .png / .webp) : elle remplace tout le reste.
* `*.svg` : illustrations de secours, utilisées seulement tant qu'aucune photo n'existe.
* `photo-misses.txt` : éléments sans résultat (supprimer le fichier, ou `app.photos.retry=true`, pour réessayer).
* Désactiver le téléchargement : `app.photos=off`.
