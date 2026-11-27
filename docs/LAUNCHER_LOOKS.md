# Launcher looks (2.30.0)

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
