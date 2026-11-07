// Tests de la logique pure de Grafit Mobile (bloc « // <logic> … // </logic> » de index.html).
// Lancer : node tools/test_logic.js   (Node 18 ou plus, aucune dépendance)
'use strict';
const fs = require('fs'), path = require('path'), assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const m = html.match(/\/\/ <logic>\n([\s\S]*?)\/\/ <\/logic>/);
if (!m) { console.error('bloc // <logic> introuvable dans index.html'); process.exit(1); }
const L = new Function('"use strict";\n' + m[1] + '\nreturn { rteFresh, rteUsual, rteSuspect, parseEur, finSignals, finBudKey, finBudKeys, finAckMake, finAckEval, nightFind, nightShifted, mbParse, serAt, serNear, wxOverlay, quotaRoll, quotaLvl, drainRate, battPlan };')();

let ok = 0, ko = 0;
const test = (name, fn) => { try { fn(); ok++; console.log('  ok   ' + name); } catch (e) { ko++; console.log('  FAIL ' + name + '\n       ' + e.message); } };

// ------------------------------------------------------------------ trajet : fraîcheur
console.log('ROUTE · FRESHNESS');
const T = 1_800_000_000, H = 3600;
const O = (o = {}) => Object.assign({ now: T, wake: T - 3600, fail: 0, busy: 0, every: 600, stale: 900, wait: 45 }, o);

test('démarrage le matin, donnée de la veille, requête en cours → UPDATE IN PROGRESS', () => {
  const f = L.rteFresh({ t: T - 12 * H }, O({ wake: T - 2, busy: T - 1 }));
  assert.strictEqual(f.st, 'upd'); assert.strictEqual(f.age, 12 * H);
});
test('juste réveillé, requête pas encore partie → UPDATE IN PROGRESS', () => {
  assert.strictEqual(L.rteFresh({ t: T - 12 * H }, O({ wake: T - 3 })).st, 'upd');
});
test('réveil, l\'API échoue → STALE (fail), même pendant le nouvel essai', () => {
  assert.deepStrictEqual(L.rteFresh({ t: T - 12 * H }, O({ wake: T - 30, fail: T - 20 })), { st: 'stale', age: 12 * H, why: 'fail' });
  assert.strictEqual(L.rteFresh({ t: T - 12 * H }, O({ wake: T - 400, fail: T - 320, busy: T - 5 })).st, 'stale');
});
test('réveil, requête sans réponse au-delà de l\'attente → STALE (noreply)', () => {
  assert.deepStrictEqual(L.rteFresh({ t: T - 12 * H }, O({ wake: T - 60, busy: T - 58 })), { st: 'stale', age: 12 * H, why: 'noreply' });
});
test('réponse reçue après le réveil → à jour', () => {
  assert.strictEqual(L.rteFresh({ t: T - 5 }, O({ wake: T - 30 })).st, 'fresh');
});
test('réveil 5 min après la dernière réponse : pas de mise à jour due → à jour, pas de clignotement', () => {
  assert.strictEqual(L.rteFresh({ t: T - 300 }, O({ wake: T - 2 })).st, 'fresh');
});
test('mise à jour périodique (11 min) en cours dans la session → reste à jour, sans clignotement', () => {
  assert.strictEqual(L.rteFresh({ t: T - 660 }, O({ busy: T - 1 })).st, 'fresh');
});
test('donnée plus vieille que le seuil (16 min), rien en cours → STALE (age)', () => {
  assert.deepStrictEqual(L.rteFresh({ t: T - 960 }, O()), { st: 'stale', age: 960, why: 'age' });
});
test('seuil réglable : 20 min de seuil, donnée de 16 min → à jour', () => {
  assert.strictEqual(L.rteFresh({ t: T - 960 }, O({ stale: 1200 })).st, 'fresh');
});
test('retour sur le format après 1 h, requête en cours → UPDATE IN PROGRESS (pas de STALE fugitif)', () => {
  assert.strictEqual(L.rteFresh({ t: T - H }, O({ wake: T - 2 * H, busy: T - 1 })).st, 'upd');
});
test('échec dans la session après la dernière réponse → STALE (fail)', () => {
  assert.strictEqual(L.rteFresh({ t: T - 300 }, O({ fail: T - 10 })).why, 'fail');
});
test('aucun résultat : en cours → upd ; échec → stale ; rien → none', () => {
  assert.strictEqual(L.rteFresh(null, O({ busy: T - 1 })).st, 'upd');
  assert.strictEqual(L.rteFresh(null, O({ fail: T - 1 })).st, 'stale');
  assert.strictEqual(L.rteFresh(null, O()).st, 'none');
});

// ------------------------------------------------------------------ trajet : durée habituelle, cohérence
console.log('ROUTE · USUAL / COHERENCE');
const tod = ts => ((ts % 86400) + 86400) % 86400;                  // UTC pour le test
const at = (day, h, m = 0) => 1_799_971_200 + day * 86400 + h * 3600 + m * 60;   // 1_799_971_200 = minuit UTC
const hist = {
  d1: [[at(1, 7, 40), 'hw', 1500, 1380], [at(1, 18, 10), 'hw', 2160, 1380], [at(1, 18, 0), 'wh', 1700, 1400]],
  d2: [[at(2, 7, 50), 'hw', 1560, 1380], [at(2, 22, 5), 'hw', 2200, 1380]],
  d3: [[at(3, 8, 20), 'hw', 1440, 1380]],
  d4: [[at(4, 7, 45), 'hw', 2000, 1380]],                            // aujourd'hui : écarté
};
test('médiane des jours précédents à ± 1 h', () => {
  const u = L.rteUsual(hist, 'hw', at(4, 7, 45), 'd4', tod);
  assert.strictEqual(u.how, 'SAME HOUR'); assert.strictEqual(u.n, 3); assert.strictEqual(u.dur, 1500);
});
test('pas assez de relevés à la même heure → médiane des 7 jours', () => {
  const u = L.rteUsual(hist, 'hw', at(4, 13, 0), 'd4', tod);
  assert.strictEqual(u.how, '7 DAYS'); assert.strictEqual(u.dur, 1560);
});
test('historique trop court → null', () => { assert.strictEqual(L.rteUsual({ d1: hist.d1 }, 'wh', at(4, 18), 'd4', tod), null); });
test('36 min pour 25 habituelles, sans incident → TRF', () => { assert.strictEqual(L.rteSuspect(2160, 1500, false, 25, 5), true); });
test('36 min mais incident signalé → rien', () => { assert.strictEqual(L.rteSuspect(2160, 1500, true, 25, 5), false); });
test('+4 min seulement → rien (seuil en minutes)', () => { assert.strictEqual(L.rteSuspect(1740, 1500, false, 10, 5), false); });
test('+20 % seulement → rien (seuil en %)', () => { assert.strictEqual(L.rteSuspect(1800, 1500, false, 25, 5), false); });

// ------------------------------------------------------------------ finances
console.log('FINANCE · SIGNALS (1.21 : urgences en CRIT, dépassements de budget en CAUT)');
test('montants en euros', () => {
  assert.strictEqual(L.parseEur('DÉCOUVERT −1 234,56 €'), 1234.56);
  assert.strictEqual(L.parseEur('solde -85.20 EUR'), 85.2);
  assert.strictEqual(L.parseEur('1.234 €'), 1234);
  assert.strictEqual(L.parseEur('sans montant'), null);
});
const acc = (label, balance) => ({ kind: 'current', label, balance });
test('solde négatif : CRIT, profondeur du compte nommé ; les INFO sont ignorées', () => {
  const s = L.finSignals({ accounts: [acc('COURANT', -120)], alerts: [{ level: 'crit', code: 'NEGATIVE_BALANCE', text: 'COURANT' }, { level: 'info', code: 'TO_REVIEW', text: '3 OPÉRATIONS À VOIR' }] });
  assert.strictEqual(s.length, 1); assert.deepStrictEqual([s[0].k, s[0].lvl, s[0].mag, s[0].cat], ['AL:NEGATIVE_BALANCE:COURANT', 2, 120, 'act']);
});
test('compte courant débiteur sans alerte Klad → CRIT quand même', () => {
  const s = L.finSignals({ accounts: [acc('COMPTE COMMUN', -40)] });
  assert.deepStrictEqual([s[0].k, s[0].lvl, s[0].mag], ['AL:NEGATIVE_BALANCE:COMPTE_COMMUN', 2, 40]);
});
test('débit imprévu sur le commun (alerte Klad 1.3.2, ou champ seul) → CRIT, compté en débits non vus', () => {
  const a = L.finSignals({ commun: { unplanned: { n: 2, amount: 184.3, unseen: 1 } }, alerts: [{ level: 'crit', code: 'COMMUN_UNPLANNED', text: '1 DÉBIT IMPRÉVU · COMMUN' }] });
  assert.deepStrictEqual([a.length, a[0].k, a[0].lvl, a[0].mag, a[0].u], [1, 'AL:COMMUN_UNPLANNED', 2, 1, 'n']);
  assert.strictEqual(L.finSignals({ commun: { unplanned: { n: 2, amount: 184.3, unseen: 2 } } })[0].mag, 2);
  assert.deepStrictEqual(L.finSignals({ commun: { unplanned: { n: 2, amount: 184.3 } } }), []);    // tous vus : plus d'alarme
});
test('ne remontent plus : CAUT de Klad (synchro, comptes à vérifier, découvert lointain), reste à vivre < 0', () => {
  const s = L.finSignals({ month: { left_to_live: -40 }, attention: { accounts_to_check: 2 },
    alerts: [{ level: 'caut', code: 'ACCOUNT_CHECK', text: '2 COMPTES À VÉRIFIER' }, { level: 'caut', code: 'SYNC_FAILED', text: 'COURANT' },
      { level: 'caut', code: 'OVERDRAFT_RISK', text: 'COMPTE COMMUN · 12/03' }, { level: 'caut', code: 'BANK_CONSENT_EXPIRING', text: 'COURANT · 20 J' }] });
  assert.deepStrictEqual(s, []);
});
test('remontent en CRIT : commun sous zéro sous 31 jours, accès banque à 3 jours, autre alerte crit', () => {
  const s = L.finSignals({ commun: { low: { date: '2026-11-20', amount: -230 } }, alerts: [{ level: 'crit', code: 'OVERDRAFT_RISK', text: 'COMPTE COMMUN · 20/11' },
    { level: 'crit', code: 'BANK_CONSENT_EXPIRING', text: 'COURANT · 2 J' }, { level: 'crit', text: 'Découvert de 120,00 €' }] });
  assert.deepStrictEqual(s.map(x => [x.k, x.lvl]), [['AL:OVERDRAFT_RISK', 2], ['AL:BANK_CONSENT_EXPIRING', 2], ['AL:DECOUVERT_DE_#', 2]]);
  assert.strictEqual(s[0].mag, 230); assert.strictEqual(s[2].mag, 120);
});
test('dépassement de budget : CAUT poste par poste ; acquitté → ne remonte plus', () => {
  const S1 = { month: { from: '2026-10-28', to: '2026-11-27' }, alerts: [{ level: 'caut', code: 'BUDGET_OVER', text: 'ALIMENTATION +12 %' }, { level: 'caut', code: 'BUDGET_OVER', text: 'LOISIRS +5 %' }] };
  const s = L.finSignals(S1);
  assert.deepStrictEqual(s.map(x => [x.k, x.lvl, x.mag, x.cat]), [['BO:ALIMENTATION', 1, 12, 'bud'], ['BO:LOISIRS', 1, 5, 'bud']]);
  assert.strictEqual(L.finBudKey('ALIMENTATION +40 %'), 'BO:ALIMENTATION');               // même clé quand le dépassement grandit
  const fb = { cycle: '2026-10-28', keys: ['BO:ALIMENTATION'] };
  assert.deepStrictEqual(L.finSignals(S1, L.finBudKeys(fb, S1)).map(x => x.k), ['BO:LOISIRS']);
  assert.deepStrictEqual(L.finBudKeys(fb, { month: { from: '2026-11-28' } }), []);           // nouveau cycle : acquittements effacés
});
test('les urgences passent avant les dépassements', () => {
  const s = L.finSignals({ accounts: [acc('COURANT', -10)], alerts: [{ level: 'caut', code: 'BUDGET_OVER', text: 'LOISIRS +5 %' }] });
  assert.deepStrictEqual(s.map(x => x.lvl), [2, 1]);
});

console.log('FINANCE · ACK');
const S = (bal, extra = []) => L.finSignals({ accounts: [acc('COURANT', bal)], alerts: [{ level: 'crit', code: 'NEGATIVE_BALANCE', text: 'COURANT' }, ...extra] });
const ack = L.finAckMake(S(-120), T, 48);
test('acquitté : silencieux, temps restant', () => {
  const e = L.finAckEval(ack, S(-120), T + 17 * H, 50);
  assert.strictEqual(e.st, 'paused'); assert.strictEqual(e.left, 31 * H);
});
test('découvert un peu plus profond (+30 €) → reste silencieux', () => { assert.strictEqual(L.finAckEval(ack, S(-150), T + H, 50).st, 'paused'); });
test('aggravation nette (+80 €) → retour immédiat', () => {
  const e = L.finAckEval(ack, S(-200), T + H, 50);
  assert.strictEqual(e.st, 'back'); assert.strictEqual(e.why, 'worse'); assert.strictEqual(e.delta, 80);
});
test('seuil d\'aggravation réglable (100 €) → +80 € reste silencieux', () => { assert.strictEqual(L.finAckEval(ack, S(-200), T + H, 100).st, 'paused'); });
test('nouvelle urgence d\'un autre type → retour immédiat', () => {
  const e = L.finAckEval(ack, S(-120, [{ level: 'crit', code: 'BANK_CONSENT_EXPIRING', text: 'COURANT · 1 J' }]), T + H, 50);
  assert.strictEqual(e.st, 'back'); assert.strictEqual(e.why, 'new'); assert.strictEqual(e.k, 'AL:BANK_CONSENT_EXPIRING');
});
test('un autre compte passe dans le rouge → retour immédiat', () => {
  const s = L.finSignals({ accounts: [acc('COURANT', -120), acc('COMPTE COMMUN', -15)], alerts: [{ level: 'crit', code: 'NEGATIVE_BALANCE', text: 'COURANT' }] });
  assert.strictEqual(L.finAckEval(ack, s, T + H, 50).why, 'new');
});
test('un débit imprévu de plus sur le commun → retour', () => {
  const u = n => L.finSignals({ commun: { unplanned: { n, amount: 50 * n, unseen: n } } });
  assert.strictEqual(L.finAckEval(L.finAckMake(u(1), T, 48), u(2), T + H, 50).why, 'worse');
  assert.strictEqual(L.finAckEval(L.finAckMake(u(1), T, 48), [], T + H, 50).st, 'cleared');      // vu dans Klad
});
test('nouveau dépassement de budget pendant la pause → retour (nouvelle alerte)', () => {
  assert.strictEqual(L.finAckEval(ack, S(-120, [{ level: 'caut', code: 'BUDGET_OVER', text: 'LOISIRS +5 %' }]), T + H, 50).why, 'new');
});
test('dépassement qui grandit : jamais « worse » (seul l\'acquittement par cycle compte)', () => {
  const b = p => L.finSignals({ alerts: [{ level: 'caut', code: 'BUDGET_OVER', text: 'LOISIRS +' + p + ' %' }] });
  assert.strictEqual(L.finAckEval(L.finAckMake(b(5), T, 48), b(60), T + H, 50).st, 'paused');
});
test('fin des 48 h, découvert toujours signalé → expired (l\'alarme revient)', () => {
  assert.strictEqual(L.finAckEval(ack, S(-120), T + 48 * H, 50).st, 'expired');
  assert.strictEqual(L.finAckEval(ack, S(-120), T + 48 * H - 1, 50).st, 'paused');
});
test('Klad ne signale plus rien → ACK effacé', () => { assert.strictEqual(L.finAckEval(ack, [], T + H, 50).st, 'cleared'); });
test('un signal disparu qui revient pendant la pause compte comme nouveau', () => {
  const two = L.finAckMake(L.finSignals({ accounts: [acc('A', -10), acc('B', -10)] }), T, 48);
  const e1 = L.finAckEval(two, L.finSignals({ accounts: [acc('B', -10)] }), T + H, 50);
  assert.strictEqual(e1.st, 'paused'); assert.deepStrictEqual(Object.keys(e1.sig), ['AL:NEGATIVE_BALANCE:B']);
  const trimmed = Object.assign({}, two, { sig: e1.sig });
  assert.strictEqual(L.finAckEval(trimmed, L.finSignals({ accounts: [acc('A', -10), acc('B', -10)] }), T + 2 * H, 50).why, 'new');
});
test('l\'ACK survit à un redémarrage (aller-retour JSON, comme le stockage)', () => {
  const back = JSON.parse(JSON.stringify(ack));
  assert.strictEqual(L.finAckEval(back, S(-120), T + 47 * H, 50).st, 'paused');
  assert.strictEqual(L.finAckEval(back, S(-120), T + 49 * H, 50).st, 'expired');
});

// ------------------------------------------------------------------ 1.20 : sommeil (nuit la plus longue, 18:00 → 18:00)
console.log('\nSLEEP · NIGHT FIND');
const D3 = 1_793_847_600;                      // un jour à 03:00 (heure locale du test sans importance : tout est relatif)
const mk = (from, to, step, f) => { const r = []; for (let t = from; t <= to; t += step) r.push(f(t)); return r; };
// relevés : immobile (g 0,001) sauf intervalles actifs [a, b] (mouvement 0,2 g, 20 pas / min)
const SM = (active, chg = []) => { let c = 1000; return mk(D3 - 10 * H, D3 + 16 * H, 60, t => { const on = active.some(([a, b]) => t >= a && t <= b); if (on) c += 20; return [t, c, on ? 0.2 : 0.001, chg.some(([a, b]) => t >= a && t <= b) ? 1 : 0]; }); };
test('nuit ordinaire 23:40 → 07:00', () => {
  const r = L.nightFind(D3, [], SM([[D3 - 9 * H, D3 - 3.33 * H], [D3 + 4 * H, D3 + 6 * H]]), null, D3 + 15 * H);
  assert.ok(r); assert.ok(Math.abs(r.bed - (D3 - 3.33 * H)) <= 120); assert.ok(Math.abs(r.wake - (D3 + 4 * H)) <= 120);
});
test('nuit décalée : debout toute la nuit, couché 07:05, levé 14:30 (le 1er novembre)', () => {
  const sm = SM([[D3 - 9 * H, D3 + 4.08 * H], [D3 + 11.5 * H, D3 + 13 * H]], [[D3 + 4.1 * H, D3 + 10.4 * H]]);
  const r = L.nightFind(D3, [], sm, null, D3 + 15 * H);
  assert.ok(r, 'nuit trouvée'); assert.ok(Math.abs(r.bed - (D3 + 4.08 * H)) <= 120); assert.ok(Math.abs(r.wake - (D3 + 11.5 * H)) <= 120); assert.ok(r.chg);
  assert.ok(L.nightShifted(r.bed, r.wake, D3));
});
test('coup d\'œil nocturne (1 min à 03:30) fondu dans la nuit', () => {
  const ev = [[D3 + 0.5 * H, 'UNLOCK'], [D3 + 0.5 * H + 50, 'OFF']];
  const r = L.nightFind(D3, ev, SM([[D3 - 9 * H, D3 - 3 * H], [D3 + 4 * H, D3 + 5 * H]]), null, D3 + 15 * H);
  assert.strictEqual(r.g, 1); assert.ok(Math.abs(r.bed - (D3 - 3 * H)) <= 120);
});
test('session à l\'alarme : réveil à l\'alarme même brève', () => {
  const al = D3 + 4 * H, ev = [[al + 10, 'UNLOCK'], [al + 70, 'OFF']];
  const r = L.nightFind(D3, ev, SM([[D3 - 9 * H, D3 - 3 * H], [D3 + 5 * H, D3 + 6 * H]]), al, D3 + 15 * H);
  assert.ok(Math.abs(r.wake - (al + 10)) <= 60);
});
test('soirée sans toucher le téléphone (3 h 30) : la vraie nuit gagne', () => {
  const sm = SM([[D3 - 9 * H, D3 - 8 * H], [D3 - 4.5 * H, D3 - 2 * H], [D3 + 4.5 * H, D3 + 6 * H]]);
  const r = L.nightFind(D3, [], sm, null, D3 + 15 * H);
  assert.ok(Math.abs(r.bed - (D3 - 2 * H)) <= 120);
});
test('nuit en cours (pas de réveil encore) → rien', () => {
  assert.strictEqual(L.nightFind(D3, [], SM([[D3 - 9 * H, D3 - 3 * H]]), null, D3 + 2 * H), null);
});

// ------------------------------------------------------------------ 1.20 : températures meteoblue / repli
console.log('\nWEATHER · TEMPERATURES');
const pT = s => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10), +s.slice(11, 13), 0);
const hours = mk(0, 47, 1, i => '2026-11-05 ' + String(i % 24).padStart(2, '0') + ':00').map((x, i) => i < 24 ? x : x.replace('11-05', '11-06'));
const MBJ = { data_1h: { time: hours, temperature: hours.map((_, i) => 10 + i * 0.1), felttemperature: hours.map((_, i) => 8 + i * 0.1),
    pictocode: hours.map((_, i) => i < 3 ? 22 : 33), precipitation: hours.map((_, i) => i < 3 ? 0 : 0.8), precipitation_probability: hours.map(() => 70), relativehumidity: hours.map(() => 88) },
  data_day: { time: ['2026-11-05', '2026-11-06'], temperature_max: [14.2, 15.1], temperature_min: [6.3, 7.4], pictocode: [6, 2], precipitation: [4.2, 0], precipitation_probability: [80, 5], precipitation_hours: [5, 0] } };
test('mbParse : heures, ressenti, ciel, pluie, humidité ; jours complets', () => {
  const S = L.mbParse(MBJ, pT);
  assert.strictEqual(S.ms.length, 48); assert.strictEqual(S.h.t[1], 10.1); assert.strictEqual(S.h.code[0], 3); assert.strictEqual(S.h.code[5], 61);
  assert.strictEqual(S.h.pr[5], 0.8); assert.strictEqual(S.h.rh[0], 88);
  assert.deepStrictEqual(S.days['2026-11-05'], { tmax: 14.2, tmin: 6.3, code: 63, psum: 4.2, pop: 80, ph: 5 });
  assert.strictEqual(S.days['2026-11-06'].code, 1);
});
test('mbParse : pictogramme inconnu → null (Open-Meteo gardé)', () => {
  const j = JSON.parse(JSON.stringify(MBJ)); j.data_1h.pictocode[0] = 99; assert.strictEqual(L.mbParse(j, pT).h.code[0], null);
});
test('mbParse : champs absents (température seule) → null, sans erreur', () => {
  const S = L.mbParse({ data_1h: { time: hours, temperature: hours.map(() => 5) } }, pT);
  assert.strictEqual(S.h.code, null); assert.strictEqual(S.h.pr, null);
});
test('mbParse : réponse sans data_1h → FORMAT', () => { assert.throws(() => L.mbParse({ metadata: {} }, pT), /FORMAT/); });
const OD = (tmax, tmin) => ({ tmax, tmin, code: 0, psum: 0, pop: 0, ph: 0 });
test('wxOverlay : maintenant interpolé ; heures (temp, ciel, pluie) et jours remplacés ; le reste gardé', () => {
  const S = L.mbParse(MBJ, pT), hms = [pT('2026-11-05 01:00'), pT('2026-11-05 05:00'), pT('2026-11-07 01:00')];
  const O = { temp: 0, feel: 0, code: 0, rh: 50, ht: [1, 1, 2], happ: [3, 3, 4], hcode: [0, 0, 0], hpr: [0, 0, 0], hpop: [0, 0, 9], days: [OD(20, 0), OD(21, 1), OD(22, 2)] };
  const r = L.wxOverlay(O, hms, ['2026-11-05', '2026-11-06', '2026-11-07'], S, pT('2026-11-05 01:00') + 1800000);
  assert.ok(Math.abs(r.temp - 10.15) < 1e-9); assert.deepStrictEqual(r.ht, [10.1, 10.5, 2]); assert.deepStrictEqual(r.hcode, [3, 61, 0]);
  assert.deepStrictEqual(r.hpr, [0, 0.8, 0]); assert.deepStrictEqual(r.hpop, [70, 70, 9]); assert.strictEqual(r.rh, 88);
  assert.deepStrictEqual(r.days[0], { tmax: 14.2, tmin: 6.3, code: 63, psum: 4.2, pop: 80, ph: 5 }); assert.deepStrictEqual(r.days[2], OD(22, 2));
});
test('wxOverlay : une valeur absente de la source garde celle d\'Open-Meteo', () => {
  const S = L.mbParse({ data_1h: { time: hours, temperature: hours.map(() => 5) } }, pT), hms = [pT('2026-11-05 01:00')];
  const r = L.wxOverlay({ temp: 0, feel: 1, code: 2, rh: 50, ht: [0], happ: [0], hcode: [2], hpr: [0.4], hpop: [30], days: [OD(9, 1)] }, hms, ['2026-11-05'], S, hms[0]);
  assert.strictEqual(r.temp, 5); assert.strictEqual(r.code, 2); assert.deepStrictEqual(r.hpr, [0.4]); assert.deepStrictEqual(r.days[0], OD(9, 1));
});
test('wxOverlay sans source : Open-Meteo inchangé', () => {
  const O = { temp: 5, feel: 4, code: 1, rh: 60, ht: [1], happ: [2], hcode: [1], hpr: [0], hpop: [0], days: [OD(3, 1)] };
  assert.deepStrictEqual(L.wxOverlay(O, [0], ['x'], null, 0), O);
});
test('serNear : heure la plus proche à 30 min près, sinon null', () => {
  const ms = [0, 3600000, 7200000], v = [1, 2, 3];
  assert.strictEqual(L.serNear(ms, v, 3500000), 2); assert.strictEqual(L.serNear(ms, v, 9500000), null);
});
test('quotaRoll : remise à zéro au changement de jour et de mois', () => {
  const c = { day: '20261104', n: 6, mon: '202611', nm: 150 };
  L.quotaRoll(c, '20261105'); assert.deepStrictEqual([c.n, c.nm], [0, 150]);
  L.quotaRoll(c, '20261201'); assert.deepStrictEqual([c.n, c.nm], [0, 0]);
});
test('quotaLvl : rien sous 80 %, CAUT dès 80 %, plafond à 100 %', () => {
  assert.deepStrictEqual([L.quotaLvl(79, 100), L.quotaLvl(80, 100), L.quotaLvl(100, 100), L.quotaLvl(5, 0)], [0, 1, 2, 0]);
});

// ------------------------------------------------------------------ 1.20 : autonomie face au coucher
console.log('\nBATTERY · TO BEDTIME');
test('drainRate : −6 %/h sur 1 h', () => { const r = L.drainRate(mk(0, 3600, 300, t => [t, 60 - t / 600])); assert.ok(Math.abs(r + 6) < 1e-9); });
test('drainRate : moins de 45 min → null', () => { assert.strictEqual(L.drainRate(mk(0, 1800, 300, t => [t, 50])), null); });
test('battPlan : 40 % à −6 %/h à 13:00, coucher 23:30 → vide ~19:40, brancher avant ~16:20', () => {
  const T = 13 * H, p = L.battPlan(40, -6, T, 23.5 * H, 20);
  assert.ok(Math.abs(p.empty - (T + 6.667 * H)) < 60); assert.ok(Math.abs(p.by - (T + 3.333 * H)) < 60);
});
test('battPlan : tient jusqu\'au coucher → null', () => { assert.strictEqual(L.battPlan(80, -5, 13 * H, 23.5 * H, 20), null); });

console.log('\n' + ok + ' ok · ' + ko + ' failed');
process.exit(ko ? 1 : 0);
