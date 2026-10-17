package dev.ryuya.galaxycapture;

import android.content.Context;
import android.os.Process;
import android.util.AtomicFile;
import java.io.*;
import java.util.Properties;

/** Durable before the first recording mutation; an unclean recording marks a restart. */
final class HostGuard {
  private final AtomicFile file;
  private final Properties state = new Properties();
  private boolean blocked;
  private boolean recordingActive;
  private int captureOperations;

  HostGuard(Context context, String host) {
    File base =
        host.equals("framework")
            ? new File("/data/system/galaxycapture/guard.properties")
            : new File(
                context.createDeviceProtectedStorageContext().getFilesDir(),
                "galaxycapture-guard.properties");
    file = new AtomicFile(base);
    try {
      if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) {
        if (file.getBaseFile().length() > 8192
            || new File(file.getBaseFile() + ".bak").length() > 8192) throw new IOException();
        try (var in = file.openRead()) {
          state.load(in);
        }
      }
      long now = System.currentTimeMillis();
      int pid = Process.myPid();
      RestartLedger.observe(state, now, pid);
      blocked = Boolean.parseBoolean(state.getProperty("blocked", "false"));
      save();
    } catch (Throwable error) {
      blocked = true;
    }
  }

  synchronized boolean allowed() {
    return !blocked;
  }

  synchronized void reset(String token) {
    if (token.isEmpty() || token.equals(state.getProperty("reset", ""))) return;
    state.setProperty("reset", token);
    state.setProperty("blocked", "false");
    state.setProperty("count", "0");
    state.setProperty("active", "false");
    blocked = false;
    try {
      save();
    } catch (Throwable error) {
      blocked = true;
    }
  }

  synchronized void arm() throws IOException {
    if (blocked) throw new IOException("Guard blocked");
    recordingActive = true;
    state.setProperty("active", "true");
    state.setProperty("time", Long.toString(System.currentTimeMillis()));
    save();
  }

  synchronized void finish() {
    recordingActive = false;
    state.setProperty("active", Boolean.toString(captureOperations > 0));
    try {
      save();
    } catch (Throwable error) {
      blocked = true;
    }
  }

  synchronized void beginCapture() throws IOException {
    if (blocked) throw new IOException("Guard blocked");
    captureOperations++;
    state.setProperty("active", "true");
    state.setProperty("time", Long.toString(System.currentTimeMillis()));
    save();
  }

  synchronized void endCapture() {
    captureOperations = Math.max(0, captureOperations - 1);
    state.setProperty("active", Boolean.toString(recordingActive || captureOperations > 0));
    try { save(); } catch (Throwable error) { blocked = true; }
  }

  synchronized void fail() {
    blocked = true;
    state.setProperty("blocked", "true");
    state.setProperty("active", "false");
    try {
      save();
    } catch (Throwable ignored) {
    }
  }

  private void save() throws IOException {
    File parent = file.getBaseFile().getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Guard directory");
    parent.setReadable(false, false);
    parent.setWritable(false, false);
    parent.setExecutable(false, false);
    parent.setReadable(true, true);
    parent.setWritable(true, true);
    parent.setExecutable(true, true);
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      state.store(out, "Galaxy Capture crash protection");
      file.finishWrite(out);
    } catch (Throwable error) {
      if (out != null) file.failWrite(out);
      throw new IOException("Guard persistence", error);
    }
  }
}
