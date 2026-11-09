package com.reaper357.loonietunes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Foreground service that owns the system MediaSession and the playback notification.
 * Audio itself still plays in the web view; this only mirrors state and forwards button presses.
 * Uses framework classes only, so no extra Gradle dependencies are needed.
 */
public class MediaPlaybackService extends Service {

    static final String CHANNEL_ID = "loonie_playback";
    static final int NOTIFICATION_ID = 357;

    static final String ACTION_PLAY = "com.reaper357.loonietunes.action.PLAY";
    static final String ACTION_PAUSE = "com.reaper357.loonietunes.action.PAUSE";
    static final String ACTION_NEXT = "com.reaper357.loonietunes.action.NEXT";
    static final String ACTION_PREV = "com.reaper357.loonietunes.action.PREV";
    static final String ACTION_STOP = "com.reaper357.loonietunes.action.STOP";

    /** Everything the notification and session need, replaced as a whole on each update. */
    static class Snapshot {
        String title = "";
        String artist = "";
        String album = "";
        String artKey = "";
        long durationMs;
        long positionMs;
        boolean playing;
        float speed = 1f;
        Bitmap art;
    }

    static volatile Snapshot snapshot;
    private static volatile MediaPlaybackService instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSession session;
    private NotificationManager notificationManager;
    private boolean inForeground = false;
    private String metadataKey = "";

    // ------------------------------------------------------------ static entry points

    static void push(Context context, Snapshot s) {
        snapshot = s;
        final MediaPlaybackService running = instance;
        if (running != null) {
            // Already running: update in place (no new service start, so this also works with the screen off)
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    running.refresh();
                }
            });
            return;
        }
        try {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // Android may refuse a first start while the app is in the background; the next update retries.
        }
    }

    static void stop(Context context) {
        snapshot = null;
        final MediaPlaybackService running = instance;
        if (running != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    running.shutdown();
                }
            });
        }
    }

    // ------------------------------------------------------------ service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback controls for Loonie Tunes");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        session = new MediaSession(this, "LoonieTunes");
        if (Build.VERSION.SDK_INT < 33) {
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                MediaControlsPlugin.emit("play", -1);
            }

            @Override
            public void onPause() {
                MediaControlsPlugin.emit("pause", -1);
            }

            @Override
            public void onStop() {
                MediaControlsPlugin.emit("pause", -1);
            }

            @Override
            public void onSkipToNext() {
                MediaControlsPlugin.emit("next", -1);
            }

            @Override
            public void onSkipToPrevious() {
                MediaControlsPlugin.emit("previous", -1);
            }

            @Override
            public void onSeekTo(long positionMs) {
                MediaControlsPlugin.emit("seekto", positionMs / 1000.0);
            }
        }, new Handler(Looper.getMainLooper()));
        session.setSessionActivity(openAppIntent());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            MediaControlsPlugin.emit("play", -1);
        } else if (ACTION_PAUSE.equals(action) || ACTION_STOP.equals(action)) {
            MediaControlsPlugin.emit("pause", -1);
        } else if (ACTION_NEXT.equals(action)) {
            MediaControlsPlugin.emit("next", -1);
        } else if (ACTION_PREV.equals(action)) {
            MediaControlsPlugin.emit("previous", -1);
        } else {
            refresh(); // first start: must call startForeground quickly
            return START_NOT_STICKY;
        }
        if (snapshot == null) shutdown(); // button pressed but the app is gone
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (session != null) {
            try {
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ session + notification

    private void refresh() {
        Snapshot s = snapshot;
        if (s == null || session == null) {
            shutdown();
            return;
        }
        applyToSession(s);
        Notification notification = buildNotification(s);
        if (!inForeground) {
            startForegroundCompat(notification);
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void applyToSession(Snapshot s) {
        String key = s.title + "\n" + s.artist + "\n" + s.album + "\n" + s.durationMs + "\n" + s.artKey + "\n" + (s.art != null);
        if (!key.equals(metadataKey)) {
            metadataKey = key;
            MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs);
            if (s.art != null) {
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, s.art);
                meta.putBitmap(MediaMetadata.METADATA_KEY_ART, s.art);
            }
            session.setMetadata(meta.build());
        }

        long actions = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SEEK_TO;
        PlaybackState state = new PlaybackState.Builder()
            .setActions(actions)
            .setState(
                s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                s.positionMs,
                s.playing ? s.speed : 0f,
                SystemClock.elapsedRealtime())
            .build();
        session.setPlaybackState(state);
        session.setActive(true);
    }

    private Notification.Builder newBuilder() {
        return Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(Snapshot s) {
        Notification.Builder b = newBuilder();
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.title.isEmpty() ? "Loonie Tunes" : s.title)
            .setContentText(s.artist)
            .setSubText(s.album)
            .setLargeIcon(s.art)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 5))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }

        b.addAction(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(ACTION_PREV, 1));
        if (s.playing) {
            b.addAction(android.R.drawable.ic_media_pause, "Pause", servicePendingIntent(ACTION_PAUSE, 2));
        } else {
            b.addAction(android.R.drawable.ic_media_play, "Play", servicePendingIntent(ACTION_PLAY, 3));
        }
        b.addAction(android.R.drawable.ic_media_next, "Next", servicePendingIntent(ACTION_NEXT, 4));

        b.setStyle(new Notification.MediaStyle()
            .setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private Notification placeholderNotification() {
        return newBuilder()
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Loonie Tunes")
            .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        inForeground = true;
    }

    private void shutdown() {
        snapshot = null;
        if (!inForeground) {
            // A service started with startForegroundService must still call startForeground once.
            try {
                startForegroundCompat(placeholderNotification());
            } catch (Exception ignored) {
            }
        }
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    // ------------------------------------------------------------ pending intents

    private PendingIntent openAppIntent() {
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
