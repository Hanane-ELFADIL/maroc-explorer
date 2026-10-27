# Maroc Explorer

Application web de découverte touristique du Maroc, développée avec Spring Boot, Thymeleaf et MongoDB. Elle permet de parcourir les villes, lieux d’intérêt, hôtels, restaurants, activités et transports, puis de filtrer, comparer et planifier un itinéraire.

## Vue d’ensemble

Le projet est un site de voyage orienté “exploration locale” :

- page d’accueil avec présentation des villes et statistiques globales ;
- recherche multi-critères par ville, catégorie, mot-clé, niveau de budget et type d’activité ;
- pages détaillées des villes et des lieux ;
- génération d’itinéraires sur plusieurs jours ;
- gestion des favoris et système de compte utilisateur ;
- API JSON pour exploitation par d’autres clients côté front ou mobile.

## Stack technique

- Java 17
- Maven
- Spring Boot 3.3.4
- Spring Web MVC
- Spring Security
- Thymeleaf
- Spring Data MongoDB
- MongoDB

## Fonctionnalités principales

- Exploration des destinations marocaines
- Filtres de recherche et tri des résultats
- Cartographie géospatiale avec coordonnées et marqueurs
- Pages “ville” et “lieu” enrichies de données structurées
- Plans d’itinéraire personnalisés selon durée, budget et profil
- Favoris persistants par utilisateur
- Photos locales ou fallback Wikimedia, avec gestion des crédits
- Données importées automatiquement depuis JSON

## Structure du projet

```text
maroc-explorer/
├── src/
│   ├── main/
│   │   ├── java/ma/tourisme/
│   │   │   ├── App.java
│   │   │   ├── Config.java
│   │   │   ├── Places.java
│   │   │   ├── PhotoService.java
│   │   │   └── Web.java
│   │   ├── resources/
│   │   │   ├── application.properties
│   │   │   ├── data/
│   │   │   │   ├── activities.json
│   │   │   │   ├── attractions.json
│   │   │   │   ├── cities.json
│   │   │   │   ├── events.json
│   │   │   │   ├── hotels.json
│   │   │   │   ├── restaurants.json
│   │   │   │   └── transport.json
│   │   │   ├── static/
│   │   │   └── templates/
│   │   └── webapp/ (si ajouté plus tard)
│   └── test/
├── images/
│   ├── _defaults/
│   ├── activities/
│   ├── attractions/
│   ├── cities/
│   └── ...
├── tools/
│   └── generate_images.py
├── pom.xml
├── README.md
└── data.zip
```

## Prérequis

Avant de lancer le projet, il faut avoir installé :

- Java 17+
- Maven 3+
- MongoDB local accessible sur `mongodb://localhost:27017`

## Démarrage rapide

1. Démarrer MongoDB.
2. Se placer à la racine du projet.
3. Lancer :

```bash
mvn spring-boot:run
```

4. Ouvrir le navigateur sur :

```text
http://localhost:8080
```

## Construction du projet

Pour générer un package exécutable :

```bash
mvn clean package
```

Ensuite, le jar peut être lancé avec :

```bash
java -jar target/maroc-explorer-1.0.0.jar
```

## Données et import automatique

Les données de référence sont stockées dans `src/main/resources/data` et chargées au démarrage via `App.java`.

Le comportement d’import est piloté par la propriété :

```properties
app.seed=missing
```

Modes disponibles :

- `missing` : ajoute seulement les éléments absents
- `reset` : vide les collections et réimporte toutes les données
- `off` : désactive l’import automatique

Le projet détecte également les doublons par `slug` ou par nom/ville et les nettoie automatiquement lors du seed.

## Configuration

Le fichier principal de configuration est :

```properties
src/main/resources/application.properties
```

Propriétés importantes :

- `spring.data.mongodb.uri` : URI MongoDB
- `app.images-dir` : dossier d’images utilisé par l’application
- `app.page-size` : nombre de résultats par page
- `app.seed` : mode d’import des données
- `app.photos` : gestion des images externes (`auto` ou `off`)

## API JSON

L’application expose plusieurs endpoints utilisables par un autre front ou un client mobile :

- `GET /api/cities`
- `GET /api/places?city=&col=&q=&level=&cat=`
- `GET /api/nearby?lat=&lon=&m=`

Exemples :

```text
/api/places?city=Marrakech&col=attractions
/api/places?q=palmeraie&level=2
/api/nearby?lat=31.6295&lon=-7.9811&m=5000
```

## Gestion des images

Le projet prend en charge :

- images locales stockées dans `images/`
- URL externes fournies dans les données JSON
- fallback Wikimedia/Commons si aucune photo locale n’est présentée
- illustrations SVG générées via `tools/generate_images.py`

Les photos réalistes peuvent être téléchargées automatiquement à l’initialisation selon la configuration `app.photos`.

## Sécurité et comptes

Le projet inclut une couche de sécurité Spring Boot avec :

- inscription (`/signup`)
- connexion (`/login`)
- pages protégées pour les favoris (`/favorites`, `/fav`)

Les mots de passe sont hachés via BCrypt.

## Points d’attention

- Le projet dépend fortement de MongoDB pour stocker les collections : `cities`, `attractions`, `hotels`, `restaurants`, `activities`, `events` et `transport`.
- Les données doivent rester cohérentes entre `city` et `slug` pour que les pages de détail et les références de carte fonctionnent correctement.
- Lorsqu’on ajoute une ville ou de nouveaux lieux, il convient de les enregistrer dans les JSON et de redémarrer l’application pour réinjecter les données.

## Licence

Ce projet est fourni à des fins pédagogiques et de démonstration. Vérifier les droits d’utilisation des images et contenus tiers avant toute diffusion publique ou commerciale.

## Auteur / contexte

Projet réalisé dans le cadre d’une exploration de données touristiques et d’un site web de découverte du Maroc, avec un accent sur l’expérience utilisateur, la recherche et la visualisation locale.
