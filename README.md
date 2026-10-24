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

## Groupes et codes (1.10)

Code de format = M + adresse : 1 STATUS (M11 OVERVIEW) · 2 SYSTEMS (M21 BARO, M22 TEMPS, M23 POWER, M24 MOTION) ·
3 ALARMS (M31 ALARMS, M32 FAIL, M33 FEED, M34 SOURCES) · 4 WEATHER (M41 WEATHER, M42 RADAR, M43 FORECAST, M44 WEEK) ·
5 DAY (M51 DAY, M52 AGENDA, M53 HEALTH) · 6 COMMS (M61 NETWORK, M62 TOWERS, M63 SATCOM, M64 S-16) · 7 NAV (M71–M75) ·
8 UTILITY (M81 CHRONO, M82 LEVEL, M83 AUDIO) · 9 CMD (M91 QUICK SET).

Données conservées lors d'une mise à jour : stockage de la page (historiques, journal, santé, réveils, pas) et
fichier du journal continu de l'APK, relu au démarrage pour reconstituer 24 h de courbes.

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
compris (pression, batterie et charge, températures, état thermique, pas, signal mobile, latence Internet mesurée
par deux requêtes 204, mouvement sur 3 s d'accéléromètre), plus les déverrouillages et extinctions d'écran, dans un
fichier privé de l'application (une semaine environ). La page le lit au retour et complète graphes (2.1, 2.3 :
3/12/24 h), tendance de pression, pas, heure de réveil et journal 3.3. La permission de notifications n'est pas
demandée : la notification obligatoire du service n'apparaît pas dans le volet.

## Courbes et santé (1.9.2)

Toutes les courbes d'historique sont alimentées en continu par le journal de l'APK : batterie (2.1), mouvement (2.2),
pression (2.3), températures (2.4), latence et signal mobile (6.1), sur 3/12/24 h, axe en heures. Restent en direct
seulement : l'oscilloscope 10 s de 2.2 (`*1` LIVE), le niveau du micro (6.4, jamais enregistré) et la précision GNSS
(7.5, GPS non journalisé pour la batterie).

5.4 HEALTH : 7 jours glissants — nuits (coucher → réveil), réveil, coucher, sommeil, pas, minutes actives, temps
d'écran, déverrouillages, moyennes. Coucher et réveil viennent des événements d'écran (coups d'œil nocturnes écartés).

## 1.11.0

- **Briefing du matin** (5.1 `*2`, automatique au premier réveil) : nuit (coucher → réveil, durée face à l'objectif,
  bilan 7 nuits), météo du jour par tranches comme 4.3, pluie (prévision et radar), vent, pression, soleil, agenda,
  défauts, batterie et sa vitesse, état du S-16.
- **5.3 HEALTH** : `*1` vue 7 j / 30 j (nuits et pas en barres, moyennes par semaine), `*2 GOAL 7.5` objectif de
  sommeil ; régularité (écart type des heures de coucher et de lever) et dette ou avance de sommeil. 35 jours gardés.
- **Éclairage de nuit** (9.1 `*0`, `NIGHT AUTO|ON|OFF`) : console en rouge atténué ; AUTO = pièce sombre
  (capteur de lumière < 3 lx pendant 20 s), sans capteur après le crépuscule.
- **Alertes vibrées** (3.4 `*7`, APK, sans notification) : batterie ≤ 10 %, chaleur, choc, chute de pression
  (règle ALERT), pluie dans la demi-heure ; motifs distincts ; chaque alerte est notée au journal (`FEED VIB`).
- **Nowcast radar** (4.2) : déplacement des échos mesuré sur les 3 dernières images radar, extrapolé jusqu'à
  +60 min (images « NOWC », points sur la frise) ; « RAIN IN ~15 MIN » et vibration dans l'appli.
- **Sauvegarde** (3.4) : `*4 BACKUP` (un fichier JSON dans Téléchargements/Grafit), `*5 RESTORE` (deux appuis,
  après une réinstallation), `*6 CSV` (santé par jour et tous les relevés du journal continu).
- **6.4 S-16** : état du PC (températures CPU/GPU, charges, VRAM, RAM, disques, historique 2 h) reçu sur le Wi-Fi
  local depuis `tools/s16-link.ps1` (diffusion UDP 47616 ; rien vers Internet, aucun port ouvert sur le PC).
  Sur le S-16 : `powershell -ExecutionPolicy Bypass -File .\s16-link.ps1 -Test` puis `-Install`. Température CPU :
  HWiNFO64 → ligne CPU (Tctl/Tdie ou Package) → « Report value in Gadget ».
- **Journal continu allégé** : un relevé toutes les 5 min quand le téléphone est immobile écran éteint ; coût réel
  du service affiché en 3.4 (relevés, temps de calcul, données).
- **3.3 FEED filtrable** : `FEED SCR`, `FEED NET`, `FEED PWR`, `FEED WARN`, `FEED ALL`, `*4` catégorie suivante.
- **Widget d'écran d'accueil** : date, pluie à venir, prochain rendez-vous, batterie ; toucher ouvre Grafit.
- Correction : le coucher de la veille n'est plus écrasé par celui de la nuit suivante (sommeil manquant en 5.3).

## 1.11.1

- **GNSS** : voyant d'information (blanc, sans vibration) au lieu d'une alarme : position grossière (> 50 m) ou
  absente depuis 5 min. L'APK demande aussi la position « fusionnée » (Wi-Fi, réseau) pour en avoir une à l'intérieur.
- **Voyant SIM** (en haut à droite) : allumé seulement si un capteur ou la météo est simulé. Dans l'APK, plus aucune
  simulation automatique (source absente = N/A) ; le TLE et le catalogue de satellites en cache ne l'allument plus.
- **Courbe de pression** : plus jamais effacée (changement de source, simulation de démarrage, rechargement) ;
  au démarrage de l'APK, les 24 h sont reconstruites depuis le journal continu.
- **8.2 LEVEL** : les valeurs restent dans leur afficheur (4 chiffres).
- **2.3 POWER** : la zone rouge de la jauge est sous 20 % (elle était inversée) ; seuils du graphique libellés 20 % / 10 %.

## 1.11.2

- **Taille de l'affichage** : `ZOOM +` agrandit maintenant le texte (c'était l'inverse) ; plage 80–110 %, ligne
  « TEXT SIZE » en 9.1 (`*6` / `*7`, ou `ZOOM 100` de n'importe quel format). Le zoom repart de 100 % à cette mise à jour.
- **2.1 BARO / 2.3 POWER** : la courbe passe avant les lignes de détail, elle reste affichée sur un écran plus court.
- **2.3 POWER** : graduations sous la jauge (0, 10, 20 en rouge = seuils d'alarme, 50, 100 %).
- **4.2 RADAR** : ±15 km par défaut, premier cercle à 5 km ; `*2 KM` passe à 30 puis 60 km.
- **5.3 HEALTH** : en-têtes de colonnes (WOKE · BED · ASLEEP · WAKE · SLEPT), axe en heures, objectif et moyenne
  en tirets blancs sur les barres 30 jours (valeurs dans l'en-tête), semaines datées ; plus de rouge pour une nuit courte.

## 1.11.3

- **Luminosité** (commande, depuis n'importe quel format) : `BRIGHT 5…100` (%), `BRIGHT AUTO` (réglage d'Android),
  `BRIGHT +` / `BRIGHT -` (±10), `BRIGHT NIGHT n|AUTO` (luminosité pendant l'éclairage rouge). APK : luminosité de
  l'écran tant que Grafit est affiché, sans changer le réglage d'Android. Affichée en 9.1.
- **Éclairage rouge** : en AUTO, seulement dans le noir d'après le capteur de lumière (plus jamais d'après l'heure) :
  rouge sous 3 lx pendant 20 s, normal au-dessus de 15 lx pendant 20 s. `NIGHT LUX n` règle le seuil.

## 1.11.4

- **8.3 AUDIO** : `*1` arme bien le micro (la bascule était inversée) ; refus d'Android → permission redemandée ;
  le micro est relâché dès que Grafit passe en arrière-plan.
- **Lien S-16** : `tools/s16-link-install.cmd` (double-clic) installe la diffusion en arrière-plan, cachée, tout de
  suite et à chaque ouverture de session (tâche planifiée, ou raccourci du dossier Démarrage si la tâche est refusée).
  `s16-link.ps1 -Status` dit si elle tourne ; une seule instance à la fois.

