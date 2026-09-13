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
| **Instagram user ID** | The numeric ID of the professional account — not the handle |
| **Access token** | A long-lived user token (~60 days) or, better, a Page token, which does not expire |
| **Publish folder** | Where this application writes the images |
| **Public URL of that folder** | The address that same folder is served at |

The handle is stored too, but only so the dialog can show you which account is
configured.

## Why a public URL is one of the credentials

**The API does not accept an uploaded image.** Every photo is passed as a URL
that Meta's servers fetch for themselves. A chart on your disk cannot be posted;
it has to be somewhere on the public internet first.

So you need a folder this application can write to that is also served over
HTTP — a directory on your web host, a synced folder, a bucket, anything that
gives a stable public address. The dialog's **Check settings** writes one small
file and fetches it back over HTTP, because a mismatched folder and URL is the
commonest mistake here and the hardest to diagnose: the images write perfectly,
and the failure arrives later from Meta as "the media could not be retrieved".

## Getting the token

You do this once. The result is a token you paste into the dialog.

1. **Make the Instagram account professional** (Business or Creator) and link it
   to a Facebook Page — Instagram app → Settings → Account type and tools.
2. **Create a Meta app** at [developers.facebook.com](https://developers.facebook.com/apps)
   — type *Business*. Add the **Facebook Login** product.
3. **Get a user token with the right scopes.** In the
   [Graph API Explorer](https://developers.facebook.com/tools/explorer), select
   your app and request:
   `instagram_basic`, `instagram_content_publish`, `pages_show_list`,
   `pages_read_engagement`.
4. **Make it long-lived.** A token from the Explorer lasts an hour or two:

   ```
   GET https://graph.facebook.com/v21.0/oauth/access_token
       ?grant_type=fb_exchange_token
       &client_id=<app-id>
       &client_secret=<app-secret>
       &fb_exchange_token=<short-lived-token>
   ```

   The result lasts about 60 days.
5. **Get a Page token, which does not expire.** With the long-lived user token:

   ```
   GET https://graph.facebook.com/v21.0/me/accounts
   ```

   The response gives each Page's `id` and its `access_token`. That Page token
   is the one worth storing — a long-lived *user* token still has to be renewed
   every couple of months.
6. **Find the Instagram user ID:**

   ```
   GET https://graph.facebook.com/v21.0/<page-id>?fields=instagram_business_account
   ```

   The `instagram_business_account.id` is what goes in the dialog.

Keep the app secret out of this application; it is only needed for step 4, and
that call should be made from somewhere you control.

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

## What has not been exercised

The publishing calls have not been run against Meta's servers — that needs a
real app, a real token and a real account. The request construction, the image
handling, the chart selection and the credential storage are covered by tests;
the three-step conversation with the API is written to the documented contract
but is unproven until you post with it.
