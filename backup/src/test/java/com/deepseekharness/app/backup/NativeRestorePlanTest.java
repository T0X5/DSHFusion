// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// **适配后**移植（正文与上游不同）。逐条改动见 governance/dsha-imports.json 的 derived 段。
package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativeRestorePlanTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  @Test
  public void inspectionAndCandidateDoNotTouchActiveDataAndKeepUnmentionedFiles() throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        archive = new File(task, "archive");
    Files.write(archive.toPath(), BackupArchiveTest.archive());
    Files.createDirectories(new File(active, "sessions").toPath());
    Files.write(new File(active, "sessions/kept.jsonl").toPath(), "newer untouched session".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.write(new File(active, "settings.yaml").toPath(), "untouched settings".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String before = BackupTree.digest(fs, active, new BackupControl(null));
    var plan =
        NativeRestorePlan.inspect(
            fs,
            task,
            archive,
            root -> new NativeRestorePlan.Target(active, active, true),
            Set.of("application"),
            new BackupControl(null));
    assertEquals(before, BackupTree.digest(fs, active, new BackupControl(null)));
    assertEquals(List.of("dsh"), plan.buildCandidates(true, new BackupControl(null)));
    assertEquals(before, BackupTree.digest(fs, active, new BackupControl(null)));
    assertEquals(
        "newer untouched session",
        new String(Files.readAllBytes(new File(task, "candidate/dsh/sessions/kept.jsonl").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        "untouched settings",
        new String(Files.readAllBytes(new File(task, "candidate/dsh/settings.yaml").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        "对话字节\n", new String(Files.readAllBytes(new File(task, "candidate/dsh/sessions/a.jsonl").toPath()), java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  public void footerFailureNeverProducesActiveWrites() throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        archive = new File(task, "archive");
    Files.write(new File(active, "data").toPath(), "old".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] data = BackupArchiveTest.archive();
    data[data.length - 1] ^= 1;
    Files.write(archive.toPath(), data);
    assertThrows(
        IOException.class,
        () ->
            NativeRestorePlan.inspect(
                fs,
                task,
                archive,
                r -> new NativeRestorePlan.Target(active, active, true),
                Set.of("application"),
                new BackupControl(null)));
    assertEquals("old", new String(Files.readAllBytes(new File(active, "data").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(new File(task, "candidate").exists());
  }

  @Test
  public void unknownSelectedScopeDoesNotBecomeApplicationRestore() throws Exception {
    File task = temporary.newFolder(), archive = new File(task, "archive");
    Files.write(archive.toPath(), BackupArchiveTest.archive());
    assertThrows(
        IOException.class,
        () ->
            NativeRestorePlan.inspect(
                fs,
                task,
                archive,
                r -> {
                  throw new AssertionError("must not resolve target");
                },
                Set.of("unknown"),
                new BackupControl(null)));
  }

  @Test
  public void relativeLinkEscapesAreRejected() throws Exception {
    assertThrows(IOException.class, () -> NativeRestorePlan.relativeTarget("a", "../outside"));
    assertEquals("folder/file", NativeRestorePlan.relativeTarget("folder/link", "file"));
  }

  private byte[] linkedArchive(String target) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    var writer = new BackupArchive.Writer(bytes, new BackupControl(null));
    writer.add(BackupArchive.Record.of("dsh", "", "DIRECTORY", "application", 0, ""), null);
    byte[] payload = "preserved".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    writer.add(
        BackupArchive.Record.of(
            "dsh",
            "kept",
            "FILE",
            "application",
            payload.length,
            BackupArchive.hex(BackupArchive.sha().digest(payload))),
        new ByteArrayInputStream(payload));
    Map<String, Object> link =
        new LinkedHashMap<>(
            BackupArchive.Record.of("dsh", "link", "DIRECTORY", "application", 0, "").metadata);
    link.put("kind", "LINK");
    link.put("target", target);
    writer.add(new BackupArchive.Record(link), null);
    writer.finish(BackupArchiveTest.summary());
    return bytes.toByteArray();
  }

  @Test
  public void missingLinkTargetAncestorsRetainMetadataWithoutCreatingDirectories()
      throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        archive = new File(task, "archive");
    Files.write(archive.toPath(), linkedArchive("missing/nested/file"));
    String before = BackupTree.digest(fs, active, new BackupControl(null));
    var plan =
        NativeRestorePlan.inspect(
            fs,
            task,
            archive,
            root -> new NativeRestorePlan.Target(active, null, false),
            Set.of("application"),
            new BackupControl(null));
    assertEquals(List.of("dsh"), plan.buildCandidates(true, new BackupControl(null)));
    assertEquals("preserved", new String(Files.readAllBytes(new File(task, "candidate/dsh/kept").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(new File(task, "candidate/dsh/missing").exists());
    assertFalse(new File(task, "candidate/dsh/link").exists());
    assertTrue(new File(task, "unresolved-links/2.json").isFile());
    assertTrue(plan.warnings.contains("LINK_METADATA_RETAINED"));
    assertEquals(before, BackupTree.digest(fs, active, new BackupControl(null)));
  }

  @Test
  public void regularFileLinkTargetParentIsStillRejected() throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        archive = new File(task, "archive");
    Files.write(archive.toPath(), linkedArchive("kept/child"));
    var plan =
        NativeRestorePlan.inspect(
            fs,
            task,
            archive,
            root -> new NativeRestorePlan.Target(active, null, false),
            Set.of("application"),
            new BackupControl(null));
    IOException failure =
        assertThrows(IOException.class, () -> plan.buildCandidates(true, new BackupControl(null)));
    assertEquals("PARENT_LINK_OR_MISSING", failure.getMessage());
    assertEquals(Collections.emptyList(), fs.list(active));
  }

  @Test
  public void linkTargetSymlinkParentIsStillRejectedWithoutFollowingIt() throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        outside = temporary.newFolder(),
        archive = new File(task, "archive");
    // Explicit link adapter avoids depending on the host's symbolic-link privileges.
    Files.write(new File(active, "parent").toPath(), "link fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    BackupFileSystem linkFs =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return file.getName().equals("parent") && !node.type.equals("MISSING")
                ? new Node("LINK", node.key, node.size, node.modified, node.device, node.mode)
                : node;
          }

          public String readLink(File file) {
            return outside.getPath();
          }

          public void symlink(String target, File file) throws IOException {
            Files.write(file.toPath(), "link fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        };
    Files.write(archive.toPath(), linkedArchive("parent/missing"));
    var plan =
        NativeRestorePlan.inspect(
            linkFs,
            task,
            archive,
            root -> new NativeRestorePlan.Target(active, active, true),
            Set.of("application"),
            new BackupControl(null));
    IOException failure =
        assertThrows(IOException.class, () -> plan.buildCandidates(true, new BackupControl(null)));
    assertEquals("PARENT_LINK_OR_MISSING", failure.getMessage());
    assertEquals(Collections.emptyList(), fs.list(outside));
  }

  @Test
  public void availableLinkTargetStillCreatesLink() throws Exception {
    File task = temporary.newFolder(),
        active = temporary.newFolder(),
        archive = new File(task, "archive");
    Files.write(archive.toPath(), linkedArchive("kept"));
    List<String> created = new ArrayList<>();
    BackupFileSystem linkFs =
        new JvmBackupFileSystem() {
          public void symlink(String target, File file) throws IOException {
            created.add(target + ":" + file.getName());
          }
        };
    var plan =
        NativeRestorePlan.inspect(
            linkFs,
            task,
            archive,
            root -> new NativeRestorePlan.Target(active, null, false),
            Set.of("application"),
            new BackupControl(null));
    assertEquals(List.of("dsh"), plan.buildCandidates(true, new BackupControl(null)));
    assertEquals(List.of("kept:link"), created);
    assertFalse(plan.warnings.contains("LINK_METADATA_RETAINED"));
  }
}
