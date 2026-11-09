package com.reaper357.loonietunes;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between the Loonie Tunes web UI and Android:
 *  - asks for "Music and audio" (and notification) access when the user opens a folder
 *  - mirrors playback to a system MediaSession so the notification shade, lock screen
 *    and quick-settings media tile show controls
 *  - hands audio files opened from other apps ("Open with") to the web UI
 */
@CapacitorPlugin(
    name = "MediaControls",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "images", strings = { "android.permission.READ_MEDIA_IMAGES" }),
        @Permission(alias = "storage", strings = { "android.permission.READ_EXTERNAL_STORAGE" }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class MediaControlsPlugin extends Plugin {

    private static MediaControlsPlugin instance;
    private String pendingLaunchUri;

    @Override
    public void load() {
        instance = this;
        try {
            pendingLaunchUri = uriFromIntent(getActivity().getIntent());
        } catch (Exception e) {
            pendingLaunchUri = null;
        }
    }

    // ---------------------------------------------------------------- permissions

    @PluginMethod
    public void checkMusicAccess(PluginCall call) {
        call.resolve(accessState());
    }

    @PluginMethod
    public void requestMusicAccess(PluginCall call) {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (getPermissionState("audio") != PermissionState.GRANTED) need.add("audio");
            if (getPermissionState("images") != PermissionState.GRANTED) need.add("images");
            if (getPermissionState("notifications") != PermissionState.GRANTED) need.add("notifications");
        } else if (getPermissionState("storage") != PermissionState.GRANTED) {
            need.add("storage");
        }

        if (need.isEmpty()) {
            call.resolve(accessState());
            return;
        }
        try {
            requestPermissionForAliases(need.toArray(new String[0]), call, "accessCallback");
        } catch (Exception e) {
            call.resolve(accessState());
        }
    }

    @PermissionCallback
    private void accessCallback(PluginCall call) {
        call.resolve(accessState());
    }

    private JSObject accessState() {
        boolean audio;
        boolean notifications;
        boolean images;
        if (Build.VERSION.SDK_INT >= 33) {
            audio = getPermissionState("audio") == PermissionState.GRANTED;
            images = getPermissionState("images") == PermissionState.GRANTED;
            notifications = getPermissionState("notifications") == PermissionState.GRANTED;
        } else {
            audio = getPermissionState("storage") == PermissionState.GRANTED;
            images = audio;
            notifications = true;
        }
        JSObject result = new JSObject();
        result.put("granted", audio);
        result.put("notifications", notifications);
        result.put("images", images);
        return result;
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getContext().getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    // ---------------------------------------------------------------- save a text file (playlist export)

    /** Saves a small text file into the phone's Downloads folder. Needs no permission on Android 10+. */
    @PluginMethod
    public void saveTextFile(PluginCall call) {
        String name = call.getString("name", "loonie_tunes_export.json");
        String text = call.getString("text", "");
        String mime = call.getString("mime", "application/json");
        if (name == null || name.isEmpty()) name = "loonie_tunes_export.json";
        if (text == null) text = "";
        if (mime == null) mime = "application/json";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("Could not create the file");
                OutputStream out = getContext().getContentResolver().openOutputStream(uri);
                if (out == null) throw new Exception("Could not open the file for writing");
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                where = "Downloads/" + name;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file;
                try {
                    dir.mkdirs();
                    file = new File(dir, name);
                    FileOutputStream out = new FileOutputStream(file);
                    try {
                        out.write(bytes);
                    } finally {
                        out.close();
                    }
                } catch (Exception denied) {
                    // No write permission on old Android: use the app's own folder instead
                    File fallback = getContext().getExternalFilesDir(null);
                    file = new File(fallback, name);
                    FileOutputStream out = new FileOutputStream(file);
                    try {
                        out.write(bytes);
                    } finally {
                        out.close();
                    }
                }
                where = file.getAbsolutePath();
            }
            JSObject result = new JSObject();
            result.put("location", where);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Could not save the file: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- media session

    @PluginMethod
    public void updateSession(PluginCall call) {
        JSObject d = call.getData();
        MediaPlaybackService.Snapshot s = new MediaPlaybackService.Snapshot();
        s.title = d.optString("title", "");
        s.artist = d.optString("artist", "");
        s.album = d.optString("album", "");
        s.artKey = d.optString("artKey", "");
        s.durationMs = (long) d.optDouble("durationMs", 0);
        s.positionMs = (long) d.optDouble("positionMs", 0);
        s.playing = d.optBoolean("playing", false);
        s.speed = (float) d.optDouble("speed", 1.0);

        // The web side only sends the picture when it changes
        MediaPlaybackService.Snapshot previous = MediaPlaybackService.snapshot;
        String art = d.optString("art", "");
        if (!art.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(art, Base64.DEFAULT);
                s.art = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) {
                s.art = null;
            }
        } else if (previous != null && !s.artKey.isEmpty() && s.artKey.equals(previous.artKey)) {
            s.art = previous.art;
        }

        MediaPlaybackService.push(getContext().getApplicationContext(), s);
        call.resolve();
    }

    @PluginMethod
    public void stopSession(PluginCall call) {
        MediaPlaybackService.stop(getContext().getApplicationContext());
        call.resolve();
    }

    /** Called by the service when the user presses a button in the notification / lock screen. */
    static void emit(String action, double positionSeconds) {
        MediaControlsPlugin plugin = instance;
        if (plugin == null) return;
        JSObject data = new JSObject();
        data.put("action", action);
        if (positionSeconds >= 0) data.put("position", positionSeconds);
        plugin.notifyListeners("mediaAction", data);
    }

    // ---------------------------------------------------------------- "Open with" audio

    @PluginMethod
    public void getLaunchAudio(PluginCall call) {
        JSObject result = new JSObject();
        if (pendingLaunchUri != null) result.put("uri", pendingLaunchUri);
        pendingLaunchUri = null;
        call.resolve(result);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String uri = uriFromIntent(intent);
        if (uri != null) {
            JSObject data = new JSObject();
            data.put("uri", uri);
            notifyListeners("launchAudio", data, true);
        }
    }

    private static String uriFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        return data == null ? null : data.toString();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void handleOnDestroy() {
        MediaPlaybackService.stop(getContext().getApplicationContext());
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }
}
