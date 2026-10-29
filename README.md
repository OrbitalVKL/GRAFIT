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

## Groupes et codes (1.15)

Code de format = M + adresse : 1 SYSTEMS (M11 BRIEF, M12 OVERVIEW, M13 BARO, M14 TEMPS, M15 POWER, M16 MOTION) ·
2 ALARMS (M21 ALARMS, M22 FAIL, M23 FEED, M24 SOURCES) · 3 WEATHER (M31 WEATHER, M32 RADAR, M33 FORECAST, M34 WEEK) ·
4 DAY (M41 DAY, M42 AGENDA, M43 HEALTH) · 5 COMMS (M51 NETWORK, M52 TOWERS, M53 SATCOM, M54 S-16) ·
6 NAV (M61 ROUTE, M62 GNSS, M63 SATS, M64 TRACK, M65 SKY, M66 KURS) · 7 FINANCE (M71 FINANCE, Klad) ·
8 UTILITY (M81 CHRONO, M82 LEVEL, M83 AUDIO) · 9 CMD (M91 QUICK SET).
Les adresses citées dans les notes de version ci-dessous sont celles de l'époque.

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

## 1.11.5

- **Micro** : permission `MODIFY_AUDIO_SETTINGS` ajoutée (la WebView d'Android ne démarre pas la capture audio sans
  elle, même micro autorisé) ; la permission du micro est demandée par Android seulement si elle manque ; contexte
  audio créé au moment de l'appui ; en cas d'échec, 8.3 affiche la raison (FEED AUD).

## 1.11.6

- **8.3 AUDIO, niveau sonore en dB** : `*2 UNIT` passe de dB(A) (bruit, comme un sonomètre, par défaut) à dB (non
  pondéré) et dBFS (échelle numérique brute). Pondération temporelle FAST (125 ms), LEQ 10 s, maximum (`*4` remet à
  zéro), repère 85 dB (risque auditif). Le micro du téléphone n'est pas étalonné : +100 dB par défaut (±10 dB) ;
  `CAL 45` (`*3`) cale la lecture sur un sonomètre de référence, `CAL RESET` revient à l'estimation.

## 1.17.1

- **9.1 `*5` AUTO** : la luminosité repasse en automatique (réglage d'Android) ; un 2e appui reprend la dernière valeur
  manuelle. `FEEDS` reste disponible en commande.

## 1.17.0

- **Finances dans les alarmes** : 12e voyant `FINANCE` (panneau 1.2 et 2.1) — CRIT/CAUT selon les alertes de Klad, comptes à
  vérifier ou reste à vivre négatif ; page de défaut 2.2 avec consignes ; journal.
- **Briefing 1.1** : section FINANCE (reste à vivre, mois dépensé / budget, prélèvements des 7 jours, à vérifier, alerte),
  regroupée sur écran court.

## 1.16.1

- **7.1 FINANCE**, résumé Klad 0.5 / 0.6 : ligne `TO CHECK IN KLAD` (nouveautés, à classer, comptes à vérifier, sauvegarde),
  `LEFT TO LIVE` (reste à vivre du cycle, avec sa base BUDGET / ACCOUNTS) en tête du mois, carte avec sa date `due_since`,
  alertes affichées avec leur code (TO REVIEW, ACCOUNT CHECK, BACKUP DUE, UPDATE AVAILABLE…). Repli `fortuneo_after_amex` retiré.

## 1.16.0

- **7.1 FINANCE** : résumé de l'app de finances **Klad** (`io.github.orbitalvkl.klad`), lu sur le téléphone par son
  ContentProvider en lecture seule (`content://io.github.orbitalvkl.klad.grafit/summary`). Klad n'est lu que si son
  certificat de release correspond à l'empreinte épinglée (SHA-256 `48B6…9F1C`) ; Klad ne répond qu'à Grafit. Aucun
  réseau ; le résumé n'est jamais écrit par Grafit (en mémoire seulement).
- Affichage : alertes de Klad, soldes par compte (carte : montant dû), total, compte principal − carte, mois en cours
  face au budget (barre avec repère du jour), postes en dépassement, fin de mois du compte commun, échéances à venir.
  Les champs que Klad ne fournit pas encore sont simplement absents. `*1` relit ; relecture aussi à l'ouverture, au
  retour au premier plan, à chaque changement signalé par Klad et toutes les 15 min page affichée.
- Alertes de Klad au journal (`FEED FIN`), une fois par jour chacune ; Klad absent, mal signé ou refusant Grafit :
  état affiché en 7.1, sans erreur.

## 1.15.1

- **9.1 `*9`** : pendant la vérification de version, `CHECK IN PROGRESS` en rouge clignotant à côté de `*9` (au moins
  3 s, la réponse de GitHub étant souvent immédiate), au lieu de l'état UP TO DATE resté fixe.

## 1.15.0

- **Sommeil (4.3 HEALTH, ex-5.3)** : le coucher était parfois avancé, donc la nuit trop longue. La dernière vérification
  du téléphone avant de dormir (réveil, heure : moins de 3 min, après 00:30) était prise pour un coup d'œil nocturne et
  écartée ; le coucher retombait sur la session d'avant. Un coup d'œil nocturne doit maintenant être précédé de 90 min
  sans téléphone (déjà endormi). Au réveil, une courte session après l'alarme − 30 min, ou suivie de pas, compte comme
  le lever. Le coucher suit le podomètre tant que le téléphone, en poche, bouge encore. Les nuits de la semaine passée
  sont recalculées une fois depuis le journal continu (`DAY NIGHTS RECOMPUTED` au journal).
- **Groupes** : BRIEF et OVERVIEW passent en tête de 1 SYSTEMS (1.1, 1.2 ; capteurs en 1.3 à 1.6), les groupes
  suivants remontent d'un cran (ALARMS 2, WEATHER 3, DAY 4, COMMS 5, NAV 6) et changent de code ; 7 FINANCE est réservé
  au carnet de comptes (page d'attente, rien n'est lu) ; 8 UTILITY et 9 CMD ne bougent pas.
- **9.1 `*9`** : l'action à gauche (CHECK FOR UPDATE, ou INSTALL x.y.z quand une version est disponible), l'état à
  droite (UP TO DATE · heure de la dernière réponse de GitHub, AVAILABLE, CHECKING, erreur) ; touche CHECK / INSTALL.

## 1.14.4

- **6.4 S-16** : disques en Go libres + % à 0,1 près ; lecteur Google Drive exclu ; uptime depuis le dernier démarrage
  réel (démarrage rapide de Windows et sortie de veille compris, journal Système) — script `tools/s16-link.ps1` à jour.

## 1.14.3

- **7.1 ROUTE** : calcul `TRAFFIC_AWARE_OPTIMAL` (celui de l'appli Google Maps) au lieu de `TRAFFIC_AWARE` (version
  allégée, moins précise) ; même tarif Google (Compute Routes Pro), mêmes plafonds.

## 1.14.2

- **Pluie « maintenant »** : l'observation prime sur le modèle. Radar au-dessus (nowcast), précipitation du moment
  (Open-Meteo `current`) ou code météo de pluie → `RAINING … (RADAR|OBS)` partout (4.1, 4.3, 1.1, 1.2), même si la
  prévision au quart d'heure dit sec.

## 1.14.1

- **Position de repli neutre** : la simulation et le repli sans GNSS ne pointent plus sur une ville réelle de l'auteur
  (point zéro de Paris). Dès le premier fix, la dernière position connue (arrondie à ~1 km) sert de repli ; elle reste
  dans le stockage local du téléphone et n'est envoyée nulle part.

## 1.14.0

- **Taille d'affichage fixe** : plus de zoom (46 colonnes sur la largeur, toujours) ; `*6` / `*7` de 9.1 règlent
  désormais la luminosité.
- **Plus de place pour les formats** (+4 lignes) : la rangée d'onglets des groupes disparaît (le pavé porte déjà les
  groupes et allume le groupe actif), le filet sous le bandeau est tracé dans la 2e ligne, plus d'espace entre les
  onglets des formats et le pavé.
- **Contrôle de tous les formats** sur la grille du Pixel 10 Pro avec barres système (46 × 43 à 45) : rien n'est coupé.
  Sur un écran court, chaque format retire d'abord ses lignes d'espacement, puis regroupe (1.1 BRIEF : défauts, pas,
  soleil, vent + pression, bilan 7 nuits ; 3.4 : sources absentes sur une ligne), réduit ses courbes, et ne garde
  jamais une coupure silencieuse. Corrigés : 5.1 (7 derniers réveils), 5.3 (tableau DAYS, 30 jours), 4.3 (4 tranches +
  frise horaire), 6.4 (courbe), 7.1 (courbe du jour), 4.2 (touches), 3.4.

## 1.13.1

- **3.4 SOURCES · API** : ligne `API` (Google, TomTom, Bison Futé : OK / ERR / ?) ; `*8` (ou `API` de partout) ouvre le
  détail : état, dernier succès, erreur, compteurs du jour et du mois, carte CARTO, gratuité Google vérifiée, plafonds,
  empreinte de l'appli comparée à celle du dernier succès Google.
- **Contrôle réel** (une requête par service) : au premier démarrage après chaque mise à jour, après `ROUTEKEY` /
  `INCIDENTKEY`, après `PLACE` si tout est réglé, et à la demande (`*9` ou `CHECK`). Résultat dans 3.4, au journal (NAV,
  en rouge si un service échoue) et, en cas d'échec, sur une ligne `API` rouge du briefing 1.1.
- Mise à jour notée au journal : `UPDATED a → b · DATA KEPT · ROUTE KEY · INCIDENT KEY · PLACES`.

## 1.13.0

- **1.1 BRIEF** : le briefing devient un format, affiché en continu (nuit, météo, trajet du moment, agenda, état) ;
  `*1` actualise météo et trafic. La console démarre dessus ; au premier réveil du jour elle y revient. `BRIEF` y mène
  de partout (et 5.1 `*2`). 1.2 OVERVIEW (ancien M11).
- **7 NAV réordonné** : 7.1 ROUTE (M71), 7.2 GNSS (M72), 7.3 SATS (M73), 7.4 TRACK (M74), 7.5 SKY (M75), 7.6 KURS (M76).
- **ROUTE** : après une requête Google en échec (clé refusée, réseau), nouvel essai au bout de 5 min seulement
  (auparavant une requête par seconde tant que la page restait affichée, quota du jour vite épuisé) ; `*1` force.

## 1.12.1

- **Ligne de commande au toucher** : `↵ CMD` (pavé) ou un appui sur la ligne du bas ouvre le clavier ; le champ
  s'affiche juste au-dessus du clavier (appui long → Coller, ou presse-papiers du clavier). Corrige la saisie qui se
  refermait aussitôt (focus repris par le canvas au relâchement du doigt). `↵` du pavé exécute la saisie en cours,
  `ESC` la ferme. La console garde sa taille pendant la saisie.

## 1.12.0

- **7.6 ROUTE** : trajet maison ↔ travail en voiture. Durée avec le trafic actuel, état du trafic (FLUID / HEAVY /
  JAMMED), retard sur le trajet sans trafic, arrivée si départ immédiat, courbe de la journée ; `*3` ouvre
  l'itinéraire dans Google Maps. Avant midi maison → travail ; à partir de midi, ou dès que la position est au travail,
  travail → maison (`*2` inverse).
  - Durées : Google Routes API (`ROUTEKEY <clé>`, clé personnelle restreinte à l'appli Grafit et à la Routes API).
  - Garde-fous de facturation : 100 requêtes par jour, 4000 par mois (gratuité Google : 5000) ; veille hebdomadaire
    de la page des tarifs Google : si la gratuité baisse, le plafond passe à 80 % de la nouvelle valeur ; tarif
    illisible trois semaines de suite : plafond 2000.
  - Évènements sur le trajet : Bison Futé (données ouvertes, réseau national non concédé : routes et voies fermées,
    travaux, déviations) et, avec `INCIDENTKEY <clé TomTom>` (gratuite, sans carte bancaire), accidents, bouchons,
    routes fermées sur tout le réseau.
  - Lieux : `PLACE HOME` / `PLACE WORK` sur place, ou suivis d'une adresse ou de coordonnées.
- **Briefing** : ligne TRAFFIC (et évènement grave sur le trajet) ; `1.1 *1` (ou `BRIEF`) le rouvre à tout moment, il
  reste affiché jusqu'à ESC.

