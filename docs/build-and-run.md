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
