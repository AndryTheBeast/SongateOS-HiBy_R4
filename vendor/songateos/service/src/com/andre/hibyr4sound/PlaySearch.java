package com.andre.hibyr4sound;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

/**
 * "Play in <app>" from Songate's search (user, 2026-11-19). Spotify ignored playFromSearch sent by Songate (an ordinary
 * app): apps like it only obey callers they trust. This service runs as the system uid, which media apps usually
 * trust (Google's own media samples accept Process.SYSTEM_UID). Songate asks, the service sends.
 *   media_play_search <package> query=... [how=search|uri|mediaid] -> ok, sent: the controller call used
 * (development: "how" picks the way; Songate will use the default "search")
 */
final class PlaySearch {
    static final String TAG = "SongatePlaySearch";

    private PlaySearch() { }

    static Bundle play(Context c, String pkg, Bundle e) {
        if (pkg == null || pkg.isEmpty()) return SystemApi.err("bad_arg", "package missing");
        String q = e == null ? null : e.getString("query");
        if (q == null || q.trim().isEmpty()) return SystemApi.err("bad_arg", "query missing");
        String how = e.getString("how", "search");
        MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
        MediaController mc = null;
        for (MediaController m : msm.getActiveSessions(null)) if (pkg.equals(m.getPackageName())) { mc = m; break; }
        if (mc == null) return SystemApi.err("no_session", "That app has no media session yet; open it first");
        Bundle x = new Bundle();
        x.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        x.putString(android.app.SearchManager.QUERY, q);
        MediaController.TransportControls t = mc.getTransportControls();
        String sent;
        switch (how) {
            case "uri":     t.playFromUri(Uri.parse("spotify:search:" + Uri.encode(q)), x); sent = "playFromUri"; break;
            case "mediaid": t.playFromMediaId("spotify:search:" + q, x); sent = "playFromMediaId"; break;
            default:        t.playFromSearch(q, x); sent = "playFromSearch"; break;
        }
        Log.i(TAG, "sent " + sent + " to " + pkg + " (as the system uid)");
        Bundle r = SystemApi.ok();
        r.putString("sent", sent);
        return r;
    }
}
