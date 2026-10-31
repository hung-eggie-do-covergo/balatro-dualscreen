package com.balatro.dualscreen;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Installs APKs through Android's installer with this app as the installer: updates from GitHub
 * releases, and a reinstall of itself so it may set Samsung's game category hint (SetupActivity).
 * Adapted from wildermyth-android's WildermythUpdater.
 */
final class SelfInstaller {
    private static final String TAG = "BalatroDS";
    private static final String LATEST =
            "https://api.github.com/repos/hung-eggie-do-covergo/balatro-dualscreen/releases/latest";
    private static final String ACTION = "com.balatro.dualscreen.INSTALL_STATUS";

    static final class Update {
        final String version, url;
        final long size;

        Update(String version, String url, long size) {
            this.version = version;
            this.url = url;
            this.size = size;
        }
    }

    private SelfInstaller() {}

    /** True when this app installed itself, so it may set its own category hint. */
    static boolean isOwnInstaller(Context ctx) {
        try {
            String installer = Build.VERSION.SDK_INT >= 30
                    ? ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName()
                    : ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
            return ctx.getPackageName().equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    /** The latest release when it is newer than this app, else null. Blocking. */
    static Update check(Context ctx) throws Exception {
        JSONObject release;
        HttpURLConnection c = open(LATEST, "application/vnd.github+json");
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            release = new JSONObject(s.hasNext() ? s.next() : "{}");
        }
        String tag = release.optString("tag_name");
        String installed = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        if (compareVersions(tag, installed) <= 0) return null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                return new Update(tag, a.optString("browser_download_url"), a.optLong("size"));
            }
        }
        return null;
    }

    /** Compares dotted versions such as "v0.6.1" and "0.6"; anything unparsable sorts lowest. */
    static int compareVersions(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = (i < x.length ? x[i] : 0) - (i < y.length ? y[i] : 0);
            if (d != 0) return d;
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null || !v.matches("v?\\d+(\\.\\d+)*")) return new int[] {-1};
        String[] parts = v.replaceFirst("^v", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    /** Downloads the release APK into the cache; progress in percent. Blocking. */
    static File download(Context ctx, Update u, IntConsumer progress) throws Exception {
        File apk = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection c = open(u.url, "application/octet-stream");
        long total = u.size > 0 ? u.size : c.getContentLengthLong(), done = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[1 << 16];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != last) progress.accept(last = pct);
            }
        }
        if (total > 0 && done != total) throw new IOException("download incomplete");
        return apk;
    }

    /** Android needs "Install unknown apps" for this app before it can install anything. */
    static boolean mayInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    static void askToInstall(Activity activity) {
        activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + activity.getPackageName())));
    }

    /** Hands [apk] to Android's installer, which asks the player to confirm; [failed] hears why not. */
    static void install(Activity activity, File apk, IntConsumer failed) throws IOException {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            activity.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return; // reported again once the player answers
                    }
                    try {
                        activity.unregisterReceiver(this);
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        Log.w(TAG, "install: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                        failed.accept(status);
                    }
                }
            }, new IntentFilter(ACTION), Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
            Intent status = new Intent(ACTION).setPackage(activity.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(activity, id, status, flags).getIntentSender());
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // WmCloud.configure puts BouncyCastle first, which breaks the default TLS context; ask Android's own.
        if (c instanceof javax.net.ssl.HttpsURLConnection) {
            try {
                javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS", "AndroidOpenSSL");
                tls.init(null, null, null);
                ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(tls.getSocketFactory());
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "balatro-dualscreen-updater");
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true); // GitHub sends asset downloads to its CDN
        if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        return c;
    }
}
