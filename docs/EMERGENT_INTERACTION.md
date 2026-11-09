# Emergent interaction (a design direction, not a field)

The idea: with 38 tools and a dozen senses, some useful interactions exist only in the *combination*, and nobody has
written them down. The job is to let the app find them, show why, and ask before acting, instead of shipping each one as a
hand-built feature.

## What this branch builds: the discovery mechanism

`context/ComboMiner.kt`. Given a timeline of signals and actions (`watch:flick_right`, `echo:approach`, `spatial:watch_near`,
`open:player`, ...), it reports "X (and Y) shortly before Z" when that happens

- many times (default at least 5) and on more than one day,
- far more often than chance (chance is measured per outcome from its own rate; default lift at least 4), and
- reliably (default at least 60% of the time X occurred).

A pair of antecedents is reported as **emergent** only when together they predict the outcome at least 25 points better than
the better of the two alone. That is the formal version of "a combination no single feature contains". Every result carries
its support, confidence, lift, typical delay and the confidence of its best part, so it can say *why*.

It is plain counting. No model, no network, nothing stored by this class.

### Measured (synthetic data; `ComboMinerTest`, `app/build/combo-benchmark.txt`)

12 made-up months each: 220 unrelated events a day over 14 signals and 12 tools, with and without two planted interactions
(a deliberate right flick then the player opens, 85%; watch near, then something approaches, then the orb opens, 90%; each
part of the pair also occurs on its own all the time).

| | Result |
| --- | --- |
| Planted single interaction found | 11 of 12 |
| Planted pair found and flagged emergent | 12 of 12 |
| Discoveries in 12 months of pure noise | 0 |
| Unplanted discoveries beside the planted ones | 0 |

Two honest observations from building it:

1. **A first version of the test found the single interaction in 0 of 12.** The flick also occurred in many unrelated
   moments, so only about a quarter of flicks led to the player, and the miner rightly refused to call that a rule. The
   test was then changed so the flick is a rare, deliberate gesture. The lesson is real: a signal that fires in many contexts
   does not yield a rule on its own, but *combined with a second signal it often does*, because the pair is rare in noise.
   That is where emergent combinations will mostly come from.
2. The planted structure is mine. This shows the miner finds what a clean combination looks like and does not invent any from
   noise. It does not show that your phone has such combinations, or how many.

## What is not built (and why)

- **No event log feeds it.** The senses bus keeps only the last 100 signals in memory. To mine, signals and opens must be
  recorded with their times, behind the same opt-in as "Learn my habits", on the phone only, capped, with Forget. That is
  the next step and it needs your decision on what is recorded.
- **No screen shows discoveries, and nothing acts on them.** The intended behaviour is a card ("You nearly always open the
  player a few seconds after a right flick. Make that a rule?") and a one-tap rule only when Rules can express the action.
  I have not checked which discovered outcomes Rules can perform today; opening a specific tool may not be one of them.
- **The Adaptive Spatial Controller is a design only.** The mechanism that would make it emergent rather than hand-wired:
  tools declare their *controllable parameters* (id, range, step: the player's volume and position, Studio's playhead, Air's
  zoom), a broker binds a continuous body signal (wrist roll from the watch, hand height from the camera) to the foreground
  tool's primary parameter, and `IntentEngine` decides when to engage (for example camera sees a raised hand and the watch
  confirms). The surface exists only while the situation says so and disappears after idle. Needs a parameter registry
  across tools (a refactor of each tool) and real-hardware tuning I cannot do here.

## Guard rails

Discoveries are suggestions, never actions. Nothing is recorded without the switch; Forget deletes the log; a discovery can
be dismissed with a reason ("not when charging") that becomes negative evidence.
