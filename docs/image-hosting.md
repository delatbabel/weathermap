# Hosting the images Instagram fetches

Instagram's API does not accept an upload. Every image is passed as a URL that
Meta's servers fetch, so charts have to be somewhere public, at a **predictable
path** — `https://host/folder/name.jpg`, where the name is the one the
application chose.

That rules out the consumer sync services. **Dropbox, pCloud, Google Drive,
OneDrive and Nextcloud all mint an opaque link per file or per folder**: the
address of a file cannot be derived from its name. They share links to files;
they do not serve directories.

This page sets up Cloudflare R2 with an `rclone` mount, which is the
arrangement that makes writing a file and publishing it the same act.

## What you end up with

```
the application writes   /home/you/weathermap-charts.example.com/chart-1.jpg
rclone uploads it to     r2://weathermap-charts/chart-1.jpg
Instagram fetches        https://charts.example.com/chart-1.jpg
```

No sync command, no timing to manage: the mount uploads on close, and the
application waits for each URL to answer before it posts.

## 1. Create the bucket

Cloudflare dashboard → **R2 Object Storage** → **Create bucket**. Name it for
what it holds — `weathermap-charts`. A location hint is optional; R2 has no
regions in the S3 sense.

## 2. Create an API token

R2 → **Manage R2 API Tokens** → **Create API Token**.

- Permission: **Object Read & Write**
- Scope it to the one bucket rather than the whole account
- No TTL, unless you want to rotate it on a schedule

You get three things, shown once:

| | |
|---|---|
| **Access Key ID** | goes in `rclone.conf` |
| **Secret Access Key** | goes in `rclone.conf` — shown only now |
| **Endpoint** | `https://<account-id>.r2.cloudflarestorage.com` |

## 3. Connect the custom domain

This is what turns the bucket into predictable URLs.

Bucket → **Settings** → **Custom Domains** → **Add**. Enter the hostname you
want to serve from — a subdomain is tidiest, `charts.example.com` — then
**Connect Domain**.

Cloudflare adds the CNAME itself and the connection takes a few minutes to go
live. The domain must already be a zone in the same Cloudflare account as the
bucket; a domain registered through Cloudflare already is.

Objects are then served at `https://charts.example.com/<key>`, so an object at
the bucket root is one path segment from the domain.

> Cloudflare also offers an `r2.dev` subdomain. It is rate-limited and
> documented as development-only, so it is the wrong thing to point a scheduled
> publisher at.

## 4. Configure rclone

Append to `~/.config/rclone/rclone.conf`:

```ini
[weathermap-r2]
type = s3
provider = Cloudflare
access_key_id = <access key id>
secret_access_key = <secret access key>
region = auto
endpoint = https://<account-id>.r2.cloudflarestorage.com
acl = private
no_check_bucket = true
no_head = true          # rclone older than 1.75 only
```

`acl = private` is right even though the bucket is public: the objects are not
world-readable through the S3 API, they are served by the custom domain. Public
access is a property of the domain binding, not of the object.

**The last two lines are not tuning. Without them nothing can be written at
all**, and each fails in a way that points at something else entirely. Both are
backend options, so they belong here rather than on the mount command line.
`no_head` is needed only on an rclone older than 1.75.

### `no_check_bucket = true` — otherwise every write is 403

Before its first write rclone checks that the bucket exists. That check is an
account-level operation, and a token scoped to a single bucket cannot perform
it, so it fails — and the failure is attributed to the write:

```
ERROR : hostname: Failed to copy: AccessDenied: Access Denied
	status code: 403
```

A 403 naming no operation reads as a permissions problem with the write, which
is how this first got diagnosed as an Object Read only token. It was not; the
token had already been recreated as **Object Read & Write** and behaved
identically. The tell is that `rclone lsd weathermap-r2:weathermap-charts`
succeeds at the same moment — a token that can list inside the bucket has the
keys and endpoint right.

### `no_head = true` — only on rclone older than 1.75

**Not needed on a current rclone.** Verified broken on `v1.60.1` and fixed by
`v1.75.1`; if yours is recent, leave this out and keep the integrity check. On
an older one, every write fails like this:

R2 answers a successful `PUT` with an `X-Amz-Version-Id` header. rclone reads
that as a versioned bucket and re-reads the object by version id to verify what
it just uploaded. R2 does not implement versioning, so that request returns
`501 NotImplemented`:

```
PUT  /weathermap-charts/chart.jpg          -> 200 OK   (X-Amz-Version-Id: 7e5f…)
HEAD /weathermap-charts/chart.jpg?versionId=7e5f…  -> 501 Not Implemented
ERROR : chart.jpg: Failed to copy: NotImplemented: Not Implemented
```

**The upload has already succeeded when this happens.** The object is in the
bucket and `rclone ls` will show it, while rclone reports the transfer as
failed and exits non-zero — so a publish script that checks the exit status
aborts on a file that is actually there. What is given up is the post-upload
verifying read; the ETag returned by the `PUT` is still checked against what
was sent.

```bash
rclone config update weathermap-r2 no_check_bucket true --non-interactive
rclone config update weathermap-r2 no_head true --non-interactive   # rclone < 1.75 only
```

`no_check_bucket` is still required on 1.75 — and 1.75 at least says which
operation was refused, which the older one did not:

```
Failed to copy: failed to prepare upload: operation error S3: CreateBucket,
https response error StatusCode: 403 ... AccessDenied: Access Denied
```

### Checking it

**Inside the bucket**, not at the account level:

```bash
rclone lsd weathermap-r2:weathermap-charts
rclone copy /etc/hostname weathermap-r2:weathermap-charts/
curl -I https://charts.example.com/hostname
rclone deletefile weathermap-r2:weathermap-charts/hostname
```

The `curl` should return `200`. If it returns 404 the domain is not connected
yet; if it never resolves, DNS has not propagated.

`rclone lsd weathermap-r2:` — with no bucket — returns 403 and **nothing is
wrong**. Listing every bucket in the account is an account-level operation that
a bucket-scoped token cannot do. Always list inside the bucket.


## 5. Mount it with systemd

`dist/r2/rclone-weathermap-r2.service` is a unit for this, modelled on an
ordinary WebDAV mount unit with two changes that object storage requires:

- **`--vfs-cache-mode writes`** is not optional. Without it rclone must know a
  file's length before it can begin the upload, and an image encoder that seeks
  back to write a header will fail. With it, the file is written locally and
  uploaded when it is closed.
- **`--dir-cache-time 10s`** instead of the five-minute default. Nothing else
  writes to this bucket, so a short cache costs nothing and a new chart shows up
  in a listing almost at once.

**A file written to the mount is not in the bucket yet.** With
`--vfs-cache-mode writes` rclone queues the upload rather than starting it when
the file is closed, so the file reads back through the mount immediately and the
public URL returns 404 for several seconds longer. Nothing is wrong; the write
has simply not left the machine. This is what `PublishGate.awaitReachable()` is
for — it polls every image URL before the post is submitted, because Instagram
fetches the images itself and a 404 at that moment fails the whole carousel.

### Cloudflare caches a 404, for four hours

The other half of the same problem, and the one that actually bit. An R2 custom
domain answers a request for a missing image with:

```
HTTP/2 404
cache-control: max-age=14400
```

So asking for a chart *before* the mount has uploaded it does not merely fail —
it teaches the cache that the chart is not there, for the rest of the day. Every
later request is answered from that entry:

```
poll 1: HTTP/2 404  cf-cache-status: HIT      <- object is in the bucket by now
poll 2: HTTP/2 404  cf-cache-status: HIT
```

The readiness check therefore never asks for the address the post will use. It
asks for that address with a **different cache-busting query on every single
request**, so the plain one stays untouched until the image really exists and
the first request for it — Meta's — reaches the bucket and is cached as a 200.

> One probe URL per run is not enough, and looks like it should be. The first
> poll of a run still happens before the upload finishes, so that probe address
> collects the cached 404 and every poll after it within the same run is
> answered from the cache. It fails in exactly the way it was meant to fix:
> three minutes of cached 404 for a file that arrived in two seconds.

This applies to images and not to the `weathermap-check.txt` marker, because
Cloudflare caches by file extension and `.txt` is not in its default list.
Testing the mechanism with a text file says nothing about what a `.jpg` does —
which is how this was missed the first time.

The wait is `--vfs-write-back`, five seconds by default, and it is **per file
and fixed** — not a bandwidth limit. Writing a carousel's worth of charts that
way takes long enough to look like a slow mount while the transfers themselves
take milliseconds. The unit sets `1s`, which is the right trade here: the files
are small and nothing else writes to this bucket, so there is no batching worth
preserving.

### Deleting through the mount needs rclone 1.75

On `v1.60.1`, removing a file **through the mount** fails with `Input/output
error`, and the mount's log gives the reason:

```
ERROR : IO error: NotImplemented: versionId not implemented
	status code: 501
```

It is the versioning gap again, on a path `no_head` does not cover: R2 returns a
version ID when a file is written, rclone keeps it on the cached object and sends
it back with the delete, and R2 has no versioned delete. `rclone deletefile` is
unaffected, because it looks the object up fresh with no version ID to send — so
on an old rclone, anything tidying up after itself has to go through the command
rather than the mount.

Both are fixed by `v1.75.1`: writes need no `no_head`, and `rm` through the mount
removes the object with no 501 in the log.

### The mount will not start over a non-empty directory

```
Fatal error: failed to mount FUSE fs: "/home/del/weathermap-charts..." is not
empty, use --allow-non-empty to mount anyway
```

**Do not add `--allow-non-empty`.** rclone is refusing for a good reason. The
mount hides whatever is already in the directory, so a chart written while the
mount is down lands on local disk, is hidden the moment the mount comes back,
and never reaches R2 — published as far as the application is concerned, and
absent from the URL Instagram will fetch. The refusal is the only thing that
makes that visible.

The harmless half is an empty subdirectory left behind when a mount goes away,
which the unit clears in `ExecStartPre`. Files are deliberately left alone so
the mount still fails loudly. If it does, look at what is in the directory
before deleting any of it — it may be a chart that never got published.

A related trap: after two failed starts the unit hits its rate limit and reports
`Start request repeated too quickly` instead of the real error. Clear it with
`systemctl reset-failed rclone-weathermap-r2.service` before starting again, and
read the failure from `journalctl -u rclone-weathermap-r2.service`, not from
`systemctl status`, which truncates the command line.

### Put mount flags on the command line, not in `rclone.conf`

A remote stanza in `rclone.conf` holds **backend** options — for WebDAV that is
`url`, `vendor`, `user`, `pass`; for S3 the keys and endpoint. VFS and mount
settings such as `vfs-cache-mode`, `buffer_size`, `transfers` and `checkers` are
**global flags**, and they do nothing there.

Nothing tells you. rclone stores unrecognised keys in a stanza without
complaint — a deliberately invented option is accepted exactly as quietly as a
misplaced real one — so a tuning block written into the config file looks
applied and is not. If you have one, move it into the unit's `ExecStart`, or set
it as `RCLONE_VFS_CACHE_MODE` and friends in the environment.

```bash
mkdir -p ~/weathermap-charts.example.com
sudo cp dist/r2/rclone-weathermap-r2.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now rclone-weathermap-r2.service
systemctl status rclone-weathermap-r2.service
```

Then prove the whole path end to end:

```bash
echo hello > ~/weathermap-charts.example.com/test.txt
curl -s https://charts.example.com/test.txt     # -> hello
rm ~/weathermap-charts.example.com/test.txt
```

## 6. Point the application at it

**Share → Post to Instagram**:

| Field | Value |
|---|---|
| Publish folder | `/home/you/weathermap-charts.example.com` |
| Public URL of that folder | `https://charts.example.com` |
| Sync command | *leave empty* — the mount is the sync |

**Check settings** writes a file and fetches it back over HTTP. That is the same
path a post takes, so if it passes, the hosting is done.

## Things worth knowing

**Content type.** rclone sets it from the file extension, so `.jpg` is served as
`image/jpeg`, which is what Meta requires. Worth confirming once with
`curl -I` on a real chart.

**A mount is not a disk.** Files appear in the bucket when they are *closed*,
not as they are written, and a program that reopens a file it wrote a moment ago
is reading through rclone's cache rather than from R2. Nothing here does that,
and the application's reachability check covers the gap either way.

**Cost.** R2 charges for storage and operations but not for egress, which is the
part that usually bites when images are fetched repeatedly. A few charts a day
sits inside the free tier.

**Old charts accumulate.** Nothing deletes them. A weekly cron is enough:

```bash
rclone delete --min-age 30d weathermap-r2:weathermap-charts/
```
