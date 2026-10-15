// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// **适配后**移植（正文与上游不同）。逐条改动见 governance/dsha-imports.json 的 derived 段。
package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class HostDataTransactionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  static final String ID = "11111111-2222-4333-8444-555555555555";

  static class DiskSettings implements HostDataTransaction.Settings {
    final File file;

    DiskSettings(File base) {
      file = new File(base, "settings.json");
    }

    public Map<String, Object> current() throws IOException {
      return BackupJson.read(Files.readAllBytes(file.toPath()), 1000);
    }

    public void apply(Map<String, Object> values) throws IOException {
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(BackupJson.write(values, 1000));
        out.getFD().sync();
      }
    }
  }

  static HostDataTransaction open(File base, HostDataTransaction.Fault fault) throws IOException {
    return new HostDataTransaction(
        new JvmBackupFileSystem(),
        new File(base, ID),
        root -> {
          if (!Set.of("sessions", "settings").contains(root))
            throw new IOException("UNKNOWN_TARGET");
          return new File(base, "active/" + root);
        },
        new DiskSettings(base),
        fault);
  }

  static void fixture(File base) throws Exception {
    for (String dir :
        new String[] {
          ID,
          ID + "/candidate",
          ID + "/candidate/sessions",
          ID + "/candidate/settings",
          "active",
          "active/sessions",
          "active/settings"
        }) Files.createDirectories(new File(base, dir).toPath());
    for (String root : List.of("sessions", "settings")) {
      Files.write(new File(base, "active/" + root + "/data").toPath(), ("old-" + root).getBytes(StandardCharsets.UTF_8));
      Files.write(new File(base, ID + "/candidate/" + root + "/data").toPath(), ("new-" + root).getBytes(StandardCharsets.UTF_8));
    }
    new DiskSettings(base)
        .apply(new LinkedHashMap<>(Map.of("apiCipher", "old-cipher", "port", 3080)));
    var fs = new JvmBackupFileSystem();
    Map<String, String> before = new LinkedHashMap<>();
    for (String root : List.of("sessions", "settings"))
      before.put(
          root, BackupTree.digest(fs, new File(base, "active/" + root), new BackupControl(null)));
    open(base, null)
        .prepare(
            List.of("sessions", "settings"),
            before,
            new LinkedHashMap<>(Map.of("apiCipher", "new-cipher", "port", 3081)),
            new BackupControl(null));
  }

  static void old(File base) throws Exception {
    assertEquals("old-sessions", new String(Files.readAllBytes(new File(base, "active/sessions/data").toPath()), StandardCharsets.UTF_8));
    assertEquals("old-settings", new String(Files.readAllBytes(new File(base, "active/settings/data").toPath()), StandardCharsets.UTF_8));
    assertEquals("old-cipher", new DiskSettings(base).current().get("apiCipher"));
  }

  @Test
  public void commitAndLateRecoveryDoNotDiscardNewUserData() throws Exception {
    File base = temporary.newFolder();
    fixture(base);
    open(base, null).commit(new BackupControl(null));
    assertTrue(open(base, null).finalized());
    Files.write(new File(base, "active/sessions/data").toPath(), ("new user message after restore").getBytes(StandardCharsets.UTF_8));
    open(base, null).recover();
    assertEquals(
        "new user message after restore",
        new String(Files.readAllBytes(new File(base, "active/sessions/data").toPath()), StandardCharsets.UTF_8));
    assertEquals(
        "old-sessions", new String(Files.readAllBytes(new File(base, ID + "/previous/sessions/data").toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void changesSincePreviewRefuseCommit() throws Exception {
    File base = temporary.newFolder();
    fixture(base);
    Files.write(new File(base, "active/sessions/data").toPath(), ("concurrent change").getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> open(base, null).commit(new BackupControl(null)));
    assertEquals(
        "concurrent change", new String(Files.readAllBytes(new File(base, "active/sessions/data").toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void everyIoFailureRestoresFilesAndSettings() throws Exception {
    for (String phase :
        List.of(
            "old-sessions",
            "new-sessions",
            "old-settings",
            "new-settings",
            "files-committed",
            "settings-write",
            "settings-committed")) {
      File base = temporary.newFolder();
      fixture(base);
      assertThrows(
          IOException.class,
          () ->
              open(
                      base,
                      name -> {
                        if (name.equals(phase)) throw new IOException("INJECTED");
                      })
                  .commit(new BackupControl(null)));
      open(base, null).recover();
      old(base);
    }
  }

  @Test
  public void corruptPlanOrMarkerRetainsAllFiles() throws Exception {
    File base = temporary.newFolder();
    fixture(base);
    Files.write(new File(base, ID + "/finalized").toPath(), ("corrupt").getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> open(base, null).recover());
    old(base);
    Files.delete(new File(base, ID + "/finalized").toPath());
    Files.write(new File(base, ID + "/plan.json").toPath(), ("{bad").getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> open(base, null).recover());
    old(base);
  }

  @Test
  public void realProcessKillThenRecoveryAndKillDuringRollback() throws Exception {
    for (String phase :
        List.of("old-sessions", "new-settings", "settings-write", "settings-committed")) {
      File base = temporary.newFolder();
      fixture(base);
      killAt(base, phase, false);
      if (phase.equals("new-settings")) killAt(base, "rollback-new-settings", true);
      open(base, null).recover();
      open(base, null).recover();
      old(base);
    }
  }

  private void killAt(File base, String phase, boolean recover) throws Exception {
    File marker = new File(base, "reached-" + phase);
    String javaBinary =
        new File(
                System.getProperty("java.home"),
                "bin/"
                    + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"))
            .getPath();
    Set<String> locations = new LinkedHashSet<>();
    for (Class<?> type :
        List.of(
            HostDataTransactionTest.class,
            HostDataTransaction.class,
            com.google.gson.stream.JsonReader.class,
            org.junit.Assert.class))
      locations.add(
          new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
    Process child =
        new ProcessBuilder(
                javaBinary,
                "-cp",
                String.join(File.pathSeparator, locations),
                HostTransactionCrashProcess.class.getName(),
                base.getPath(),
                phase,
                Boolean.toString(recover))
            .redirectErrorStream(true)
            .redirectOutput(new File(base, "child.log"))
            .start();
    long deadline = System.currentTimeMillis() + 15000;
    while (!marker.exists() && child.isAlive() && System.currentTimeMillis() < deadline)
      Thread.sleep(20);
    try {
      assertTrue("未到持久化边界：" + phase, marker.exists());
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
