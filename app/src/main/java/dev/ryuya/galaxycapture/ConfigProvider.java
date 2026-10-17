package dev.ryuya.galaxycapture;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

public final class ConfigProvider extends ContentProvider {
  static final Uri URI = Uri.parse("content://dev.ryuya.galaxycapture.config");
  static final String PERMISSION = "dev.ryuya.galaxycapture.permission.CONFIG";

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle data) {
    var c = getContext();
    if (c == null) return Bundle.EMPTY;
    if (method.equals("config")) {
      var p = c.getSharedPreferences("config", 0);
      var b = new Bundle();
      for (String k :
          new String[] {
            "enabled",
            "rotation",
            "samsung_app",
            "single_window",
            "app_audio_only",
            "ignore_half_turns",
            "hide_duplicate_indicator",
            "speaker_media"
          })
        b.putBoolean(
            k,
            p.getBoolean(
                k,
                !k.equals("enabled")
                    && !k.equals("single_window")
                    && !k.equals("ignore_half_turns")));
      b.putString("reset", p.getString("reset", ""));
      var status = c.getSharedPreferences("status", 0);
      int boot =
          android.provider.Settings.Global.getInt(
              c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
      String server = status.getString("framework", "");
      b.putBoolean(
          "framework_ready",
          boot >= 0
              && status.getInt("framework_boot", -2) == boot
              && (server.startsWith("Ready") || server.startsWith("Recording")));
      return b;
    }
    int uid = Binder.getCallingUid();
    var packages = c.getPackageManager().getPackagesForUid(uid);
    boolean host =
        uid == 1000
            || (packages != null
                && (Arrays.asList(packages).contains("com.android.systemui")
                    || Arrays.asList(packages).contains("com.samsung.android.app.smartcapture")));
    if (method.equals("status") && host && arg != null) {
      String hostName =
          uid == 1000
              ? "framework"
              : Arrays.asList(packages).contains("com.android.systemui") ? "systemui" : "samsung";
      c.getSharedPreferences("status", 0)
          .edit()
          .putString(hostName, arg.substring(0, Math.min(1500, arg.length())))
          .putInt(
              hostName + "_boot",
              android.provider.Settings.Global.getInt(
                  c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1))
          .putLong(hostName + "_time", System.currentTimeMillis())
          .apply();
      return Bundle.EMPTY;
    }
    throw new SecurityException("Unsupported provider operation");
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType(Uri u) {
    return null;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
