package org.weathermap.instagram;

import org.weathermap.model.InstagramAccount;
import org.weathermap.util.Http;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Publishes a carousel through Meta's Content Publishing API.
 *
 * <h2>The three-step shape</h2>
 *
 * <p>Nothing is posted in one call. Each image becomes a <em>container</em>,
 * the containers become a carousel container, and the carousel is published:</p>
 *
 * <pre>    POST /{ig-user-id}/media          image_url=..., is_carousel_item=true   -> container id
 *    POST /{ig-user-id}/media          media_type=CAROUSEL, children=..., caption=...
 *    POST /{ig-user-id}/media_publish  creation_id=...</pre>
 *
 * <p>The middle step is where a bad image URL is discovered, because that is
 * when Meta's servers fetch it - so a failure there is usually about hosting
 * rather than about the post.</p>
 *
 * <h2>What the API will not do</h2>
 *
 * <ul>
 *   <li><b>It will not take an upload.</b> Images are fetched from public URLs;
 *       there is no binary endpoint for photos.</li>
 *   <li><b>It will not take a PNG.</b> Carousels are JPEG only, which is why
 *       {@link CarouselImage} exists.</li>
 *   <li><b>It will not take more than ten.</b></li>
 *   <li><b>It will not take a password.</b> See {@link InstagramAccount}.</li>
 * </ul>
 */
public final class InstagramClient {

    private static final Logger LOG = Logger.getLogger(InstagramClient.class.getName());

    /** Instagram's own limit on one carousel. */
    public static final int MAX_CAROUSEL = 10;

    private final InstagramAccount account;
    private final String base;

    /**
     * Calls the host the account's token belongs to.
     *
     * <p>Not one host with a version: Instagram Login tokens are accepted only
     * by {@code graph.instagram.com} and Facebook Login tokens only by
     * {@code graph.facebook.com}. Crossing them fails as an authentication
     * error, which looks like a bad token and is really a wrong address.</p>
     */
    public InstagramClient(InstagramAccount account) {
        this(account, account.login().apiBase());
    }

    /** @param base the API root, so a test can point this somewhere else */
    public InstagramClient(InstagramAccount account, String base) {
        this.account = account;
        this.base = base;
    }

    /** One image of a carousel: where it is served, and what it shows. */
    public record CarouselImage(String url, String altText) { }

    /**
     * Posts a carousel and returns the published media ID.
     *
     * @param images  two to ten images, in the order they should appear
     * @param caption the post's text
     */
    public String postCarousel(List<CarouselImage> images, String caption)
            throws IOException, InterruptedException {

        if (images.size() < 2 || images.size() > MAX_CAROUSEL) {
            throw new IllegalArgumentException(
                    "a carousel holds 2 to " + MAX_CAROUSEL + " images, not " + images.size());
        }

        final List<String> children = new ArrayList<>();
        for (CarouselImage image : images) {
            children.add(createItemContainer(image));
        }
        final String carousel = createCarouselContainer(children, caption);
        return publish(carousel);
    }

    private String createItemContainer(CarouselImage image)
            throws IOException, InterruptedException {
        final StringBuilder body = new StringBuilder()
                .append("image_url=").append(Http.encode(image.url()))
                .append("&is_carousel_item=true");
        if (image.altText() != null && !image.altText().isBlank()) {
            body.append("&alt_text=").append(Http.encode(image.altText()));
        }
        return idFrom(post("/" + account.igUserId() + "/media", body.toString()),
                      "image container for " + image.url());
    }

    private String createCarouselContainer(List<String> children, String caption)
            throws IOException, InterruptedException {
        final StringBuilder body = new StringBuilder()
                .append("media_type=CAROUSEL")
                .append("&children=").append(Http.encode(String.join(",", children)));
        if (caption != null && !caption.isBlank()) {
            body.append("&caption=").append(Http.encode(caption));
        }
        return idFrom(post("/" + account.igUserId() + "/media", body.toString()),
                      "carousel container");
    }

    private String publish(String creationId) throws IOException, InterruptedException {
        return idFrom(post("/" + account.igUserId() + "/media_publish",
                           "creation_id=" + Http.encode(creationId)),
                      "publish");
    }

    /**
     * How many posts remain in the rolling 24-hour window.
     *
     * <p>Worth asking before a post rather than discovering the limit as a
     * failure after the images have been written and fetched.</p>
     *
     * @return the quota used, or -1 when it cannot be read
     */
    public int postsUsedToday() {
        try {
            final String json = post("/" + account.igUserId() + "/content_publishing_limit"
                    + "?fields=quota_usage", "");
            final String usage = field(json, "quota_usage");
            return usage == null ? -1 : Integer.parseInt(usage);
        }
        catch (Exception e) {
            LOG.fine(() -> "Could not read the publishing limit: " + e);
            return -1;
        }
    }

    /**
     * POSTs to the API and returns the response body, error or not.
     *
     * <p>A rejection from Meta arrives as HTTP 400 carrying a JSON body that
     * names the cause. That body is the diagnosis, so it is handed on to
     * {@link #idFrom} to be read like any other response rather than being lost
     * behind the status code - which is what used to happen, leaving every
     * distinct mistake looking like the same bare "HTTP 400".</p>
     */
    private String post(String path, String body) throws IOException, InterruptedException {
        final String withToken = body.isEmpty()
                ? "access_token=" + Http.encode(account.accessToken())
                : body + "&access_token=" + Http.encode(account.accessToken());
        // The token goes in the body, never the query string, so it stays out of
        // the server's access log.
        try {
            return Http.postForm(URI.create(base + path), withToken);
        }
        catch (Http.HttpStatusException e) {
            if (field(e.body(), "message") != null) return e.body();
            throw e;
        }
    }

    /**
     * The {@code id} from a response, or the API's own error message.
     *
     * <p>Meta's errors are the useful part of this integration - "The image
     * could not be fetched" says exactly what a wrong public URL looks like -
     * so they are passed through rather than replaced with a generic failure.</p>
     */
    private static String idFrom(String json, String step) throws IOException {
        final String id = field(json, "id");
        if (id != null) return id;

        final String message = field(json, "message");
        final String detail = field(json, "error_user_msg");
        final String type = field(json, "type");

        final StringBuilder said = new StringBuilder(step).append(" failed");
        if (message == null) {
            said.append(": ").append(json);
        }
        else {
            said.append(": ").append(message);
            if (detail != null && !detail.equals(message)) said.append(" - ").append(detail);
            // OAuthException on a request that named the right account almost
            // always means the token was issued down the other login path.
            if ("OAuthException".equals(type)) {
                said.append(" (an OAuth error here usually means the token and the "
                        + "\"Token from\" setting disagree - a token issued by one of "
                        + "Meta's two login paths is refused by the other's host)");
            }
        }
        throw new IOException(said.toString());
    }

    /** A string or number field from a flat JSON object. */
    static String field(String json, String name) {
        if (json == null) return null;
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name)
                        + "\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))")
                .matcher(json);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
