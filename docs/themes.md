# Themes

Three choices in the **Appearance** menu, switchable without a restart: FlatLaf
**Light**, FlatLaf **Dark**, and **System** — the platform look and feel, for
anyone who would rather the application stopped having opinions and matched the
rest of their desktop. The choice is saved as soon as it is made rather than on
close, since someone who switches theme and then kills the window has still
expressed a preference.

The two FlatLaf themes share their metrics, so switching between them changes
only colours. System does not: the platform look and feel has its own fonts and
insets, and controls genuinely change the size they ask for. Every open window
is therefore revalidated after a switch, not just repainted — which works
because the panels use layout managers rather than absolute positions.

**The map does not follow the theme, and that is deliberate.** It is drawn by
the same `Compositor` that writes the PNG, so theming it would either fork the
renderer — the one thing this design refuses, since the point is that what is
seen and what is saved cannot differ — or make the command-line tool's output
depend on a setting in a window it never opens. A chart is a document: it gets
saved, printed and read beside paper ones, and its colours answer to the
conventions of a weather chart rather than to the time of day. So the map stays
a light page in a dark room. The chrome around it — the letterboxing, the hint
pill — does follow the theme.
