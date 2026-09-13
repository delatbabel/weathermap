# Series, profiles and chart times

What to fetch, how to name it, and how the result is labelled.

## A series of charts

A chart every *n* hours covering *m* hours from now — three and forty-eight by
default. `--series 3,48` on the command line, or the **Series from now** row in
the window, which disables the explicit forecast-hours field because a series
and a list of hours are two answers to the same question.

It can start in the past: `--series 6,24,48` covers twenty-four hours back as
well as forty-eight ahead, up to `MAX_SERIES_HOURS_BACK` — **eight days**.

That limit is set from what the archive holds rather than from a guess about
what anyone would want; the first version guessed two days and was wrong within
a week. Probing NOMADS found runs from nine days ago served and ten days gone,
and the boundary moves each day as a day rolls off, so eight leaves a day of
margin. A live `--series 24,192,0` returned eight charts spanning a week, every
one of them an analysis.

It is a margin and not a guarantee: a run can disappear between one request and
the next. A chart whose run has rolled off is therefore **skipped with a
warning** rather than failing the series around it — one chart of a week ago
being gone is no reason to throw away the other fifteen. Anything other than a
404 still stops the run, since a broken connection would fail every remaining
chart too. Downloads come back paired with the request that produced them rather
than matched by position, because the result can now be shorter than what was
asked for.

**Past charts are not forecasts re-run.** Each chart picks its own run by a
single rule that covers both directions:

```
run  = the latest cycle at or before min(valid time, now - publication lag)
lead = valid time - run
```

A chart of the future takes the newest run and a long lead, because that is the
best forecast there is. A chart of last night takes *last night's run* at a lead
of a few hours — often zero, the model's own analysis of that moment. A live
run of `--series 6,24,12` produced six charts, three of them analyses, longest
lead f018.

The `min` is what keeps it honest near the present: a run exists on paper before
NOMADS finishes publishing it, and asking for one that is not there yet gets a
404 rather than a fallback. That is also why a chart of three hours ago can have
a six-hour lead while one of thirty hours ago has none.

**An analysis is not an observation.** It is the model's own best estimate of
that moment, having assimilated observations into it. It is the closest this
source comes to "what actually happened", and it is what synoptic charts of the
past normally show, but it is not a measurement.

The subtlety on the forward side is that **"starting now" is not forecast hour 0**. Forecast hours
count from the model run, and the run in hand is always hours old — NOMADS
publishes one about four hours after its nominal time. So the first chart is the
first step at or after the gap between the run and the clock: for the 00Z run at
09:30 UTC with a three-hour step, that is hour 12. Getting this wrong is not
visible in the output, because hour 0 renders perfectly well — it is simply a
chart of this morning.

The series is stored as its definition rather than as the hours it resolved to,
which is what makes it correct in a crontab: "every three hours for the next two
days" means the same thing every night, while `9,12,15,18` means three moments
that have already passed. It is resolved to actual hours exactly once per run,
so the download and the labelling cannot disagree across a step boundary.

In the window, **◀** and **▶** step through the series one chart at a time, as
do the arrow keys while the map has focus. A front's arrival is obvious in the
difference between two charts and nearly invisible in either one alone, so the
buttons are on the toolbar rather than in a menu.

**Chart → Copy chart image** (Ctrl-Shift-C) puts the chart on screen on the
clipboard as an image, not as a path — a chart is usually wanted in a message or
a document. The accelerator avoids Ctrl-C deliberately: a menu accelerator fires
wherever the focus is, and taking Ctrl-C would break copying out of the latitude
and longitude fields.

## Saved profiles

A profile is a named answer to *what to fetch*: the area, the model and run, the
forecast hours or series, the variables and the levels. **Save profile** and
**Recall profile** on the toolbar; `--profile NAME` on the command line, with
`--list-profiles` to see what there is.

It deliberately holds nothing about how the chart is drawn. Someone who works a
home coastline and an ocean passage wants to switch between those two questions
without losing their theme, their time zone or the size of their window, so the
rendering settings stay where they are. A test asserts that no `render.` or
`ui.` key ever reaches a profile file.

One file each under `~/.weathermap/profiles`, in the same format as the
preferences — which is not laziness but the point: a profile *is* an area and a
`GribSelection`, and reusing the codec means one implementation of bounding
boxes, variable lists and series definitions rather than two that drift. A
directory of small files can also be listed, copied to another machine, deleted
with `rm` and put under version control, and a corrupt profile costs one profile
instead of all of them.

The file name is sanitised and lower-cased while the name you typed is stored
inside it, so a profile can be called `Ushant → Finisterre` and still live on a
filesystem that would rather it did not. Names differing only in case are the
same profile. The listing is collated rather than compared by code point, so
`Åland` sorts with the other A's instead of after Z.

On the command line a profile stands in for the stored preferences and
everything else still overrides it, which makes "the usual area, but tomorrow as
well" a one-flag change rather than a new profile:

```bash
weathermap --cli --profile "Western Approaches"
weathermap --cli --profile "Western Approaches" --hours 0,6   # overrides its series
```

## Chart times

Two labels, top right and much larger than the title: **VALID AT**, the instant
the forecast describes, and **CHART CREATED**, when the PNG was rendered. They
are what a chart gets checked against — whether this is the forecast for
tomorrow's crossing, and whether it was made before or after the last model run.
A chart whose age is not obvious gets trusted long after it should have been
thrown away.

The valid time used to sit in the title line, small and always in UTC. It does
not any more: having it in two places in two zones is two answers to one
question.

Times are written in the zone set by `--timezone` or **Chart → Time zone…**,
defaulting to the machine's own — "valid 06:00 UTC" makes someone in Bangkok do
arithmetic before they can tell whether it is this afternoon. The zone changes
how an instant is written down, never which instant it is.

The label is always an offset — `GMT+7`, `GMT+5:30`, or `UTC` at zero — and
never an abbreviation. The abbreviations are not unique and not international:
BST is both British Summer Time and Bangladesh Standard Time, IST is India,
Ireland and Israel. The offset is resolved at each instant rather than once per
chart, so the two labels differ correctly across a clock change.

**File names stay UTC**, so a directory of charts sorts chronologically no
matter who generated it or where they were.
