/*
 * balatro-dualscreen-thor
 * Copyright (C) 2026  balatro-dualscreen-thor contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.balatro.dualscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wmcloud.ConflictException;
import wmcloud.WmCloud;
/**
 * First-launch setup: the release APK contains no Balatro, and this screen is
 * where the user's own copy becomes the game.
 *
 * It is the LAUNCHER activity. When a game is already present -- assembled on
 * an earlier visit, or embedded in a developer build -- it forwards straight
 * to BalatroActivity and is never seen. Otherwise it walks the user through:
 * pick the file (Storage Access Framework, so any location the phone can
 * reach works), probe and display what was picked, then assemble.
 *
 * Deliberately a plain Activity with a programmatic layout: it must carry no
 * game assets (rule #1 -- so no Balatro styling either), and it runs BEFORE
 * the engine, in the same process the engine will later occupy.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "BalatroDS";
    private static final int PICK_REQUEST = 41;

    private TextView status;
    private Button steamButton;
    private Button pickButton;
    private Button buildButton;
    private ProgressBar progress;

    private GameAssembler.Info info;

    // Pin-dialog handshake. requestPinShortcut opens the LAUNCHER'S OWN
    // confirmation activity, and starting the game immediately afterwards
    // covered it before the user could confirm, so no shortcut ever
    // appeared. The game now waits until that dialog
    // has come and gone: pinShown is set when we actually lose the
    // foreground to it, and onResume then continues.
    private boolean awaitingPin;
    private boolean pinShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fill the camera cutout band instead of leaving it black beside the felt.
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().getDecorView().setBackgroundColor(FELT);
        markAsGame();

        // A game already on board: only Steam Cloud is left to do.
        if (GameAssembler.assembled(this) != null || hasEmbeddedGame()) {
            beforeLaunch();
            return;
        }

        buildUi();
    }

    /**
     * Samsung's game list (Game Booster, Gaming Hub) ignores the manifest category and reads the
     * installer's category hint, which only the installing app may set. That holds when this app is
     * its own installer; from adb or a file manager the call is refused and nothing changes.
     */
    private void markAsGame() {
        try {
            getPackageManager().setApplicationCategoryHint(getPackageName(), android.content.pm.ApplicationInfo.CATEGORY_GAME);
        } catch (SecurityException ignored) {
        }
    }

    private boolean hasEmbeddedGame() {
        try {
            getAssets().open("game.love").close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void launchGame() {
        startActivity(new Intent(this, BalatroActivity.class));
        finish();
    }

    // ------------------------------------------------------------------
    // Steam Cloud
    // ------------------------------------------------------------------

    private static final String PREF_STEAM_DECLINED = "steam_declined";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private void beforeLaunch() {
        // Pulling under a running game would overwrite saves it holds open.
        if (BalatroActivity.isRunning()) {
            launchGame();
            return;
        }
        SteamSync.configure(this);
        if (WmCloud.isLoggedIn()) {
            syncThenLaunch();
        } else if (getPreferences(MODE_PRIVATE).getBoolean(PREF_STEAM_DECLINED, false)) {
            launchGame();
        } else {
            panel("Steam Cloud saves", "Sign in once to keep your runs and progress in step with Steam on PC.",
                    null, false,
                    "Sign in with Steam", () -> signIn(this::syncThenLaunch, this::launchGame),
                    "Not now", () -> {
                        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_STEAM_DECLINED, true).apply();
                        launchGame();
                    });
        }
    }

    private static final String SCAN = "Open the Steam app on your phone, tap the shield (Steam Guard) and scan this code.";

    /** Bumped per sign-in attempt; a superseded attempt's callbacks are ignored. */
    private int loginGen;

    /** QR sign-in, then [then]; [cancel] if the player backs out. */
    private void signIn(Runnable then, Runnable cancel) {
        final int gen = ++loginGen;
        final Runnable retry = () -> signIn(then, cancel);
        panel("Sign in with Steam", "Getting a sign-in code…", null, true, null, null, null, null);
        // Own thread, not the worker: a stale attempt keeps blocking until Steam gives up on it.
        new Thread(() -> {
            try {
                WmCloud.login(url -> runOnUiThread(() -> {
                    if (gen == loginGen) panel("Sign in with Steam", SCAN, qrBitmap(url), false,
                            "New code", retry, "Cancel", () -> { loginGen++; cancel.run(); });
                }));
                runOnUiThread(() -> { if (gen == loginGen) then.run(); });
            } catch (Exception e) {
                Log.w(TAG, "steam sign-in failed", e);
                runOnUiThread(() -> {
                    if (gen == loginGen) panel("Sign-in failed", e.getMessage(), null, false,
                            "Try again", retry, "Back", cancel);
                });
            }
        }, "steam-login").start();
    }

    /** Fetches only Balatro.exe from the Windows depot -- the game data rides inside it -- then builds. */
    private void downloadFromSteam() {
        SteamSync.configure(this);
        if (!WmCloud.isLoggedIn()) {
            signIn(() -> { buildUi(); downloadFromSteam(); }, this::buildUi);
            return;
        }
        steamButton.setEnabled(false);
        pickButton.setEnabled(false);
        buildButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Downloading Balatro.exe from Steam…");
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "steam");
                WmCloud.downloadFiles(dir, java.util.Collections.singleton("Balatro.exe"),
                        pct -> runOnUiThread(() -> progress.setProgress(Math.round(pct))));
                GameAssembler.Info probed = GameAssembler.probe(this, new File(dir, "Balatro.exe"));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    steamButton.setEnabled(true);
                    pickButton.setEnabled(true);
                    info = probed;
                    showProbe("Balatro.exe from Steam", probed);
                    if (probed.usable()) startBuild();
                });
            } catch (Exception e) {
                Log.w(TAG, "steam download failed", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    steamButton.setEnabled(true);
                    pickButton.setEnabled(true);
                    status.setText("Steam download failed:\n  " + e.getMessage());
                });
            }
        }, "steam-download").start();
    }

    private void syncThenLaunch() {
        panel("Steam Cloud", "Syncing saves…", null, true, null, null, null, null);
        worker.execute(() -> {
            try {
                WmCloud.pull(SteamSync.saveDir(this), false);
                runOnUiThread(this::launchGame);
            } catch (ConflictException e) {
                runOnUiThread(this::conflict);
            } catch (Exception e) {
                Log.w(TAG, "steam pull failed", e);
                runOnUiThread(() -> panel("Can't reach Steam", "Your saves will sync next time.", null, false,
                        "Play offline", this::launchGame, "Retry", this::syncThenLaunch));
            }
        });
    }

    /** Both sides changed since the last sync. The losing side is backed up either way. */
    private void conflict() {
        panel("Which saves?", "Saves changed both on this device and in Steam Cloud. "
                        + "This device last saved " + lastSaved() + ". The copy you don't pick is kept as a backup.",
                null, false,
                "Use Steam Cloud", () -> resolve(false),
                "Keep this device…", this::confirmOverwrite);
    }

    /** Overwriting the cloud replaces the PC's saves too, so it never happens on one tap. */
    private void confirmOverwrite() {
        Button overwrite = panel("Overwrite Steam Cloud?", "Your saves on PC and every other device will be "
                        + "replaced by this device's. The current cloud copy is backed up on this device only.",
                null, false,
                "Go back", this::conflict,
                "Overwrite cloud", () -> resolve(true));
        overwrite.setAlpha(0.4f);
        overwrite.postDelayed(() -> overwrite.setEnabled(false), 0);
        overwrite.postDelayed(() -> { overwrite.setEnabled(true); overwrite.setAlpha(1f); }, 1500);
    }

    private String lastSaved() {
        long newest = 0;
        File[] profiles = SteamSync.saveDir(this).listFiles();
        if (profiles != null) {
            for (File p : profiles) {
                File[] files = p.listFiles();
                if (files != null) for (File f : files) newest = Math.max(newest, f.lastModified());
            }
        }
        return newest == 0 ? "never"
                : android.text.format.DateUtils.getRelativeTimeSpanString(newest).toString().toLowerCase();
    }

    private void resolve(boolean keepDevice) {
        panel("Steam Cloud", keepDevice ? "Uploading this device's saves…" : "Downloading saves from Steam Cloud…",
                null, true, null, null, null, null);
        worker.execute(() -> {
            try {
                File dir = SteamSync.saveDir(this);
                if (keepDevice) WmCloud.push(dir, true); else WmCloud.pull(dir, true);
                runOnUiThread(this::launchGame);
            } catch (Exception e) {
                Log.w(TAG, "steam resolve failed", e);
                runOnUiThread(() -> panel("Sync failed", e.getMessage(), null, false,
                        "Retry", () -> resolve(keepDevice), "Play offline", this::launchGame));
            }
        });
    }

    // Balatro's own UI blue and red, on the setup screen's felt green.
    private static final int FELT = Color.rgb(20, 32, 26);
    private static final int CARD = Color.rgb(36, 52, 46);
    private static final int BLUE = Color.rgb(0, 157, 255);
    private static final int RED = Color.rgb(254, 95, 85);
    private static final int ORANGE = Color.rgb(253, 162, 0);

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int colour, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Solid rounded fill; a white outline marks controller focus. */
    private StateListDrawable buttonBackground(int colour) {
        GradientDrawable focused = rounded(colour, 10);
        focused.setStroke(dp(3), Color.WHITE);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, focused);
        d.addState(new int[] {}, rounded(colour, 10));
        return d;
    }

    /**
     * A centred card: optional QR tile, title, message, spinner, and up to two buttons. Returns the
     * second button, or null. Buttons ignore taps for a moment so one meant for the card being
     * replaced cannot land on this one.
     */
    private Button panel(String title, String message, Bitmap qr, boolean busy,
                       String a, Runnable runA, String b, Runnable runB) {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(CARD, 18));
        card.setPadding(dp(28), dp(24), dp(28), dp(24));

        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            iv.setBackground(rounded(Color.WHITE, 12));
            iv.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(200));
            lp.rightMargin = dp(28);
            card.addView(iv, lp);
        }

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        card.addView(text, new LinearLayout.LayoutParams(dp(qr != null ? 320 : 420),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        text.addView(t);

        TextView m = new TextView(this);
        m.setText(message);
        m.setTextSize(16);
        m.setLineSpacing(0, 1.15f);
        m.setTextColor(Color.rgb(200, 212, 206));
        m.setPadding(0, dp(8), 0, 0);
        text.addView(m);

        if (busy) {
            ProgressBar spin = new ProgressBar(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.topMargin = dp(16);
            text.addView(spin, lp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button second = null;
        for (int i = 0; i < 2; i++) {
            final String label = i == 0 ? a : b;
            final Runnable run = i == 0 ? runA : runB;
            if (label == null) continue;
            Button btn = new Button(this);
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(16);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setTextColor(Color.WHITE);
            btn.setBackground(buttonBackground(i == 0 ? BLUE : RED));
            btn.setStateListAnimator(null);
            btn.setPadding(dp(20), 0, dp(20), 0);
            btn.setOnClickListener(v -> run.run());
            btn.setEnabled(false);
            final boolean primary = i == 0;
            btn.postDelayed(() -> {
                btn.setEnabled(true);
                if (primary) btn.requestFocus(); // controller users land on the main action
            }, 600);
            second = btn;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
            lp.rightMargin = dp(12);
            row.addView(btn, lp);
        }
        if (row.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(20);
            text.addView(row, lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(FELT);
        root.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        return b != null ? second : null;
    }

    private Bitmap qrBitmap(String url) {
        boolean[][] m = WmCloud.qrMatrix(url);
        Bitmap bmp = Bitmap.createBitmap(m.length, m.length, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.WHITE);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m[y].length; x++) {
                if (m[y][x]) bmp.setPixel(x, y, Color.BLACK);
            }
        }
        // Nearest-neighbour upscale keeps modules crisp; ImageView scales it into its tile.
        return Bitmap.createScaledBitmap(bmp, m.length * 8, m.length * 8, false);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 18));
        card.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView title = new TextView(this);
        title.setText("Balatro Dual Screen");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        card.addView(title);

        addBody(card, "This app contains no Balatro. Download your own copy from Steam, "
                + "or choose a Balatro.exe, a zipped Balatro.app or a Balatro.love you own.");
        addBody(card, "Built and tested against Steam 1.0.1o. Newer PC versions get a "
                + "warning and usually work; console, mobile and demo builds do not.");
        addBody(card, "Free and unofficial. If you paid for this, ask for a refund.");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        steamButton = styledButton("Download from Steam", BLUE);
        steamButton.setOnClickListener(v -> downloadFromSteam());
        row.addView(steamButton, rowParams());
        pickButton = styledButton("Choose a file", ORANGE);
        pickButton.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK_REQUEST);
        });
        row.addView(pickButton, rowParams());
        buildButton = styledButton("Start build", BLUE);
        buildButton.setOnClickListener(v -> startBuild());
        buildButton.setVisibility(View.GONE); // shown once a file passes the check
        row.addView(buildButton, rowParams());
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(20);
        card.addView(row, rowLp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = dp(16);
        card.addView(progress, pLp);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        status.setTextColor(Color.rgb(170, 186, 178));
        status.setPadding(0, dp(12), 0, 0);
        card.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout root = new FrameLayout(this);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        int width = Math.min(dp(620), getResources().getDisplayMetrics().widthPixels - dp(32));
        root.addView(card, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        scroll.addView(root);
        scroll.setBackgroundColor(FELT);
        setContentView(scroll);
        steamButton.requestFocus();
    }

    private Button styledButton(String label, int colour) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(16);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setBackground(buttonBackground(colour));
        btn.setStateListAnimator(null);
        btn.setPadding(dp(20), 0, dp(20), 0);
        return btn;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        lp.rightMargin = dp(12);
        return lp;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void addBody(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(Color.rgb(200, 212, 206));
        tv.setPadding(0, dp(10), 0, 0);
        root.addView(tv);
    }

    // ------------------------------------------------------------------
    // Pick -> copy -> probe
    // ------------------------------------------------------------------

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_REQUEST || result != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        status.setText("Reading the selected file…");
        pickButton.setEnabled(false);
        buildButton.setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                probeInBackground(uri);
            }
        }, "balatro-probe").start();
    }

    private void probeInBackground(Uri uri) {
        String name = displayName(uri);
        GameAssembler.Info result = null;
        String failure = null;
        try {
            File cached = new File(getCacheDir(), "balatro-source.bin");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cached)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
            }
            result = GameAssembler.probe(this, cached);
            // Zipped .app containers also carry the game's icon; stash it for
            // the home-screen shortcut offer after the build. Best-effort.
            IconExtractor.tryExtract(this, cached);
        } catch (Exception e) {
            Log.w(TAG, "setup: copy/probe failed", e);
            failure = e.getMessage();
        }

        final GameAssembler.Info probed = result;
        final String error = failure;
        final String displayName = name;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                pickButton.setEnabled(true);
                if (error != null || probed == null) {
                    status.setText("Could not read that file:\n  " + error);
                    return;
                }
                info = probed;
                showProbe(displayName, probed);
            }
        });
    }

    private void showProbe(String name, GameAssembler.Info p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Selected: ").append(name).append('\n');
        if (p.problem != null) {
            sb.append("\n✗ ").append(p.problem);
            buildButton.setEnabled(false);
            buildButton.setVisibility(View.GONE);
        } else {
            sb.append("Detected: ").append(p.kind).append('\n');
            sb.append("Version:  ").append(p.versionDisplay)
              .append(" (").append(p.versionNumber).append(", ")
              .append(p.versionBuild).append(")\n");
            sb.append("Files:    ").append(p.fileCount).append('\n');
            if (!"1.0.1o".equals(p.versionNumber)) {
                sb.append("\n⚠ This is not 1.0.1o. The build can go "
                        + "ahead, but this version is untested and things "
                        + "may be broken.\n");
            }
            sb.append("\n✓ This looks buildable.");
            buildButton.setEnabled(true);
            buildButton.setVisibility(View.VISIBLE);
        }
        status.setText(sb.toString());
    }

    /**
     * Offer a pinned home-screen shortcut, then start the game.
     *
     * The shortcut is the one place Android accepts a RUNTIME icon
     * (Icon.createWithBitmap and friends) -- so when the container was a
     * zipped .app, the shortcut can wear the game's own icon, extracted from
     * the user's copy and never leaving the device. The APK's launcher icon
     * itself stays this project's original artwork; a packaged resource
     * cannot be swapped at runtime, and packaging the real one would mean
     * distributing it.
     *
     * The extracted art is re-composed 14% inset over its own dark backing
     * before createWithAdaptiveBitmap: a full-bleed adaptive bitmap is
     * masked to its central ~2/3, the same over-zoom the APK icon pipeline
     * hit and solved the same way.
     */
    private void offerShortcutThenLaunch() {
        if (Build.VERSION.SDK_INT < 26) {
            beforeLaunch();
            return;
        }
        final ShortcutManager sm =
                getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            beforeLaunch();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Add to home screen?")
                .setMessage("Add a Balatro shortcut to your home screen?"
                        + (IconExtractor.extracted(this) != null
                           ? " It will use the game's own icon, taken from"
                             + " your copy."
                           : ""))
                .setPositiveButton("Add", new DialogInterface
                        .OnClickListener() {
                    @Override public void onClick(
                            DialogInterface d, int w) {
                        awaitingPin = true;
                        pinShown = false;
                        status.setText("Confirm the shortcut, then Balatro"
                                + " will start…");
                        requestPin(sm);
                        // A launcher that silently ignores the request would
                        // otherwise strand us here.
                        status.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (awaitingPin) {
                                    awaitingPin = false;
                                    beforeLaunch();
                                }
                            }
                        }, 20000);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface
                        .OnClickListener() {
                    @Override public void onClick(
                            DialogInterface d, int w) {
                        beforeLaunch();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (awaitingPin) {
            pinShown = true;      // the launcher's dialog took the foreground
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingPin && pinShown) {
            awaitingPin = false;
            beforeLaunch();
        }
    }

    private void requestPin(ShortcutManager sm) {
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setClass(this, SetupActivity.class);

            ShortcutInfo.Builder b =
                    new ShortcutInfo.Builder(this, "balatro")
                            .setShortLabel("Balatro")
                            .setLongLabel("Balatro Dual Screen")
                            .setIntent(launch);

            File iconFile = IconExtractor.extracted(this);
            if (iconFile != null) {
                Bitmap art =
                        BitmapFactory.decodeFile(
                                iconFile.getAbsolutePath());
                if (art != null) {
                    int s = 432;
                    Bitmap composed =
                            Bitmap.createBitmap(s, s,
                                    Bitmap.Config.ARGB_8888);
                    Canvas c =
                            new Canvas(composed);
                    c.drawColor(0xFF120A08);   // dark backing, never visible
                    int inset = (int) (s * 0.14f);
                    Rect dst = new Rect(
                            inset, inset, s - inset, s - inset);
                    // NEAREST-NEIGHBOUR for small sources. The .exe's
                    // Balatro icon is 32 px pixel art (its bigger entries
                    // are LOVE's heart, see IconExtractor); smoothing that
                    // up to 432 px turns it to mush, while hard pixels read
                    // as intentional. The .app's 1024 px art is filtered
                    // normally on the way down.
                    boolean pixelArt = art.getWidth() <= 64;
                    Paint p = new Paint();
                    p.setFilterBitmap(!pixelArt);
                    p.setAntiAlias(!pixelArt);
                    c.drawBitmap(art, null, dst, p);
                    b.setIcon(Icon
                            .createWithAdaptiveBitmap(composed));
                }
            }
            sm.requestPinShortcut(b.build(), null);
        } catch (Exception e) {
            Log.w(TAG, "shortcut pin failed", e);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return "(unnamed file)";
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    private void startBuild() {
        if (info == null || !info.usable()) {
            return;
        }
        pickButton.setEnabled(false);
        buildButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(2);
        status.setText("Building…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    GameAssembler.assemble(SetupActivity.this, info,
                            new GameAssembler.Progress() {
                        @Override public void update(final String msg,
                                                     final int pct) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    status.setText(msg);
                                    progress.setProgress(pct);
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setProgress(100);
                            status.setText("Done.");
                            deleteTree(new File(getCacheDir(), "steam"));
                            offerShortcutThenLaunch();
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "setup: build failed", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            pickButton.setEnabled(true);
                            buildButton.setEnabled(true);
                            status.setText("Build failed:\n  " + e.getMessage()
                                    + "\n\nNothing was left half-built; try again.");
                        }
                    });
                }
            }
        }, "balatro-assemble").start();
    }
}
