'use strict';
const { onDocumentWritten, onDocumentCreated } = require('firebase-functions/v2/firestore');
const admin = require('firebase-admin');
const { recipients, isNewAnnouncement, announcement, clean } = require('./plan');

admin.initializeApp();
const db = admin.firestore();

async function tokensOf(uids) {
  const out = {};
  await Promise.all(uids.map(async (u) => { const s = await db.doc(`private/${u}`).get(); out[u] = (s.exists && s.data().tokens) || []; }));
  return out;
}

// A walker announces a walk (a new announceId on their walk document): tell the friends they chose.
exports.announceWalk = onDocumentWritten('walks/{uid}', async (event) => {
  const before = event.data.before.exists ? event.data.before.data() : null;
  const after = event.data.after.exists ? event.data.after.data() : null;
  if (!isNewAnnouncement(before, after)) return;
  const walker = event.params.uid;
  const snap = await db.collection('pairs').where('members', 'array-contains', walker).where('status', '==', 'accepted').get();
  const pairs = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
  const now = Date.now();
  // Tokens are fetched only for friends who pass the cheap checks (accepted, chosen, not muted, not told recently).
  const candidates = recipients(walker, pairs, null, now).map((r) => r.friend);
  if (!candidates.length) return;
  const tokens = await tokensOf(candidates);
  const who = recipients(walker, pairs, tokens, now);
  if (!who.length) return;
  const user = await db.doc(`users/${walker}`).get();
  const name = clean((user.exists && user.data().name) || '');
  const body = announcement(name, after.state);
  for (const r of who) {
    const res = await admin.messaging().sendEachForMulticast({
      tokens: r.tokens,
      notification: { title: 'Ultimate', body },
      data: { type: 'walk', from: walker, name, state: String(after.state) },
      android: { priority: 'high', notification: { channelId: 'friends-walks' } },
    });
    // Drop tokens that no longer exist, so they are not tried again.
    const dead = r.tokens.filter((_, i) => !res.responses[i].success && /registration-token-not-registered|invalid-argument/.test((res.responses[i].error || {}).code || ''));
    if (dead.length) await db.doc(`private/${r.friend}`).set({ tokens: admin.firestore.FieldValue.arrayRemove(...dead) }, { merge: true }).catch(() => {});
    await db.doc(`pairs/${r.pairId}`).update({ [`lastAnnounce.${walker}`]: now });
  }
});

// A friend taps Join: tell the walker.
exports.joinedWalk = onDocumentCreated('walks/{uid}/joins/{friend}', async (event) => {
  const walker = event.params.uid; const friend = event.params.friend;
  const [t, u] = await Promise.all([tokensOf([walker]), db.doc(`users/${friend}`).get()]);
  const tokens = (t[walker] || []).filter((x) => typeof x === 'string' && x.length > 20);
  if (!tokens.length) return;
  const name = clean((event.data.data() || {}).name || (u.exists && u.data().name) || '') || 'A friend';
  await admin.messaging().sendEachForMulticast({
    tokens, notification: { title: 'Ultimate', body: `${name} is joining your walk` },
    data: { type: 'join', from: friend, name }, android: { priority: 'high', notification: { channelId: 'friends-walks' } },
  });
});
