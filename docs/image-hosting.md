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
```

`acl = private` is right even though the bucket is public: the objects are not
world-readable through the S3 API, they are served by the custom domain. Public
access is a property of the domain binding, not of the object.

Check it before going further — **inside the bucket**, not at the account level:

```bash
rclone lsd weathermap-r2:weathermap-charts
rclone copy /etc/hostname weathermap-r2:weathermap-charts/
curl -I https://charts.example.com/hostname
rclone delete weathermap-r2:weathermap-charts/hostname
```

The `curl` should return `200`. If it returns 404 the domain is not connected
yet; if it never resolves, DNS has not propagated.

### Reading a 403 from R2

`AccessDenied` means different things depending on which command produced it,
and the distinction saves a lot of time:

| Command | 403 means |
|---|---|
| `rclone lsd weathermap-r2:` | **Nothing is wrong.** Listing every bucket is an account-level operation, and a token scoped to one bucket cannot do it. Always list inside the bucket. |
| `rclone lsd weathermap-r2:bucket` | The token is scoped to a different bucket, or the endpoint has the wrong account ID. |
| `rclone copy ... weathermap-r2:bucket/` **after a successful list** | The token is **Object Read only**. Recreate it as **Object Read & Write**. |

That middle-of-the-night one is the trap: a read-only token lists perfectly and
fails only when something is written, which is long after the setup looked
finished.

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
