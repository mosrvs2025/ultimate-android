# Launcher looks (2.30.0; living looks and wallpaper colours 2.31.0; home cards 2.32.0)

Code: `launcher/LauncherLook.kt` (parts, tokens, presets, share code), `launcher/LauncherClock.kt` (the clock layouts),
`launcher/LauncherScreen.kt` (favourites layouts, app-row variants, rail styles), `launcher/LauncherSettings.kt` (the settings and
their storage), `launcher/LauncherSettingsSheet.kt` (the Themes page).

## A look is parts, not a colour

Before, a "theme" was two accent colours and a background; every theme was the same arrangement in different paint. Now a look is
a set of parts, each its own setting, and a preset sets them all at once:

| Part | Choices |
| --- | --- |
| Colours | `Accent` (the pair of colours): Aurora, Sunset, Ocean, Meadow, Rose, Ember, Phosphor, Amber, Mono, Ink |
| Background | `Backdrop`: Aurora, Black, Graphite, Midnight, Dusk, Terminal, Paper, the phone's wallpaper. Each carries its text colours too. |
| Typeface | Default, Clean (sans), Serif, Mono |
| Clock | Big day (the original), Digital, Terminal, Editorial, None |
| Favourites | List (the original), Numbered, Grid, Pills, Centered |
| Letter rail | Liquid (the original), Plain letters, Hidden until touched |
| Icon shape | As they are, Circle, Squircle |

Presets (`Looks.all`): **Aurora** (the original; the default settings *are* Aurora, so nobody's launcher changes), **Terminal**,
**Paper**, **Zen**, **Tiles**, **Dusk**. Any single part changed afterwards makes a look of your own (`Looks.matching` returns null
when the parts match no preset; how many favourites show is deliberately not part of what makes a look).

## How it is drawn

Everything the launcher draws reads `LookTokens` (`LocalLook`) instead of hard-coded white: the main and quiet text colours, whether
the background is light, the search panel's colour, the typeface, the rail style, the icon shape. Two consequences worth knowing:

- **A light look** (Paper) has dark text, and the status bar and navigation bar icons are switched to dark with it
  (`WindowCompat`). The search panel is dark whatever the look, so there a light look's text becomes paper-coloured and its dark
  accent becomes amber (`LookTokens.onSheet`).
- **The edge rail** (the rail that slides over other apps) sits on a dark scrim, so it always keeps light text; it takes only the
  look's typeface, icon shape and rail style (a hidden rail would be invisible over another app, so it is drawn as plain letters).

New settings are stored as plain strings under the existing `x.<name>` keys, so an older install's settings and a value a later
version adds both fall back to the default instead of failing (`SettingsCodec.extras` / `withExtras`).

## Sharing a look

A look as one line of text: `ULOOK1:Phosphor|Terminal|Mono|Terminal|Numbered|Plain|AsIs|6|false` (accent, background, typeface,
clock, favourites, rail, icon shape, favourites shown, icons in the lists). Copy it on the Themes page, paste it on another phone.
Only the look travels: never apps, hidden apps or gestures. Reading is forgiving (an unknown name keeps what was there; a
non-look text is refused).

## How it was checked

Unit tests: every preset is different and recognised once applied; the default settings are the Aurora look; each look's
settings store and read back; text contrast on every background (main text 7:1, quiet 3:1) and on the search panel for every look;
share codes round-trip and junk is refused. And, new in this release, **the renderings were looked at**: `screenshots.yml` (a
by-hand workflow) renders the Compose screenshot tests on GitHub's runners and attaches the images. Looking at them found four
real problems that no unit test would have: Zen's clock was left-aligned over centred favourites, Terminal's date wrapped awkwardly,
the look-card previews drew hard squares, and (the important one) in Paper the search results were dark ink on a dark panel.

## Not yet verified

On a phone: how a look feels under the thumb (the rail styles above all), the dark status-bar icons in Paper, and every look with
real icons (the renderings use sample apps with placeholder discs).

## Ideas this leaves room for

Looks that change by themselves (by time of day, Focus, activity, a rule); home cards that show Ultimate's own data (activity
rings, heart rate, a mesh camera); "match my wallpaper" colours; any colour, not just the presets; a look editor with saved slots.


## Living looks (2.31.0)

Code: `launcher/LivingLook.kt` (rules, all plain arithmetic), `health/ActivityGlance.kt` (today's step count, for the goal rule),
`LauncherRepo.living` / `tick()` / `refreshSignals()`.

A **rule** is "when" plus "which look": a time window (`Between(from, to)` in minutes after midnight, wrapping midnight when `from > to`,
so 22:00 to 06:00 works; `from == to` never applies), **while a focus session runs** (running and not paused), or **once today's step
goal is reached** (until midnight). The look is a preset id (`zen`) or a whole `ULOOK1:` code, so a look of your own can be used.

- The look you pick yourself stays your **everyday look** (`repo.settings`). `repo.living` is that with the first applying rule's look on top;
  the home screen and the edge rail draw `living`, the Themes page edits `settings`. A rule changes only the look's parts: hidden apps,
  gestures, favourites and the rules themselves are never touched.
- Rules are checked top to bottom, the first that applies wins; a rule whose look can't be read is skipped. Up to six rules; the Themes
  page has one-tap starting points (Zen at night, Paper by day, Terminal while focusing, Dusk when the goal is reached), move up/down and
  remove, and a small builder with 30-minute steppers.
- The signals are read every 30 seconds while the home screen is up (and each time you come home): the clock, `UltimateApp.focus.current`,
  and `ActivityGlance.goalReached`. The step count is read from Health Connect at most every five minutes, only when a goal rule exists,
  and only if the steps permission was already given (the launcher never asks); with no permission the goal simply never "is reached".
- Stored as one `x.rules` string (`t1320-360@zen` per line; `focus@terminal`; `goal@dusk`). Damaged or future-version lines are dropped,
  the rest kept.

## Colours from the wallpaper (2.31.0)

Code: `launcher/WallpaperPalette.kt`, `LauncherRepo.watchWallpaper()`.

`Accent.Wallpaper` ("From wallpaper") takes the colours the phone itself found in the wallpaper (`WallpaperManager.getWallpaperColors`:
primary, secondary, tertiary) and picks two: the most colourful leads, the second is a clearly different hue (or the first turned 38 degrees
when the picture has only one colour). Wallpaper colours are often dark or washed out, so each is moved in lightness, keeping its hue,
until it is at least **4.5:1** against the lightest dark background (and against Paper's page on a light look). The result lives in
`WallpaperInk` as Compose state, so the glow, rail and highlights change the moment the wallpaper (or a look's background) changes. With no
wallpaper colours it is the original teal and violet. It is shareable in a look code (the other phone uses its own wallpaper).
The **Mirror** look pairs it with the phone's wallpaper as the background.


## Home cards (2.32.0)

Code: `launcher/HomeCards.kt` (the cards and their wording), `health/HeartGlance.kt` (the heart read and its words),
`LauncherRepo.cards` / `refreshCards()`, `LauncherSettings.cards`.

Three cards under the clock, each **off until switched on** (Settings, Home screen, Cards; stored as `x.cards`, so an old install has none):

| Card | Shows | Tap opens |
| --- | --- | --- |
| Activity | the three rings, today's steps, active time, and "+1,200 vs usual" against your usual day (`ActivityMath`) | Timeline, Activity tab (`EXTRA_SOURCE` = `activity`) |
| Heart | the newest heart rate if it is under 6 h old, else the resting rate, with how old it is | Health |
| Cameras | "Kitchen camera" or "2 cameras", who is sharing, and how many are being watched from this phone | Mesh |

- Drawn in the look being worn (text colours, typeface, accent), as small rounded blocks with ellipses instead of clipping. A card with
  nothing to say (no camera sharing) is not drawn.
- The launcher **never asks for permission**. Activity reads through `ActivityRepo`; Heart through `HeartGlance` (newest sample in 3 days,
  newest resting rate in 4 days, kept for two minutes). Without Health Connect or the permission the card says "Tap to allow ..." and a tap
  opens the matching page. Cameras reads `CamNodes.list()` (the owner's own mesh devices that chose to share), never frames, and is empty
  in editions without the mesh.
- The cards refresh about every minute while the home screen is up, only for the cards that are on.

Checked: `HomeCardsTest` (wording, short pace line, camera lines, storage), and renders of all three cards in Aurora, Terminal and Paper,
which led to shorter copy. Not checked on a phone with real data.
