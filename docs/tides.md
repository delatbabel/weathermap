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

## 2. Choose a point

A tide belongs to a spot, not to a rectangle, so the window opens on the
**middle of the area selected on the map**. That is usually the right guess;
when it is not, the latitude and longitude are fields, not a readout — type a
new pair and press **Show tides**.

Storm Glass answers from the nearest gauge it has and says which, and how far
away, under the title. Worth reading: "the tide here" from a station sixty
kilometres up the coast is a weaker claim than one from four kilometres away,
and the chart cannot tell you which you have unless you look.

## 3. Read the chart

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

The arrows at the top right move through the days. A fortnight is fetched at
once, so paging is instant and free.

## 4. Take it away

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

So the application asks for a **fortnight at a time** and keeps the answer for
**24 hours**. Within that day, that place costs nothing again however many times
you look at it or page through it, and the status line says
`cached at 09:12 - no request made` when nothing was spent. When a request *is*
made it reports what the service says is left: `4 of 10 requests used today`.

Coordinates are rounded to three decimals — about a hundred metres, far finer
than stations are spaced — before the request is built, so that nudging the map
selection does not quietly buy the same fortnight twice.

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
