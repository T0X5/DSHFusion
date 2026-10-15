// 来源：DSHA (https://github.com/DSH-APP/DSHA) — MIT
// Copyright (c) 2026 qiannianhuanxiang
// 原样复制进 DSHFusion，正文未做任何修改。见 governance/dsha-imports.json。
package com.deepseekharness.app.util;

/** Persisted transaction IDs keep the historical lowercase UUID wire format. */
public final class Ids {
  public static final String UUID_PATTERN =
      "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

  private Ids() {}

  public static boolean uuid(String value) {
    return value != null && value.matches(UUID_PATTERN);
  }
}
