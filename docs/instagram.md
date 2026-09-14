# Posting to Instagram

**Share → Post to Instagram…** publishes the chart on screen and the ones after
it as a carousel.

The setup is longer than you would expect, and the reason is worth stating
first, because it shapes everything else.

## There is no password

**Instagram has no supported way to post with a username and a password.**
Publishing goes through Meta's Content Publishing API, which authenticates with
an OAuth access token issued to an app you register, against an Instagram
*professional* account linked to a Facebook Page.

Libraries that take a password drive Instagram's private mobile API instead.
That breaches the Terms of Use and is a reliable way to have an account
restricted or disabled — a poor trade for skipping a setup you do once.

So the credentials are:

| Field | What it is |
|---|---|
| **Token from** | Which of Meta's two paths issued the token — they are not interchangeable |
| **Instagram user ID** | The numeric ID of the professional account — not the handle |
| **Access token** | A long-lived token |
| **Publish folder** | Where this application writes the images |
| **Public URL of that folder** | The address that same folder is served at |

The handle is stored too, but only so the dialog can show you which account is
configured.

## Which path, and why it matters

Meta offers two. For **one account that you own**, take the first:

| | Instagram Login | Facebook Login |
|---|---|---|
| Facebook Page needed | no | yes |
| Login flow to build | **no** | yes |
| Access level | **Standard** | Standard for your own account |
| Token lifetime | 60 days, refreshable | Page token does not expire |
| API host | `graph.instagram.com` | `graph.facebook.com` |
| Scopes | `instagram_business_basic`, `instagram_business_content_publish` | `instagram_basic`, `instagram_content_publish`, `pages_show_list`, `pages_read_engagement` |

**The host is the part that bites.** A token from one path is rejected by the
other's host, and the rejection reads as an authentication error — so it looks
like a bad token when it is a wrong address. The dialog's *Token from* field
picks the host, which is the only reason the application needs to know.

Standard Access is enough either way if the app serves only accounts you own or
manage. Advanced Access is for serving other people's accounts, and needs App
Review.

## Why a public URL is one of the credentials

**The API does not accept an uploaded image.** Every photo is passed as a URL
that Meta's servers fetch for themselves. A chart on your disk cannot be posted;
it has to be somewhere on the public internet first.

So you need a folder this application can write to that is also served over
HTTP at a **predictable path** — `https://host/folder/name.jpg`, where the name
is the one the application chose.

That last part rules out most consumer sync services. **Dropbox, pCloud, Google
Drive, OneDrive and Nextcloud all mint an opaque share link per file or per
folder**, so the address of a file is not derivable from its name. They are
built to share a link to a file, not to serve a directory, and no amount of
configuration turns one into the other.

### What does work

| Option | URL you get | Notes |
|---|---|---|
| **Cloudflare R2** + custom domain | `https://files.yoursite/charts/x.jpg` | S3-compatible, generous free tier, no egress charge. The best fit for this. |
| **Backblaze B2** (public bucket) | `https://f000.backblazeb2.com/file/bucket/x.jpg` | Cheapest raw storage; put Cloudflare in front for a tidy domain. |
| **Amazon S3**, **DigitalOcean Spaces**, **Wasabi** | `https://bucket.host/charts/x.jpg` | Same pattern; S3 charges for egress. |
| **Any web host you already have** | `https://yoursite/charts/x.jpg` | The simplest answer if you have one. SFTP or rsync into `public_html`. |
| **GitLab / GitHub Pages** | `https://you.gitlab.io/charts/x.jpg` | Free and path-based, but every chart is a commit. |

All of them need the object to be **publicly readable** and served as
`image/jpeg`. Meta fetches anonymously; a signed or expiring URL will not do.

See [Hosting the images](image-hosting.md) for a worked setup of the first
row — Cloudflare R2 with an rclone mount.

### Getting the files there

[**rclone**](https://rclone.org) is the piece that replaces Dropbox in this
arrangement. It talks to R2, B2, S3, Spaces, SFTP and dozens more, and one
command uploads the folder:

```bash
rclone sync /home/you/weathermap-charts r2:charts
```

Two ways to wire it in:

- **Mount the bucket** with `rclone mount`, point the publish folder at the
  mount, and writing *is* uploading. Nothing else to configure.
- **Or set a sync command** in the dialog, and the application runs it after
  writing and before posting.

### The timing this creates

A synced folder is not live the moment a file is written, and that matters here
in a way it usually does not. Writing the images and posting them is one action
to you and two events on the internet: if Instagram is asked to fetch a URL
before the upload finishes, it reports that the media could not be retrieved —
which reads as a wrong URL, and by the time you check by hand the sync has
finished and the URL works perfectly.

So the application does not post the moment it has written the files. It runs
the sync command if there is one, then **polls every image until it really
answers over HTTP**, and only then posts. If an image is still unreachable after
three minutes it stops and says which one, rather than spending part of the
day's quota on a carousel that will fail halfway.

A directly served folder needs no sync command; the check costs one request per
image and passes immediately.

## Getting the token — Instagram Login

You do this once, in a browser. There is no login flow to write.

**1. Make the account professional.** Instagram app → Settings and privacy →
Account type and tools → Switch to professional account. Business or Creator
both work.

**2. Create a Meta app.** [developers.facebook.com/apps](https://developers.facebook.com/apps)
→ Create app. Choose the **Business** type. You do not need to connect a
Business portfolio for Standard Access.

**3. Add the Instagram product.** In the app, add **Instagram** → *API setup
with Instagram login*. This is the section the whole setup lives in.

**4. Configure business login settings.** Step 3 of that page, *Set up Instagram
business login* → **Business login settings**. Here you will find, and set:

  - **Instagram app ID** and **Instagram app secret** — generated for you. These
    are *not* the same as the Facebook app ID and secret on the app's main
    settings page, which is a genuinely easy mistake to make.
  - **OAuth redirect URI** — required even though you will not use the flow.
    Anything you control will do; `https://localhost/` is accepted. It must
    match exactly if you ever do use it, and the dashboard may append a trailing
    slash.

  Keep the **app secret out of this application**. It is needed only to exchange
  or refresh a token, from somewhere you control.

**5. Give the account the Instagram Tester role, and accept it.** The dashboard
will not offer to generate a token for an account the app cannot see, and in
development that means the account needs the tester role — even when it is your
own account and your own app. The dashboard says so beside *Generate access
tokens*, and it is easy to read past.

  - **Assign it** at
    `https://developers.facebook.com/apps/<app-id>/roles/roles/` — go straight
    to the URL. Meta has reorganised the App Dashboard sidebar more than once and
    the entry is not always where a guide says it is; the URL has been stable.
  - **Add people** → in the dialog scroll to **Additional roles** → tick
    **Instagram Tester** → type the Instagram username → **Add**. The account
    appears as **Pending**.
  - **Accept it from Instagram**, signed in as that account:
    [instagram.com/accounts/manage_access](https://www.instagram.com/accounts/manage_access/)
    → the **Tester Invites** tab → **Accept**. In the app the same page is under
    **Website permissions → Apps and websites → Tester invitations** — note
    *Website permissions*, not directly under Settings.
  - Back in the dashboard the status becomes **Active**.

  You are inviting yourself, so both halves are yours to do — but the
  acceptance is the half that gets forgotten, because nothing in the dashboard
  prompts for it and the token button simply stays unhelpful until it is done.

  > Both of these were wrong in an earlier draft of this page, written from the
  > API reference rather than from the screens. Where this guide names a URL, it
  > is because the URL outlived the menu path.

**6. Add the account and generate the token.** Back in *API setup with Instagram
login*, step 2 — **Add account**, then **Generate token** beside the account.
Expect to log in to Instagram a second time here, even though you just accepted
the invite; that is normal.

  The account now shows its handle with a long number beneath it. **That number
  is the Instagram user ID** — the same value step 7 returns, so you can copy it
  straight from the screen and use step 7 only to confirm it.

  The token it hands back is **already long-lived: 60 days**. That is the point
  of this path: no short-lived token to exchange, and no flow to implement.

**7. Confirm the Instagram user ID.** Ask the API who the token belongs to:

```bash
curl -s "https://graph.instagram.com/v25.0/me?fields=user_id,username&access_token=YOUR_TOKEN"
```

The `user_id` should match the number shown under the account in step 6. That is
what goes in the dialog — not the handle, and not a Facebook Page ID.

**8. Paste both into the dialog** — *Token from: Instagram Login*, the user ID,
and the token.

### What you can skip

The API setup page has a step 4, **Set up Instagram business login**, with
redirect URIs, an embed URL and a permissions list. **You do not need to
complete it to post to your own account.** That section configures the OAuth
flow by which *other* businesses would grant your app access — it exists for
apps that serve accounts they do not own. You are not running a flow; you
generated a token directly.

The one thing worth taking from that area is the **Instagram app ID and app
secret**, which is where they live.

And you may well never need the secret. It is required only to *exchange* a
short-lived token for a long-lived one, and the dashboard hands you a long-lived
one already. Refreshing does not use it:

```
GET https://graph.instagram.com/refresh_access_token
    ?grant_type=ig_refresh_token
    &access_token=<current-token>
```

Store the secret somewhere safe anyway — if you later build the login flow, that
is when it is needed.

### Keeping it alive

A 60-day token can be refreshed for another 60, any time after it is 24 hours
old, as long as it is still valid:

```bash
curl -s "https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token&access_token=YOUR_TOKEN"
```

The response is a JSON object whose `access_token` is the renewed one — use
that from then on. Let it lapse instead and you repeat step 6, which is a
minute's work. Set a reminder for about day 50.

If you ever start from a short-lived token instead, exchange it first:

```
GET https://graph.instagram.com/access_token
    ?grant_type=ig_exchange_token
    &client_secret=<instagram-app-secret>
    &access_token=<short-lived-token>
```

## The other path — Facebook Login

Worth it only if you already work through a Facebook Page, because a Page token
does not expire. The Instagram account must be linked to a Page.

1. Add **Facebook Login** to the app.
2. In the [Graph API Explorer](https://developers.facebook.com/tools/explorer),
   request `instagram_basic`, `instagram_content_publish`, `pages_show_list`,
   `pages_read_engagement`.
3. Exchange the short-lived token:
   `GET https://graph.facebook.com/v21.0/oauth/access_token?grant_type=fb_exchange_token&client_id=<app-id>&client_secret=<app-secret>&fb_exchange_token=<token>`
4. `GET https://graph.facebook.com/v21.0/me/accounts` — each Page's
   `access_token` is the non-expiring one.
5. `GET https://graph.facebook.com/v21.0/<page-id>?fields=instagram_business_account`
   gives the Instagram user ID.

Set *Token from: Facebook Login* in the dialog so the calls go to the right
host.

## What gets posted

The chart on screen is **first**, then the ones after it in the series, in
chronological order. A carousel is read left to right, so the post never wraps
back to the start of the series — near the end you simply get fewer images than
you asked for.

Default four, adjustable from two to ten. Ten is Instagram's own limit on a
carousel.

Each image is re-encoded as **JPEG** at high quality, because the API accepts
nothing else for a carousel, and drawn onto white because JPEG has no
transparency. Each carries alt text naming the chart's valid time.

Instagram crops every image in a carousel to the aspect ratio of the first one.
Charts in a series share an area, so they share an aspect and nothing is lost —
but if you post charts of different areas together, the first one decides the
crop.

## Limits and storage

Meta allows **100 API-published posts in a rolling 24 hours**, and a carousel
counts as one.

The credentials live in `~/.weathermap/instagram.properties`, separate from the
preferences, and the file is made readable only by you where the filesystem
allows it. **It holds a bearer token in plain text**: anything that can read the
file can post as that account until the token is revoked. The application never
writes the token to a log or into an error message.

## Parameters in the caption

A daily chart's caption is the same sentence every day with the date changed.
Writing the date in means retyping it every day, and eventually not noticing
that you didn't — so the caption can carry parameters instead, and they are
worked out at the moment of posting.

```
Saigon Weather charts for ${tomorrow:'+%A %e %B %Y'}

#saigonweather #gfs
```

posts as *Saigon Weather charts for Tuesday 15 September 2026*. **The stored
caption keeps the template**, so tomorrow's post says tomorrow's date with
nobody touching it.

### `${DATE:FORMAT}`

Both halves are exactly what the `date` command takes: `DATE` is its
`--date=STRING`, `FORMAT` its `+FORMAT`. Either half may be wrapped in single or
double quotes, and the format may keep the `+` it would have in a shell, so a
format can be pasted straight across.

| Written | Gives |
|---|---|
| `${tomorrow:'+%A %e %B %Y'}` | Tuesday 15 September 2026 |
| `${now:"+%F %T"}` | 2026-09-14 22:17:06 |
| `${'next friday':'+%A %e %B'}` | Friday 18 September |
| `${3 days ago:'+%a %d %b %y'}` | Fri 11 Sep 26 |
| `${yesterday}` | 2026-09-13 — no format means `%F` |
| `${today:'+%-d/%-m/%Y'}` | 14/9/2026 |

Dates it understands: `now`, `today`, `tomorrow`, `yesterday`; offsets such as
`+3 days`, `-2 weeks`, `2 hours ago`, `next week`, `last month`; day names with
`next`, `last` or `this`; `2026-09-15`, a time as `14:30`, the two together, and
`@` followed by seconds since the epoch.

Formats it understands: `%a %A %b %B %C %d %D %e %F %h %H %I %j %k %l %m %M %n
%p %P %r %R %s %S %t %T %u %V %w %y %Y %z %Z %%`, with the `-`, `_`, `0` and `^`
flags. `%d` is zero-padded, `%e` space-padded and `%-d` not padded: on the fifth
of the month, `05`, ` 5` and `5`.

To put a literal `${` in a caption, write `$${`.

### Three rules that are not what they look like

Taken from GNU `date` by running it, not by reasoning about it, because each one
can put the wrong day on a post:

- **A relative offset keeps the time of day.** `tomorrow` at 22:17 is tomorrow
  at 22:17, not tomorrow morning.
- **Naming a day or a date does not.** `friday` and `2026-09-15` are both
  00:00:00 — a date without a time means midnight. An explicit time wins over
  both: `tomorrow 09:00`.
- **`next monday` on a Monday is a week away**, while a bare `monday` on a
  Monday is today. "next" means strictly after today; a bare day, or `this`,
  means on or after it.

### Where the colon goes

Both halves can contain one — `${2026-09-15 14:30:'+%H:%M'}` has four. The
format is the half that begins with `+`, so the divider is the last colon whose
remainder does; quoting the date settles it outright. Both of these work:

```
${'2026-09-15 14:30':'+%H:%M'}
${2026-09-15 14:30:'+%H:%M'}
```

### This is not a call to `date`

`--date` is a GNU extension. BSD `date` on macOS does not have it and Windows
has no `date` of this kind at all, and packages are built for all three. It is
implemented here instead, as a documented subset, checked against real GNU
`date` output across several hundred expression-and-format pairs.

Anything outside that subset is **named as a problem rather than guessed at**.
The dialog shows what the caption will actually say as you type it, and refuses
to post a caption whose parameters cannot be worked out — a post is published
before anyone reads it, and that is the one place a mistake cannot be taken
back.

## Posting from the command line

The same account, the same caption, the same publishing path — there is one of
each, so a post made from cron cannot differ from one made by hand.

```bash
java -jar weathermap.jar --cli --profile "BoB to East Sea" --post --count 4
```

The account is read from `~/.weathermap/instagram.properties`, written by the
dialog. **There is no flag for the token.** A command-line argument is visible
in `ps` to every user on the machine and is written to the shell history, which
is no place for a credential that can post as you until it is revoked.

The caption is the stored one unless `--caption` or `--caption-file` says
otherwise, and its parameters are expanded at the moment of posting exactly as
they are from the window.

Everything that can be checked is checked before the download starts: the
account is complete, the caption expands, and the count is between 2 and 10.
Failing after fetching and compositing eight charts wastes the work and reports
the problem a long way from its cause.

See [Build and run](build-and-run.md) for the scheduling, including which local
times the charts fall on and why a nightly job should not sit exactly on the
hour.

## A container is not ready when its ID comes back

Creating a container returns an ID straight away, and then Meta goes off to
fetch the image from the public URL. Publishing before it has finished is
refused:

```
publish failed: Media ID is not available - Phương tiện này chưa sẵn sàng đăng,
vui lòng chờ trong giây lát
```

The Vietnamese half is Meta's `error_user_msg`, returned in the account's own
language: *this media is not ready to publish, please wait a moment*. It reads
like a failure and is an instruction — so each container is polled on
`GET /{container-id}?fields=status_code` until it reports `FINISHED`, rather
than being published on the assumption that an ID means readiness. Every image
container is checked, and then the carousel container.

`ERROR` and `EXPIRED` are final and are reported at once; waiting out the
five-minute timeout to say an image could not be fetched helps nobody.

Expect the whole post to take **minutes, not seconds** — Meta fetches every
image in the carousel before it will publish, and that is the bulk of the time.
The status bar counts the seconds while it waits, and a dialog confirms the
finished post with a link to it, because by the time it completes nobody is
still watching the window.

> Meta files a great many unrelated failures under `type: OAuthException`,
> including this one. The type alone does not mean the token is wrong, and
> treating it as though it did sends you to check credentials that are working
> perfectly. Only a message that is itself about the token says so.

## What has been exercised, and what has not

Posting has now been run against Meta's servers with a real app, token and
account: the images are written, uploaded, fetched by Meta and assembled into
containers. The request construction, image handling, chart selection,
credential storage, container readiness and error reporting are covered by
tests.
