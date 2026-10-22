# ГРАФИТ · GRAFIT OS Mobile

Console de bord Soyuz du téléphone, version web installable (PWA). Une seule page, sans dépendance
(hors police Martian Mono de Google Fonts, avec repli). Fonctionne hors ligne une fois installée.

- Ouvrir l'adresse HTTPS dans Chrome (Android) → menu ⋮ → « Ajouter à l'écran d'accueil » / « Installer l'application ».
- Les capteurs ne sont accessibles qu'en HTTPS.
- Baromètre, température batterie, lumière, proximité : réservés à l'APK (passerelle `grafit-native`).

## Antennes-relais (M07 TOWERS)

`cells/` est généré chaque semaine par `.github/workflows/cells.yml` (`tools/build_cells.py`) à partir des
« Données sur les réseaux mobiles » de l'ANFR (data.gouv.fr, Licence Ouverte 2.0) : stations en service par
opérateur, générations, bandes, hauteur et azimuts des antennes, en tuiles de 0,2°.

## Journal (3.3 FEED)

Le journal est conservé sur l'appareil (2 000 événements, plusieurs jours), passages au premier plan / en
arrière-plan compris. `LOG EXPORT` (ou 3.3 `*1`) l'écrit dans `Téléchargements/Grafit/grafit-log-AAAAMMJJ-HHMM.txt`
(APK) ou le télécharge (web), avec l'état des sources au moment de l'export.

## Groupes et codes (1.8.1)

Code de format = M + adresse : 1 STATUS (M11) · 2 SYSTEMS (M21 POWER, M22 MOTION, M23 BARO, M24 TEMPS) ·
3 ALARMS (M31 ALARMS, M32 FAIL, M33 FEED, M34 SOURCES) · 4 WEATHER (M41 FORECAST, M42 WEATHER, M43 WEEK) ·
5 DAY (M51 AGENDA, M52 DAY, M53 CHRONO) · 6 COMMS (M61–M64) · 7 NAV (M71–M75) · 8 CMD (M81 QUICK SET).

## Alarmes (3.1)

Paramètres du téléphone seulement : batterie (charge, température, santé Android), état thermique, choc,
réseau, signal mobile, GNSS, boussole, flux des capteurs, stockage. Le FCU virtuel du S-16 est retiré ; la
tendance de pression reste une information sur 2.3 BARO.

## Radar (4.4, 1.9.0)

Passé (−2 h) : radar RainViewer (images de 10 min, gratuites en usage personnel, zoom 7), décodé et recoloré.
Futur (+2 h) : prévision Open-Meteo au quart d'heure sur une grille 7 × 7 autour du téléphone (modèle). Fond :
carte sombre Esri Dark Gray Canvas sans clé (© Esri, HERE, OSM) ou, avec une clé CARTO personnelle saisie par
`MAPKEY <clé>` (gratuite en usage non commercial, à demander sur carto.com/basemaps/apikey), CARTO Dark ; ou
repères seuls (antennes ANFR). Pluie la plus proche et approche
estimée à partir des dernières images radar.

## Journal continu (APK, 1.9.0)

Service Android de premier plan (réglable en 3.4 `*3`, actif par défaut) : un relevé par minute, écran éteint
compris (pression, batterie, état thermique, pas, signal), plus les déverrouillages et extinctions d'écran, dans un
fichier privé de l'application (une semaine environ). La page le lit au retour et complète graphes (2.1, 2.3 :
3/12/24 h), tendance de pression, pas, heure de réveil et journal 3.3. La permission de notifications n'est pas
demandée : la notification obligatoire du service n'apparaît pas dans le volet.
