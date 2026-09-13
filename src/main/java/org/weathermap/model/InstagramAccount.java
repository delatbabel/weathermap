package org.weathermap.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the application needs to publish to one Instagram account.
 *
 * <h2>There is no password here, and there cannot be</h2>
 *
 * <p>Instagram has no supported way to post with a username and a password.
 * Publishing goes through Meta's Content Publishing API, which authenticates
 * with an OAuth access token issued to an app, against an Instagram
 * <em>professional</em> account linked to a Facebook Page. Tools that take a
 * password drive the private mobile API instead: that breaches the Terms of Use
 * and is a good way to have the account disabled, which is a poor trade for
 * skipping a one-off setup. {@code docs/instagram.md} walks through obtaining
 * the token.</p>
 *
 * <h2>Why a public URL is part of the credentials</h2>
 *
 * <p>The API does not accept an uploaded image. Every photo is passed as a URL
 * that Meta's servers fetch, so a chart on this machine has to be somewhere
 * public before it can be posted. {@code publishDir} is where the application
 * writes the images; {@code publicBaseUrl} is the address that same directory is
 * served at. Getting that pairing wrong is the commonest failure, and it
 * surfaces as an opaque error from Meta rather than anything local, so the
 * dialog checks it before posting.</p>
 *
 * @param login         which of Meta's two paths the token came from, which
 *                      decides the host the API is called on
 * @param profile       the @handle, for display only
 * @param igUserId      the Instagram professional account's numeric ID
 * @param accessToken   a long-lived user token, or better a Page token, which
 *                      does not expire
 * @param publishDir    where the application writes images to be fetched
 * @param publicBaseUrl the URL that {@code publishDir} is served at
 * @param syncCommand   run after the images are written and before they are
 *                      posted, for a folder that is uploaded rather than served
 *                      directly; may be blank
 * @param caption       the last caption posted, kept so the next one starts
 *                      from it rather than from nothing - a daily chart's text
 *                      is usually yesterday's with the date and a line changed,
 *                      and retyping it every day was the whole cost
 */
public record InstagramAccount(Login login, String profile, String igUserId,
                               String accessToken, Path publishDir, String publicBaseUrl,
                               String syncCommand, String caption) {

    /**
     * The two ways Meta issues a publishing token, which are not interchangeable.
     *
     * <p>They differ in more than branding: the token comes from a different
     * dashboard, carries different scopes, identifies the account by a different
     * ID, and - the part that actually breaks things - is accepted by a
     * different host. A token from one used against the other's host fails as an
     * authentication error, which reads like a bad token rather than a wrong
     * address.</p>
     */
    public enum Login {

        /**
         * Instagram Login. No Facebook Page, no login flow to build: the token
         * is generated in the App Dashboard for an account you own, which is all
         * Standard Access allows and all a single-account publisher needs.
         */
        INSTAGRAM("Instagram Login", "https://graph.instagram.com/v25.0",
                  "instagram_business_basic, instagram_business_content_publish"),

        /**
         * Facebook Login. The Instagram account is linked to a Facebook Page and
         * the token is a Page token, which does not expire - worth the extra
         * setup only if you already work through a Page.
         */
        FACEBOOK("Facebook Login", "https://graph.facebook.com/v21.0",
                 "instagram_basic, instagram_content_publish, pages_show_list, "
                 + "pages_read_engagement");

        private final String label;
        private final String apiBase;
        private final String scopes;

        Login(String label, String apiBase, String scopes) {
            this.label = label;
            this.apiBase = apiBase;
            this.scopes = scopes;
        }

        public String label() { return label; }

        /** The only host that will accept a token issued down this path. */
        public String apiBase() { return apiBase; }

        public String scopes() { return scopes; }

        @Override
        public String toString() { return label; }
    }

    private static final Logger LOG = Logger.getLogger(InstagramAccount.class.getName());

    /** Separate from the preferences, because this file holds a bearer token. */
    public static final String FILE = "instagram.properties";

    private static final String KEY_LOGIN = "instagram.login";
    private static final String KEY_PROFILE = "instagram.profile";
    private static final String KEY_USER_ID = "instagram.userId";
    private static final String KEY_TOKEN = "instagram.accessToken";
    private static final String KEY_DIR = "instagram.publishDir";
    private static final String KEY_URL = "instagram.publicBaseUrl";
    private static final String KEY_SYNC = "instagram.syncCommand";
    private static final String KEY_CAPTION = "instagram.caption";

    public InstagramAccount {
        login = login == null ? Login.INSTAGRAM : login;
        profile = profile == null ? "" : profile.trim();
        igUserId = igUserId == null ? "" : igUserId.trim();
        accessToken = accessToken == null ? "" : accessToken.trim();
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        syncCommand = syncCommand == null ? "" : syncCommand.trim();
        // Not trimmed: a caption's own leading blank line is deliberate.
        caption = caption == null ? "" : caption;
    }

    /** True when every field needed to post is present. */
    public boolean isComplete() {
        return !igUserId.isEmpty() && !accessToken.isEmpty()
                && publishDir != null && !publicBaseUrl.isEmpty();
    }

    /** The same account with a different caption remembered. */
    public InstagramAccount withCaption(String text) {
        return new InstagramAccount(login, profile, igUserId, accessToken,
                                    publishDir, publicBaseUrl, syncCommand, text);
    }

    /** True when the images have to be pushed somewhere before they are fetched. */
    public boolean hasSyncCommand() { return !syncCommand.isEmpty(); }

    /** The public URL a file written into {@link #publishDir} will be served at. */
    public String urlFor(String fileName) {
        return publicBaseUrl + "/" + fileName;
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), Preferences.CONFIG_DIR, FILE);
    }

    public static Optional<InstagramAccount> load(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        final Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + file, e);
            return Optional.empty();
        }
        final String dir = props.getProperty(KEY_DIR, "");
        return Optional.of(new InstagramAccount(
                loginFrom(props.getProperty(KEY_LOGIN)),
                props.getProperty(KEY_PROFILE, ""),
                props.getProperty(KEY_USER_ID, ""),
                props.getProperty(KEY_TOKEN, ""),
                dir.isBlank() ? null : Path.of(dir),
                props.getProperty(KEY_URL, ""),
                props.getProperty(KEY_SYNC, ""),
                props.getProperty(KEY_CAPTION, "")));
    }

    /**
     * Writes the account, readable only by its owner.
     *
     * <p>The token is a bearer credential in a plain file, which is worth being
     * explicit about: anything that can read it can post as this account until
     * the token is revoked. The file mode is narrowed where the filesystem
     * supports it, and the application never logs the value.</p>
     */
    public void save(Path file) throws IOException {
        final Properties props = new Properties();
        props.setProperty(KEY_LOGIN, login.name());
        props.setProperty(KEY_PROFILE, profile);
        props.setProperty(KEY_USER_ID, igUserId);
        props.setProperty(KEY_TOKEN, accessToken);
        props.setProperty(KEY_DIR, publishDir == null ? "" : publishDir.toString());
        props.setProperty(KEY_URL, publicBaseUrl);
        props.setProperty(KEY_SYNC, syncCommand);
        props.setProperty(KEY_CAPTION, caption);

        final Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var out = Files.newOutputStream(file)) {
            props.store(out, "weathermap - Instagram publishing credentials. "
                    + "Contains an access token: keep it private.");
        }
        restrictPermissions(file);
    }

    /** An unreadable or unknown value falls back to the simpler path. */
    private static Login loginFrom(String stored) {
        if (stored == null) return Login.INSTAGRAM;
        try {
            return Login.valueOf(stored.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            LOG.warning("Unknown login type " + stored + "; assuming Instagram Login");
            return Login.INSTAGRAM;
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            final Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        }
        catch (UnsupportedOperationException | IOException e) {
            // Not POSIX, or the filesystem refused: worth a note, not a failure.
            LOG.fine(() -> "Could not restrict permissions on " + file + ": " + e);
        }
    }

    /** Never print the token. */
    @Override
    public String toString() {
        return "InstagramAccount[" + (profile.isEmpty() ? igUserId : profile)
                + ", " + login.label()
                + ", token " + (accessToken.isEmpty() ? "absent" : "present") + "]";
    }
}
