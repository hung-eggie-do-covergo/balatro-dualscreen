package com.balatro.dualscreen;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import wmcloud.WmCloud;

/** Steam Cloud save sync around a play session: pull before the engine starts, push when it stops. */
final class SteamSync {
    private static final String TAG = "BalatroDS";
    private static final ExecutorService PUSHER = Executors.newSingleThreadExecutor();
    private static boolean configured;

    private SteamSync() {}

    static synchronized void configure(Context ctx) {
        if (configured) return;
        configured = true;
        ignoreJavaSteamCancellations();
        WmCloud.configure(new File(ctx.getFilesDir(), "wmcloud"), line -> Log.i(TAG, "steam: " + line));
    }

    /**
     * LOVE's save folder: internal storage, named by the game's identity, which LOVE takes from the
     * .love file name -- the embedded game.love or the assembled balatro-game.love (BalatroActivity).
     */
    static File saveDir(Context ctx) {
        String identity = GameAssembler.assembled(ctx) != null && !hasEmbeddedGame(ctx) ? "balatro-game" : "game";
        return new File(ctx.getFilesDir(), "save/" + identity);
    }

    private static boolean hasEmbeddedGame(Context ctx) {
        try {
            ctx.getAssets().open("game.love").close();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Uploads saves in the background once the game has stopped writing them. */
    static void pushAsync(Context ctx) {
        if (!WmCloud.isLoggedIn()) return;
        final File dir = saveDir(ctx);
        PUSHER.execute(() -> {
            try {
                waitForQuietSaves(dir);
                WmCloud.push(dir, false);
            } catch (Exception e) {
                // A changed cloud resurfaces as a conflict at the next launch's pull.
                Log.w(TAG, "steam: push skipped: " + e.getMessage());
            }
        });
    }

    /** Blocks until queued pushes finish, at most [seconds]. */
    static void awaitPushes(int seconds) {
        PUSHER.shutdown();
        try {
            PUSHER.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** The game writes saves from its own thread; never upload one mid-write. */
    private static void waitForQuietSaves(File dir) throws InterruptedException {
        // ponytail: mtime heuristic, 2 s of quiet capped at 15 s; a save-complete signal from Lua is the upgrade.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && newestSave(dir) > System.currentTimeMillis() - 2_000) {
            Thread.sleep(500);
        }
    }

    private static long newestSave(File dir) {
        long newest = 0;
        File[] profiles = dir.listFiles();
        if (profiles == null) return 0;
        for (File p : profiles) {
            File[] files = p.isDirectory() ? p.listFiles() : null;
            if (files == null) continue;
            for (File f : files) newest = Math.max(newest, f.lastModified());
        }
        return newest;
    }

    private static boolean fromJavaSteam(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("in.dragonbra") || f.getClassName().startsWith("kotlinx.coroutines")) return true;
        }
        return false;
    }

    /**
     * On disconnect JavaSteam cancels pending jobs and the CancellationException escapes on its network
     * thread, which kills an Android app. The waiting caller already sees the failure.
     */
    private static void ignoreJavaSteamCancellations() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof CancellationException && fromJavaSteam(e)) {
                Log.w(TAG, "steam: ignored cancellation on " + t.getName());
                return;
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
