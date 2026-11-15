'use strict';
// The decisions behind the notifications, with no Firebase in them so they can be tested on their own.

const MIN_COOLDOWN_MS = 60 * 60 * 1000; // a floor the phone cannot go under, whatever its settings say

function clean(name) {
  return String(name || '').replace(/[\u0000-\u001f\u007f]/g, '').replace(/(https?:\/\/|www\.)\S+/gi, '').trim().slice(0, 24);
}

function announcement(name, state) {
  const n = clean(name) || 'A friend';
  return state === 'running' ? `${n} is going for a run. Join them?` : `${n} is going for a walk. Join them?`;
}

/**
 * Who to tell about a new announcement by `walker`. `pairs` are the walker's pair documents ({id, members, status, tell, mute,
 * lastAnnounce}); `tokens` maps a uid to that person's push tokens. Returns [{friend, tokens, pairId}].
 * A friend is told only if: the pair is accepted, the walker chose to tell them, they have not muted the walker, the walker has
 * not told them within the cooldown, and they have at least one token.
 */
function recipients(walker, pairs, tokens, now, cooldownMs = MIN_COOLDOWN_MS) {
  // `tokens` null means "do not check tokens yet": used to find who could be told before fetching anyone's tokens.
  const out = [];
  for (const p of pairs) {
    if (p.status !== 'accepted' || !Array.isArray(p.members) || !p.members.includes(walker)) continue;
    const friend = p.members.find((m) => m !== walker);
    if (!friend) continue;
    if (!(p.tell && p.tell[walker] === true)) continue;
    if (p.mute && p.mute[friend] === true) continue;
    const last = (p.lastAnnounce && p.lastAnnounce[walker]) || 0;
    if (now - last < Math.max(cooldownMs, MIN_COOLDOWN_MS)) continue;
    const t = tokens === null ? [] : (tokens[friend] || []).filter((x) => typeof x === 'string' && x.length > 20);
    if (tokens !== null && !t.length) continue;
    out.push({ friend, tokens: t, pairId: p.id });
  }
  return out;
}

/** Whether a write to a walk document is a new announcement (a new id, with a state, and not an update of the same walk). */
function isNewAnnouncement(before, after) {
  if (!after || !after.announceId || !after.state) return false;
  return !before || before.announceId !== after.announceId;
}

module.exports = { MIN_COOLDOWN_MS, clean, announcement, recipients, isNewAnnouncement };
