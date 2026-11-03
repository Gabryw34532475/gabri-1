package com.annunci.volo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Riproduce gli annunci in background e mostra la notifica multimediale
 * (indietro / play-pausa / avanti / chiudi), come Spotify.
 * Non chiede il "focus audio": cosi' l'audio del gioco resta acceso
 * e l'annuncio si sente insieme ai suoni del simulatore.
 */
public class PlayerService extends Service {

    public static final String ACTION_PLAY_LIST = "com.annunci.volo.PLAY_LIST";
    public static final String ACTION_TOGGLE = "com.annunci.volo.TOGGLE";
    public static final String ACTION_NEXT = "com.annunci.volo.NEXT";
    public static final String ACTION_PREV = "com.annunci.volo.PREV";
    public static final String ACTION_STOP = "com.annunci.volo.STOP";
    public static final String ACTION_STATE = "com.annunci.volo.STATE";

    public static volatile boolean running = false;
    public static volatile boolean playing = false;
    public static volatile String currentId = null;
    public static volatile String currentTitle = "";
    public static volatile String currentSub = "";

    private static final String CHANNEL_ID = "player";
    private static final int NOTIF_ID = 1;

    private MediaSession session;
    private MediaPlayer mp;
    private boolean ready = false;
    private boolean stopping = false;
    private final List<Library.Track> queue = new ArrayList<>();
    private int index = 0;
    private Library lib;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        lib = new Library(this);

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Annunci di volo", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);

        session = new MediaSession(this, "AnnunciVolo");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!playing) {
                    toggle();
                }
            }

            @Override
            public void onPause() {
                if (playing) {
                    toggle();
                }
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onStop() {
                shutdown();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();

        // Obbligatorio: il servizio deve mostrare subito la notifica.
        startForeground(NOTIF_ID, buildNotification());

        if (ACTION_STOP.equals(a)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_LIST.equals(a)) {
            loadQueue(intent);
        } else if (ACTION_TOGGLE.equals(a)) {
            toggle();
        } else if (ACTION_NEXT.equals(a)) {
            next();
        } else if (ACTION_PREV.equals(a)) {
            prev();
        }

        if (queue.isEmpty()) {
            shutdown();
        } else {
            updateAll();
        }
        return START_NOT_STICKY;
    }

    private void loadQueue(Intent intent) {
        String[] ids = intent.getStringArrayExtra("ids");
        int idx = intent.getIntExtra("index", 0);
        if (ids == null || ids.length == 0) {
            return;
        }
        lib = new Library(this);
        String sel = ids[Math.max(0, Math.min(idx, ids.length - 1))];
        List<Library.Track> q = new ArrayList<>();
        int start = 0;
        for (String id : ids) {
            Library.Track t = lib.byId(id);
            if (t != null && lib.fileOf(t).exists()) {
                if (id.equals(sel)) {
                    start = q.size();
                }
                q.add(t);
            }
        }
        if (q.isEmpty()) {
            return;
        }
        queue.clear();
        queue.addAll(q);
        playIndex(start);
    }

    private void playIndex(int i) {
        if (queue.isEmpty()) {
            return;
        }
        int n = queue.size();
        index = ((i % n) + n) % n;
        Library.Track t = queue.get(index);
        currentId = t.id;
        currentTitle = t.title;
        currentSub = t.company + " · " + t.phase;

        releasePlayer();
        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            ready = false;
            p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(lib.fileOf(t).getAbsolutePath());
            p.setOnPreparedListener(m -> {
                if (mp == m) {
                    ready = true;
                    m.start();
                    playing = true;
                    updateAll();
                }
            });
            p.setOnCompletionListener(m -> {
                if (mp == m) {
                    playing = false;
                    updateAll();
                }
            });
            p.setOnErrorListener((m, what, extra) -> {
                if (mp == m) {
                    releasePlayer();
                    playing = false;
                    updateAll();
                }
                return true;
            });
            playing = true;
            p.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            playing = false;
        }
        updateAll();
    }

    private void toggle() {
        if (mp == null) {
            if (!queue.isEmpty()) {
                playIndex(index);
            }
            return;
        }
        if (!ready) {
            return;
        }
        try {
            if (playing) {
                mp.pause();
                playing = false;
            } else {
                mp.start();
                playing = true;
            }
        } catch (IllegalStateException e) {
            playing = false;
        }
        updateAll();
    }

    private void next() {
        if (!queue.isEmpty()) {
            playIndex(index + 1);
        }
    }

    private void prev() {
        if (queue.isEmpty()) {
            return;
        }
        try {
            if (mp != null && ready && mp.getCurrentPosition() > 3000) {
                mp.seekTo(0);
                if (!playing) {
                    mp.start();
                    playing = true;
                }
                updateAll();
                return;
            }
        } catch (IllegalStateException e) {
            // si passa al brano precedente
        }
        playIndex(index - 1);
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception e) {
                // niente
            }
            mp = null;
        }
        ready = false;
    }

    private void shutdown() {
        stopping = true;
        releasePlayer();
        queue.clear();
        playing = false;
        currentId = null;
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
        stopForeground(true);
        stopSelf();
    }

    private void updateAll() {
        if (stopping) {
            return;
        }
        int pos = 0;
        int dur = 0;
        try {
            if (mp != null && ready) {
                pos = mp.getCurrentPosition();
                dur = mp.getDuration();
            }
        } catch (IllegalStateException e) {
            // niente
        }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSub)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .build());
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, 1f)
                .build());
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification());
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        boolean idle = currentId == null;
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(idle ? "Annunci di Volo" : currentTitle)
                .setContentText(idle ? "Pronto" : currentSub)
                .setContentIntent(openPi)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(android.R.drawable.ic_media_previous, "Indietro", ACTION_PREV, 1))
                .addAction(action(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Play", ACTION_TOGGLE, 2))
                .addAction(action(android.R.drawable.ic_media_next, "Avanti", ACTION_NEXT, 3))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Chiudi", ACTION_STOP, 4))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private Notification.Action action(int icon, String title, String act, int code) {
        PendingIntent pi = PendingIntent.getForegroundService(
                this, code,
                new Intent(this, PlayerService.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        if (session != null) {
            session.release();
        }
        running = false;
        playing = false;
        currentId = null;
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
        super.onDestroy();
    }
}
