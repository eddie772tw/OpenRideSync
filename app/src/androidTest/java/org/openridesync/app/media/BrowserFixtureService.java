package org.openridesync.app.media;

import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import java.util.ArrayList;
import java.util.List;

/** Test APK process only: no Kotlin runtime from the instrumented target is available here.
 * Playback commands change PAUSED position and never claim audible playback. */
public final class BrowserFixtureService extends MediaBrowserService {
    public static final String ACTIVATE_PRIMARY = "org.openridesync.app.test.ACTIVATE_PRIMARY";
    public static final String DEACTIVATE_PRIMARY = "org.openridesync.app.test.DEACTIVATE_PRIMARY";
    public static final String SPAWN_OTHER = "org.openridesync.app.test.SPAWN_OTHER";
    public static final String DESTROY_PRIMARY = "org.openridesync.app.test.DESTROY_PRIMARY";
    public static final String CLOSED = "org.openridesync.app.test.BROWSER_CLOSED";
    public static final String CREATED_AT = "created_at";
    public static final String OTHER_PLAYED = "other_played";

    private final long createdAt = SystemClock.elapsedRealtime();
    private MediaSession primary;
    private MediaSession other;
    private boolean otherPlayed;

    @Override public void onCreate() {
        super.onCreate();
        final MediaSession session = new MediaSession(this, "browser-fixture");
        primary = session;
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { session.setPlaybackState(paused(1)); }
            @Override public void onCustomAction(String action, Bundle extras) {
                switch (action) {
                    case ACTIVATE_PRIMARY:
                        session.setActive(true);
                        break;
                    case DEACTIVATE_PRIMARY:
                        session.setActive(false);
                        break;
                    case SPAWN_OTHER:
                        if (other == null) {
                            final MediaSession second = new MediaSession(BrowserFixtureService.this, "browser-fixture-other");
                            other = second;
                            second.setCallback(new MediaSession.Callback() {
                                @Override public void onPlay() {
                                    otherPlayed = true;
                                    second.setPlaybackState(paused(99));
                                }
                            }, new Handler(Looper.getMainLooper()));
                            second.setPlaybackState(paused(0));
                            second.setActive(true);
                        }
                        break;
                    case DESTROY_PRIMARY:
                        if (primary != null) primary.release();
                        primary = null;
                        break;
                    default:
                        break;
                }
            }
        }, new Handler(Looper.getMainLooper()));
        session.setPlaybackState(paused(0));
        session.setActive(false);
        setSessionToken(session.getSessionToken());
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return "org.openridesync.app".equals(clientPackageName) ? new BrowserRoot("fixture", null) : null;
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }

    @Override public void onDestroy() {
        try {
            if (primary != null) primary.release();
            if (other != null) other.release();
        } finally {
            primary = null;
            other = null;
            sendBroadcast(new Intent(CLOSED).setPackage("org.openridesync.app")
                    .putExtra(CREATED_AT, createdAt).putExtra(OTHER_PLAYED, otherPlayed));
            super.onDestroy();
        }
    }

    private static PlaybackState paused(long position) {
        return new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY)
                .setState(PlaybackState.STATE_PAUSED, position, 0f).build();
    }
}
