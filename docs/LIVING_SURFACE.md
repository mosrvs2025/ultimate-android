# Living command surface

The first native implementation centers Home on an orb and a shallow depth scene.
It builds on the existing orb service, terminal renderer fix, Rules and SSH work
through Claude commit `e131976` without changing Claude's branch.

## Interaction

- Home shows six nearby tools, or three in windows narrower than 340 dp after
  surface padding. Tap a tool to open it; hold it to open its results.
- Tap the central orb to reveal live headlines and recent shortcuts. Android Back
  closes those shortcuts first. Hold the orb for its settings.
- Inside a tool, the draggable orb carries that tool's icon and media/system accent.
  Tap for nearby tools, the current tool's results, Activity, Home, and a cancel
  action when it has a live job. “All tools” opens the scrollable catalog.
  Hold the floating orb to return Home. The labeled bottom navigation remains an
  accessible fallback.
- Opening through the floating orb slides from the side it rests on. Opening from
  Home rises from the surface. Returning reverses the same direction; visits
  shorter than eight seconds use a shorter return transition.
- Tool families below the surface start collapsed and remember which you expand.

## Memory and motion

Ranking uses up to 512 opens within the last 48 hours, with recency and an observed
similar-time-of-day bonus. Running tools take precedence. Tools never opened use
a deterministic starter order, with an explicit first-use explanation. There are
no claims about replacing another app or invented time-of-day recommendations.
History stays in the existing local Insights preferences.

Failed jobs recorded by WorkService leave a persistent “unfinished job” reminder
on Home. Opening it returns to the tool for review; it does not restart work
without input. Clear dismisses only the reminder; a successful job also clears it.
Cancel paths that report success rather than failure do not produce reminders.

Tilt uses the gravity sensor, is bounded to a few dp, accounts for display
rotation, and registers only while Home is resumed. The Tilt switch remembers its
setting. Turning system animations off disables tilt on the next registration;
Android's animation scale also governs Compose transitions. Missing sensors
leave the surface still.

## Scope and verification

This is native Compose depth, lighting and parallax, not a Three.js scene or a
physically tracked 3D workspace. Small-window adaptation does not identify a
foldable hinge or activate a manufacturer's cover display. Cover-screen launch,
fold/unfold continuity, hardware haptic feel, and motion comfort need device
checks. The system overlay remains opt-in with Android's existing permission.
Tool-specific job vibration patterns, displacement indicators, custom font
assets, and a global replacement of bottom navigation are not implemented.

Automated coverage includes persistence/expiry of history, time-of-day ranking,
active-job priority, failure-reminder clearing, compact Home interaction, rendered
Home, and orb/catalog access. Terminal device smoke tests remain available in CI.

## Try without replacing an existing install

`gradle -PorbitPreview=true :app:assembleUltimateplusRelease` builds a separate
`app.ultimate.orbitpreview` app labeled **Ultimate Orbit Preview**. It starts with
its own empty preferences and files, so it learns independently of the installed
Ultimate/Ultimate+ app. Local builds use the debug signing fallback; the APK is a
preview rather than a production update. Permissions are granted separately.
