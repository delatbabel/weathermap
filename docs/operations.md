# Running it against the public services

## The User-Agent is load-bearing

Overpass instances police it, and a placeholder is worse than a plain string.
Ours carried `example.invalid`, and two of the three instances then in use
refused **every**
request with `HTTP 429` and the body:

> Please include a meaningful User-Agent string with your requests to avoid
> rate-limiting.

A 429 reads as rate limiting, so it was diagnosed as load and waited out — which
could never have worked, since the same request would have been refused a week
later. Verified by posting an identical query with and without the placeholder:
429 with, 200 without, on both instances.

A fourth instance was added afterwards. Three leaves very little headroom when
one of them — `overpass-api.de` — also publishes two A records of which one is
black-holed from some networks: Java's `HttpClient` picks one address and does
not fall back, so roughly half the attempts at the healthiest instance stall
before failing over.

So the default names the application and what it does and claims nothing untrue.
Set `-Dweathermap.userAgent=...` to add a contact address if you run it hard;
inventing a URL on the user's behalf is what caused this. A test fails the build
if a placeholder reappears in the string.

A 429 now quotes the server's own response body rather than asserting a cause.
The status code alone cannot distinguish "you are asking too often" from "I do
not like your User-Agent", and the body says which.

## Before pointing this at the public services


- **Set a real `Http.USER_AGENT`.** It currently names `example.invalid`. Both
  the OSM Foundation's Overpass instances and NOMADS police the User-Agent, and
  anonymous clients get blocked.
- **Keep the attribution layer on.** OSM data is ODbL, which requires the source
  to be credited on anything produced from it. `AnnotationLayer` draws
  "© OpenStreetMap contributors" whenever the annotation layer is enabled.
- **Be sparing.** Both services are shared capacity. The cache exists so that
  re-rendering the same area costs nothing; requests are made in sequence, never
  in parallel.

## Licensing of the data


| Source | Licence | Obligation |
|---|---|---|
| OpenStreetMap | ODbL 1.0 | Attribute; share alike if you publish derived data |
| Natural Earth | Public domain | None, but the outline and the gazetteer are credited on charts that use them |
| NOAA NOMADS | US Government public domain | None, but crediting NOAA is conventional |
