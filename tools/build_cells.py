"""Antennes-relais pour Grafit Mobile (écran M07 TOWERS).

Source : ANFR, « Données sur les réseaux mobiles » (data.gouv.fr, mise à jour hebdomadaire, Licence Ouverte 2.0).
Le ZIP contient un CSV (séparateur ;) avec une ligne par émetteur : opérateur, système, génération, hauteur du
support, coordonnées, statut, azimuts des antennes. Les émetteurs sont regroupés par station (n° ANFR + opérateur),
seuls ceux « En service » ou « Techniquement opérationnel » sont gardés, puis découpés en tuiles de 0,2° :
cells/<floor(lat*5)>_<floor(lon*5)>.json = {"v":1,"d":date,"ops":[...],"s":[[lat,lon,op,gen,h,"az|az","bandes",id],...]}
gen : bits 1=2G 2=3G 4=4G 8=5G. bandes : G9 = GSM 900, U21 = UMTS 2100, L8 = LTE 800, N35 = 5G NR 3500…

Usage : python3 tools/build_cells.py [fichier.zip]   (sans argument : télécharge le dernier ZIP publié)
"""
import csv, io, json, math, os, re, shutil, sys, urllib.request, zipfile

DATASET = '6ab291cf426d615c84196260'          # data.gouv.fr : donnees-sur-les-reseaux-mobiles
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'cells')
MAIN_OPS = ['ORANGE', 'SFR', 'BOUYGUES TELECOM', 'FREE MOBILE']
KEEP = {'En service', 'Techniquement opérationnel'}
GEN = {'2G': 1, '3G': 2, '4G': 4, '5G': 8}
PFX = {'GSM': 'G', 'UMTS': 'U', 'LTE': 'L', '5G NR': 'N'}


def latest_zip():
    meta = json.load(urllib.request.urlopen('https://www.data.gouv.fr/api/1/datasets/%s/' % DATASET, timeout=60))
    res = [r for r in meta['resources'] if r['title'].lower().startswith('observatoire') and r['title'].lower().endswith('.zip')]
    res.sort(key=lambda r: r['last_modified'], reverse=True)
    r = res[0]
    print('source', r['title'], r['last_modified'])
    return urllib.request.urlopen(r['url'], timeout=600).read(), r['last_modified'][:10]


def band(sysname):
    m = re.match(r'\s*(5G NR|GSM|UMTS|LTE)\s*(\d+)', sysname or '')
    if not m:
        return (sysname or '').strip().replace(' ', '')[:5]
    mhz = int(m.group(2))
    return PFX[m.group(1)] + str(mhz // 100 if mhz >= 1000 or mhz % 100 == 0 else mhz)


def build(data, date):
    z = zipfile.ZipFile(io.BytesIO(data))
    name = next(n for n in z.namelist() if n.lower().endswith('.csv'))
    if not date:
        m = re.search(r'(\d{4})(\d{2})(\d{2})', name)
        date = '%s-%s-%s' % m.groups() if m else ''
    st, ops = {}, list(MAIN_OPS)
    with z.open(name) as f:
        for row in csv.DictReader(io.TextIOWrapper(f, encoding='utf-8', newline=''), delimiter=';'):
            if (row.get('statut') or '').strip() not in KEEP:
                continue
            try:
                lat, lon = (float(x) for x in row['coordonnees'].split(','))
            except (ValueError, KeyError, AttributeError):
                continue
            op = (row.get('adm_lb_nom') or '').strip()
            key = (row.get('sta_nm_anfr'), op)
            s = st.get(key)
            if s is None:
                if op not in ops:
                    ops.append(op)
                try:
                    h = round(float((row.get('sup_nm_haut') or '0').replace(',', '.')), 1)
                except ValueError:
                    h = 0
                s = st[key] = {'lat': lat, 'lon': lon, 'op': ops.index(op), 'gen': 0, 'h': h, 'az': set(), 'b': set(), 'id': row.get('sta_nm_anfr') or ''}
            s['gen'] |= GEN.get((row.get('generation') or '').strip(), 0)
            for a in (row.get('list_azimut') or '').split('|'):
                try:
                    s['az'].add(int(round(float(a.replace(',', '.')))) % 360)
                except ValueError:
                    pass
            s['b'].add(band(row.get('emr_lb_systeme')))
    tiles = {}
    for s in st.values():
        k = '%d_%d' % (math.floor(s['lat'] * 5), math.floor(s['lon'] * 5))
        h = int(s['h']) if s['h'] == int(s['h']) else s['h']
        tiles.setdefault(k, []).append([round(s['lat'], 5), round(s['lon'], 5), s['op'], s['gen'], h,
                                        '|'.join(str(a) for a in sorted(s['az'])), ' '.join(sorted(s['b'])), s['id']])
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    for k, lst in tiles.items():
        with open(os.path.join(OUT, k + '.json'), 'w', encoding='utf-8') as f:
            json.dump({'v': 1, 'd': date, 'ops': ops, 's': lst}, f, ensure_ascii=False, separators=(',', ':'))
    with open(os.path.join(OUT, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump({'v': 1, 'd': date, 'stations': len(st), 'tiles': len(tiles), 'ops': ops,
                   'source': 'ANFR · Données sur les réseaux mobiles · data.gouv.fr · Licence Ouverte 2.0'}, f, ensure_ascii=False)
    print('stations', len(st), 'tiles', len(tiles), 'date', date)


if __name__ == '__main__':
    if len(sys.argv) > 1:
        build(open(sys.argv[1], 'rb').read(), '')
    else:
        build(*latest_zip())
