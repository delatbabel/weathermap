# What probing the live services established


Three assumptions turned out to be wrong, and the design changed because of it.

## NOMADS filter output is simple-packed, not JPEG2000

GRIB2 packs its data section with one of a dozen templates, and the raw
published GFS files use JPEG2000 (template 5.40) — which is why decoding GRIB
normally means taking a large dependency.

But the filter endpoints **re-encode each subset**, and what comes back is
template 5.0, simple packing, on a regular lat/lon grid (3.0). Verified against
live GFS 0.25° responses for `TMP`, `PRMSL` and `APCP`.

So `Grib2Scanner` decodes it directly in about 200 lines, with no dependency at
all. NetCDF-Java became the *optional* path for everything else rather than the
main one — see [Optional NetCDF-Java](build-and-run.md#optional-netcdf-java).

## GRIB2 angles are sign-magnitude

Latitudes, longitudes and the binary/decimal scale factors are stored with the
top bit as a sign flag, not in two's complement. Reading a southern latitude as
a signed integer gives a number near −2 billion. `signedMicroDegrees` and
`signedShort` exist for this.

## Section 3's point count is at octet 7, not 6

An off-by-one here reported "9 points" for a 2,491-point grid, because it read
the last three bytes of the count plus the preceding source byte. It is the kind
of error that produces plausible-looking nonsense rather than an exception, and
it was only caught by checking the number against the arithmetic — 13° ÷ 0.25 +
1 = 53 columns, 11.5° ÷ 0.25 + 1 = 47 rows.
