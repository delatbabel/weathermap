# Build and run


Java 21 or later. One runtime dependency — [FlatLaf](https://www.formdev.com/flatlaf/),
about a megabyte, and only the window uses it. Everything else is in the JDK.

Everything goes through the `Makefile`; `make help` lists every target.

```bash
make help                    # every target, with a one-line description
make jar                     # build the fat jar
make test                    # run the tests
make run                     # build if needed, then launch
make run ARGS="--cli"        # the command-line tool
make cli                     # the same thing, spelled shorter
make netcdf                  # build with the optional NetCDF-Java decoder
make clean
```

The jar itself is still plain Maven, if you would rather:

```bash
./mvnw package                                               # jars + tests
java -jar target/weathermap-1.0.0-jar-with-dependencies.jar
```

For installable packages — `.deb`, `.rpm`, Windows `.exe`, macOS `.dmg` — and
for the version numbering, see [Packaging](packaging.md).

The command-line tool is the same jar, and runs from the plain one too, since
it never creates a window:

```bash
java -jar target/weathermap-1.0.0.jar --cli --help
```

The desktop application started from the plain jar still opens — it falls back
to the platform look and feel rather than failing on the missing class — it just
will not be themed.

With no options it repeats the last area and GRIB selection the desktop
application stored, against the latest published run — which is the point:

```bash
# in a crontab, ten minutes after each GFS cycle is published
10 4,10,16,22 * * *  java -jar /opt/weathermap.jar --cli --quiet
```

Everything stored can still be overridden:

```bash
java -jar target/weathermap-1.0.0.jar --cli \
    --area "-6,53.5,0,57" --var TMP --level 2_m_above_ground \
    --hours 0,6,12-24 --out ~/maps --size 1600x1200
```

### Profiles

A profile saved in the desktop application carries an area, a model, forecast
hours or a series, variables and levels — everything a chart needs but the
rendering. Name it and the command line uses the lot:

```bash
java -jar weathermap.jar --cli --profile "BoB to East Sea"
java -jar weathermap.jar --cli --list-profiles
```

Other options still override a profile, so "the usual area but further ahead"
is one extra flag rather than a second profile.

### Posting to Instagram

`--post` publishes the rendered charts as a carousel, **using the account set up
in the desktop application** — *Share → Post to Instagram…*. There is no
command-line way to supply a token, deliberately: an argument is visible in
`ps` to every user on the machine and lands in the shell history besides.

```bash
java -jar weathermap.jar --cli --profile "BoB to East Sea" --post --count 4
```

The caption comes from the same stored settings and may carry parameters, which
are expanded when the post is made:

```bash
--caption "Charts for ${tomorrow:'+%A %e %B %Y'} #saigonweather"
--caption-file ~/.weathermap/caption.txt      # for one with line breaks
```

See [Posting to Instagram](instagram.md) for what the parameters can say.

**Everything is checked before anything is downloaded** — the account is
complete, the caption expands, the count is between 2 and 10. A cron job that is
going to fail should fail before spending ten minutes fetching GRIB, and should
say why near the cause rather than at the end.

`--dry-run` reports what would be fetched and posted, and does neither.

### A chart of tomorrow, posted this evening

```bash
# 19:45 daily — late enough that the first chart is tomorrow
45 19 * * *  java -jar /opt/weathermap.jar --cli \
                 --profile "BoB to East Sea" --post --quiet
```

`--quiet` prints **nothing** to stdout. It does not trim the output — it sends
the same lines to `~/.weathermap/weathermap.log` instead, along with the
logging of every class that does any, which otherwise goes to stderr and would
have cron mailing you a retry warning. `--log FILE` puts it somewhere else, and
turns the log on for an ordinary run too. The live file is always exactly the
path named; when it passes a megabyte the previous one is kept alongside it as
`weathermap.log.1`.

```
2026-09-14 23:17:10  profile   BoB to East Sea
2026-09-14 23:17:10  series    12 charts, 2026-09-14T18:00:00Z to 2026-09-17T12:00:00Z
2026-09-14 23:17:10  caption   Weather charts for tomorrow, Tuesday 15 September 2026
2026-09-14 23:17:10  posted    17895695668004550
```

> **Quiet means quiet: failures go to the log and nowhere else**, so a crontab
> entry produces no mail whether the run worked or not. The exit status still
> reports the failure — `0` for success, non-zero otherwise — which is what a
> wrapper script should test if it needs to know. Without `--quiet` failures go
> to stderr exactly as before.
>
> The single exception is a log that cannot be opened. There is then nowhere
> else for a failure to go, so it falls back to stderr rather than being lost.

Series times sit on a grid of whole steps **in UTC**, not on the local clock, so
which local times the charts fall on depends on the offset from UTC. At UTC+7
with a six-hourly series they are 01:00, 07:00, 13:00 and 19:00 local; the first
chart of a run started in the evening is therefore 01:00 tomorrow.

> **Do not put the job exactly on that boundary.** At UTC+7 a run at 19:00:00
> still begins with the 19:00 chart — today — and a run at 19:00:01 begins with
> 01:00 tomorrow. A cron line of `0 19 * * *` decides which of those you get by
> how long the machine took to start the JVM. A few minutes past the hour costs
> nothing and settles it.

To be certain regardless of the clock, name the moment instead:

```bash
--start 'tomorrow 06:00'      # or '+12 hours'
```

`--start` takes the same date expressions as a caption parameter and moves the
whole window, so `--start 'tomorrow 06:00' --series 3,48` is 48 hours of charts
beginning tomorrow morning — not 48 hours from now with the front cut off. The
first chart is the first grid slot **at or after** the moment asked for, never
before it, so at UTC+7 a three-hourly series asked to start at 06:00 begins at
07:00.

Which run each chart is drawn from is decided against the real clock either way:
a series starting tomorrow is still built from runs that have actually been
published.

## Optional NetCDF-Java


```bash
./mvnw package -Pnetcdf
```

Compiles `grib/netcdf/NetcdfGribReader`, which is loaded reflectively so the
application builds, ships and runs without it. Needed only for sources the
built-in reader does not cover:

- raw published files rather than filter output (JPEG2000, complex packing);
- Lambert-conformal grids — NAM and HRRR, grid template 3.30;
- the full WMO parameter table, rather than the nine variables catalogued here.

**It is currently a stub** that reports itself unavailable. The class javadoc
sets out the intended route.
