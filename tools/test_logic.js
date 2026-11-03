// Tests de la logique pure de Grafit Mobile (bloc « // <logic> … // </logic> » de index.html).
// Lancer : node tools/test_logic.js   (Node 18 ou plus, aucune dépendance)
'use strict';
const fs = require('fs'), path = require('path'), assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const m = html.match(/\/\/ <logic>\n([\s\S]*?)\/\/ <\/logic>/);
if (!m) { console.error('bloc // <logic> introuvable dans index.html'); process.exit(1); }
const L = new Function('"use strict";\n' + m[1] + '\nreturn { rteFresh, rteUsual, rteSuspect, parseEur, finSignals, finAckMake, finAckEval };')();

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
console.log('FINANCE · SIGNALS');
test('montants en euros', () => {
  assert.strictEqual(L.parseEur('DÉCOUVERT −1 234,56 €'), 1234.56);
  assert.strictEqual(L.parseEur('solde -85.20 EUR'), 85.2);
  assert.strictEqual(L.parseEur('1.234 €'), 1234);
  assert.strictEqual(L.parseEur('sans montant'), null);
});
const S0 = { accounts: [{ kind: 'current', label: 'CC', balance: -120 }], alerts: [{ level: 'caut', code: 'OVERDRAFT', text: 'Compte courant à découvert' }, { level: 'info', code: 'SYNC_OK', text: '' }] };
test('découvert sans montant : profondeur du solde négatif ; les INFO sont ignorées', () => {
  assert.deepStrictEqual(L.finSignals(S0), [{ k: 'AL:OVERDRAFT', lvl: 1, mag: 120, u: 'eur' }]);
});
test('alerte sans code : clé stable quand le montant change', () => {
  const a = L.finSignals({ alerts: [{ level: 'caut', text: 'Découvert de 120,00 €' }] })[0];
  const b = L.finSignals({ alerts: [{ level: 'caut', text: 'Découvert de 260,00 €' }] })[0];
  assert.strictEqual(a.k, b.k); assert.strictEqual(a.mag, 120); assert.strictEqual(b.mag, 260);
});
test('reste à vivre négatif et comptes à vérifier', () => {
  const s = L.finSignals({ month: { left_to_live: -40 }, attention: { accounts_to_check: 2 } });
  assert.deepStrictEqual(s, [{ k: 'LTL', lvl: 1, mag: 40, u: 'eur' }, { k: 'ACC', lvl: 1, mag: 2, u: 'n' }]);
});

console.log('FINANCE · ACK');
const S = (bal, extra = []) => L.finSignals({ accounts: [{ kind: 'current', balance: bal }], alerts: [{ level: 'caut', code: 'OVERDRAFT', text: '' }, ...extra] });
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
test('nouvelle alerte d\'un autre type → retour immédiat', () => {
  const e = L.finAckEval(ack, S(-120, [{ level: 'caut', code: 'BANK_CONSENT', text: '' }]), T + H, 50);
  assert.strictEqual(e.st, 'back'); assert.strictEqual(e.why, 'new'); assert.strictEqual(e.k, 'AL:BANK_CONSENT');
});
test('CAUT → CRIT → retour immédiat', () => {
  const crit = L.finSignals({ accounts: [{ kind: 'current', balance: -120 }], alerts: [{ level: 'crit', code: 'OVERDRAFT' }] });
  assert.strictEqual(L.finAckEval(ack, crit, T + H, 50).why, 'crit');
});
test('fin des 48 h, découvert toujours signalé → expired (l\'alarme revient)', () => {
  assert.strictEqual(L.finAckEval(ack, S(-120), T + 48 * H, 50).st, 'expired');
  assert.strictEqual(L.finAckEval(ack, S(-120), T + 48 * H - 1, 50).st, 'paused');
});
test('Klad ne signale plus rien → ACK effacé', () => { assert.strictEqual(L.finAckEval(ack, [], T + H, 50).st, 'cleared'); });
test('un signal disparu qui revient pendant la pause compte comme nouveau', () => {
  const two = L.finAckMake(L.finSignals({ month: { left_to_live: -40 }, attention: { accounts_to_check: 1 } }), T, 48);
  const e1 = L.finAckEval(two, L.finSignals({ attention: { accounts_to_check: 1 } }), T + H, 50);
  assert.strictEqual(e1.st, 'paused'); assert.deepStrictEqual(Object.keys(e1.sig), ['ACC']);
  const trimmed = Object.assign({}, two, { sig: e1.sig });
  assert.strictEqual(L.finAckEval(trimmed, L.finSignals({ month: { left_to_live: -40 }, attention: { accounts_to_check: 1 } }), T + 2 * H, 50).why, 'new');
});
test('un compte à vérifier de plus → retour', () => {
  const a2 = L.finAckMake(L.finSignals({ attention: { accounts_to_check: 1 } }), T, 48);
  assert.strictEqual(L.finAckEval(a2, L.finSignals({ attention: { accounts_to_check: 2 } }), T + H, 50).why, 'worse');
});
test('l\'ACK survit à un redémarrage (aller-retour JSON, comme le stockage)', () => {
  const back = JSON.parse(JSON.stringify(ack));
  assert.strictEqual(L.finAckEval(back, S(-120), T + 47 * H, 50).st, 'paused');
  assert.strictEqual(L.finAckEval(back, S(-120), T + 49 * H, 50).st, 'expired');
});

console.log('\n' + ok + ' ok · ' + ko + ' failed');
process.exit(ko ? 1 : 0);
