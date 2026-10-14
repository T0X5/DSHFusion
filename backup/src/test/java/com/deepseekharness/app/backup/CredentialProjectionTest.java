// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// **适配后**移植（正文与上游不同）。逐条改动见 governance/dsha-imports.json 的 derived 段。
package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class CredentialProjectionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private static final String LOCAL = "native-machine-fixture";
  private static final String PROVIDER = "opaque-provider-fixture";

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String credential() {
    return "version: 1\nrefs: {DEEPSEEK_API_KEY: yes, OPENAI_API_KEY: on}\nrecords:\n"
        + "  client-connection/browser-session: {kind: grant, payload: {secret: "
        + LOCAL
        + "}}\n"
        + "  provider/main: {kind: api-key, key: "
        + PROVIDER
        + ", env: {TEST_TOKEN: '01234'}}\n"
        + "  provider/context: {kind: grant, payload: [yes, on, off, '01234', 01234, 2026-10-03, {data: 中文}]}\n";
  }

  @Test
  public void projectsMachineRecordsFromBlockFlowQuotedAndFourSpaceYamlWithoutTouchingProviders()
      throws Exception {
    for (String source :
        List.of(
            credential(),
            "version: 1\nrecords: {\"client-connection/browser-session\": {kind: grant, payload: '"
                + LOCAL
                + "'}, provider/main: {kind: api-key, key: '"
                + PROVIDER
                + "'}}\n",
            "version: 1\nrecords:\n    'client-connection/browser-session':\n        kind: grant\n        payload: "
                + LOCAL
                + "\n    provider/main:\n        kind: api-key\n        key: "
                + PROVIDER
                + "\n")) {
      byte[] original = bytes(source);
      String projected = text(CredentialProjection.portable(original));
      assertFalse(projected.contains(LOCAL));
      assertFalse(projected.contains("client-connection/browser-session"));
      assertTrue(projected.contains(PROVIDER));
      assertArrayEquals(bytes(source), original);
    }
  }

  @Test
  public void sourceWithoutMachineRecordsAndRecognizedOldRefsRemainByteIdentical()
      throws Exception {
    for (String source :
        List.of(
            "",
            " \n",
            "# only a comment\n",
            "{}\n",
            "null\n",
            "DEEPSEEK_API_KEY: on\nOTHER_KEY: '01234'\n",
            "version: 1\nrecords: {provider/main: {kind: api-key, key: yes}}\n"))
      assertArrayEquals(bytes(source), CredentialProjection.portable(bytes(source)));
  }

  @Test
  public void unknownSchemaTagsAliasesDuplicatesAndNonfinitePayloadFailWithNoSecretDiagnostics()
      throws Exception {
    for (String source :
        List.of(
            "version: 2\nrecords: {}\n",
            "version: 1\nunknown: '" + LOCAL + "'\n",
            "version: 1\nrecords: {provider/main: {kind: unknown, payload: '" + LOCAL + "'}}\n",
            "version: 1\nrecords: {provider/main: {kind: api-key, key: '"
                + LOCAL
                + "', key: other}}\n",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: .inf}}\n",
            "version: 1\nrecords: &record {provider/main: {kind: grant, payload: '"
                + LOCAL
                + "'}}\nrefs: *record\n",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: !!java.lang.String '"
                + LOCAL
                + "'}}\n",
            "%YAML 1.1\n---\nversion: 1\nrecords: {}\n")) {
      IOException failure =
          assertThrows(IOException.class, () -> CredentialProjection.portable(bytes(source)));
      assertTrue(failure.getMessage().startsWith("CREDENTIAL_PROJECTION_"));
      assertFalse(failure.toString().contains(LOCAL));
      assertNull(failure.getCause());
    }
    assertThrows(
        IOException.class,
        () -> CredentialProjection.portable(new byte[CredentialProjection.LIMIT + 1]));
    assertThrows(IOException.class, () -> CredentialProjection.portable(new byte[] {(byte) 0xff}));
    assertThrows(
        IOException.class,
        () ->
            CredentialProjection.portable(
                bytes(
                    "version: 1\nrecords: {provider/main: {kind: grant, payload: "
                        + "[".repeat(40)
                        + "null"
                        + "]".repeat(40)
                        + "}}\n")));
  }

  @Test
  public void actualHostSnapshotArchiveUsesTheProjectionAndPreservesSourceBytes() throws Exception {
    File source = new File(temporary.newFolder(), CredentialProjection.FILE),
        operation = temporary.newFolder();
    Files.write(source.toPath(), bytes(credential()));
    byte[] original = Files.readAllBytes(source.toPath());
    var raw = new FileBackupSource(fs, "credentials", "settings", source, false, null);
    var projected = new CredentialFileBackupSource(raw);
    File archive = new File(operation, "archive.dshdata");
    HostSnapshot.create(
        fs,
        List.of(projected),
        operation,
        archive,
        BackupArchiveTest.summary(),
        false,
        new BackupControl(null));
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    try (InputStream input = fs.read(archive, fs.stat(archive))) {
      BackupArchive.read(
          input,
          new BackupArchive.Visitor() {
            public OutputStream payload(int ordinal, BackupArchive.Record record) {
              return contents;
            }
          },
          new BackupControl(null));
    }
    assertFalse(text(contents.toByteArray()).contains(LOCAL));
    assertTrue(text(contents.toByteArray()).contains(PROVIDER));
    assertArrayEquals(original, Files.readAllBytes(source.toPath()));
  }

  @Test
  public void projectedSourceStillRejectsSameMetadataRawByteChanges() throws Exception {
    File source = new File(temporary.newFolder(), CredentialProjection.FILE);
    Files.write(source.toPath(), bytes(credential()));
    var projected =
        new CredentialFileBackupSource(
            new FileBackupSource(fs, "credentials", "settings", source, false, null));
    List<BackupSource.Item> items = new ArrayList<>();
    projected.walk(items::add, new BackupControl(null));
    var modified = Files.getLastModifiedTime(source.toPath());
    Files.write(source.toPath(), credential().replace(LOCAL, "changed-machine-value!").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.setLastModifiedTime(source.toPath(), modified);
    assertThrows(IOException.class, () -> projected.open(items.get(0)));
  }

  @Test
  public void unselectedOrCancelledCredentialRestoreKeepsCurrentMachineRecords() throws Exception {
    File candidate = temporary.newFolder(), file = new File(candidate, CredentialProjection.FILE);
    Files.write(file.toPath(), bytes(credential()));
    byte[] before = Files.readAllBytes(file.toPath());
    CredentialProjection.restoreCandidate(fs, candidate, false, new BackupControl(null));
    assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    var cancelled = new BackupControl(null);
    cancelled.cancel();
    assertThrows(
        InterruptedIOException.class,
        () -> CredentialProjection.restoreCandidate(fs, candidate, true, cancelled));
    assertArrayEquals(before, Files.readAllBytes(file.toPath()));
  }
}
