# Tide charts

**Tide → Tide chart…** opens a chart of the water at one point: the curve for a
day, the highs and lows marked on it, and the times and heights written
underneath.

## 1. Get a key

Tide predictions come from [Storm Glass](https://stormglass.io), which needs an
API key. Free keys come from
[dashboard.stormglass.io](https://dashboard.stormglass.io/register).

Put it in **File → Preferences → Storm Glass API key**. It is stored in
`~/.weathermap/preferences.properties`, which the application writes readable by
you only. Opening the tide window without a key offers to take you there.

## 2. Choose the place, on the map

A tide belongs to a spot, not to a rectangle, so the first step is on the map
rather than in a dialog. **Tide → Tide chart…** hands the map over: scroll and
zoom to the harbour, anchorage or stretch of coast you want, and **click it**.
Panning, zooming and the wheel all keep working while it waits; **Esc**
abandons it.

A click raises a question before anything is fetched:

> 10.3510°N 107.1120°E
> Nearest place in the OSM data:
> **Vũng Tàu (town) — 3.1 km away**
> Storm Glass will be asked for 10 days of tide here, which costs 2 of today's
> requests.

The name is the point of it. A latitude and a longitude are exact and
unreadable — 10.35, 107.11 is a harbour, a headland or forty kilometres of open
water, and only one of those is worth a chart. A name and a distance can be
checked at a glance, and nothing is spent until you say so.

Two things the question tells you about the data itself:

- **"Nearest place in the bundled world gazetteer"** means the only names here
  are the major cities shipped with the application, so the nearest *real*
  place may be round the corner and simply absent. Press **Load detail** and
  click again.
- **"That is a long way off"** on an OSM name means what it says: the point is
  well away from anything named, and more data will not change that.

Zoomed in below 8° the application fetches OSM place names for what you are
looking at on its own, so by the time you have found a harbour the names are
usually already there.

## 3. Correct it, if the click was off

The latitude and longitude in the tide window are fields, not a readout. Type a
new pair and press **Show tides**. The heading drops the place name when the
numbers stop being the ones it was given for, rather than captioning a new spot
with the old name.

Storm Glass answers from the nearest gauge *it* has, which is a different thing
from the nearest town, and says which and how far away under the title. Worth
reading: "the tide here" from a station sixty kilometres up the coast is a
weaker claim than one from four kilometres away.

## 4. Read the chart

| On the chart | What it is |
|---|---|
| The filled curve | Sea level hour by hour, drawn through the real turning points rather than only the hourly samples |
| ● marks | High and low water, at the minute |
| The dashed red line | Now, when the day being shown contains it |
| Under the chart | One card per turning point: time and height |

Heights are **relative to a datum** — `MSL`, mean sea level, unless Storm Glass
says otherwise, and the chart prints which. Against `MSL` a low tide is a
negative number. They are never water depth.

Times are written in the zone set under **File → Preferences → Time zone**, the
same one the weather charts use.

The arrows at the top right move through the days. Ten days are fetched at
once, so paging is instant and free.

## 5. Take it away

**Copy** puts the chart on the clipboard; **Save PNG…** writes it to the output
folder as `tide-YYYY-MM-DD.png`. The figures are drawn *on* the chart rather
than only shown beside it in the window, so a chart that gets shared still
carries its own numbers.

The image is produced at the size set under **Output size** in Preferences —
the width is taken from it and the height derived, because a tide chart is wide
and short where a map is not.

## The daily request limit

**This is the thing to know about tide charts.** Storm Glass counts every
request against a daily quota, and the free tier allows **ten a day**. One chart
costs **two** — the curve and the turning points are separate endpoints, and
neither can be derived from the other.

So the application asks for **ten days at a time** and keeps the answer for
**24 hours**. Within that day, that place costs nothing again however many times
you look at it or page through it, and the status line says
`cached at 09:12 - no request made` when nothing was spent. When a request *is*
made it reports what the service says is left: `4 of 10 requests used today`.

Coordinates are rounded to three decimals — about a hundred metres, far finer
than stations are spaced — before the request is built, so that nudging the map
selection does not quietly buy the same ten days twice.

What this means in practice: **a different harbour costs two requests, the same
harbour costs nothing.** Six places in a day is the free tier gone.

If it does run out, Storm Glass answers `402`, which the application reports as
*"daily request limit reached"* rather than as "Payment Required" — nothing
needs buying, and it clears at midnight UTC. Charts already cached keep working.

## Astronomical tide only

Storm Glass publishes the *astronomical* tide: what the sun and moon do to sea
level. It is not the only thing that moves water. Atmospheric pressure, wind and
a storm surge can each push the real level well away from the prediction, and
this chart will not know. On a day with weather in it, read the tide chart
beside the pressure chart rather than instead of it.
