// 凭据投影的**行为**核验：不是「字符串在不在」，而是「跑一遍看结果对不对」。
//
// 为什么要有这个文件：
//   `CredentialProjection` 是本仓库里唯一一处「处理用户密钥」的代码。
//   阶段 2.3 要证明的性质是**行为**层面的 ——
//   「机器本地记录（client-connection/）不进归档，而 provider 凭据一个字节不动」。
//   `verify-phase0.sh` 的 5m 节要断言的就是它，而只 grep 源码里的字符串
//   **证明不了任何一条**：字符串在、行为错，照样能过。
//
//   它由 tools/run-backup-jvm-tests.sh 用 `javac -encoding UTF-8` 显式编译后运行。
//   （**不要**改用 `java tools/check-credential-projection.java` 单文件启动：那条路
//     用的是平台默认字符集（本机是 US-ASCII），中文注释会直接编不过。）
//
// 依赖：只依赖 :backup 已编译的类 + Gson + SnakeYAML。
//
// 退出码：0 全部成立；1 有不成立的性质（**不是**「跳过」）。

import com.deepseekharness.app.backup.CredentialProjection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

// 包私有：javac 只要求 public 类的类名与文件名一致，包私有类可以放在任意文件名的文件里。
final class CheckCredentialProjection {

    /** 一个必须永远不出现在任何诊断里、也不该进归档的假密钥。 */
    private static final String SECRET = "machine-local-secret-value";
    private static int failures = 0;

    private static void check(String what, boolean holds) {
        System.out.printf("  %s %s%n", holds ? "OK  " : "FAIL", what);
        if (!holds) {
            failures++;
        }
    }

    private static byte[] portable(String text) throws IOException {
        return CredentialProjection.portable(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        // ① 机器本地记录必须被删掉，provider 凭据必须原样留着。
        //    只测「删掉了」会漏掉「顺手改坏了 provider」—— 两件事必须同时成立。
        String mixed = "version: 1\n"
                + "records: {provider/main: {kind: api-key, key: keep-me}, "
                + "client-connection/browser-session: {kind: grant, payload: {secret: " + SECRET + "}}}\n";
        String projected = new String(portable(mixed), StandardCharsets.UTF_8);
        System.out.println("① 机器记录被删、provider 存活");
        check("密钥值没有进投影结果", !projected.contains(SECRET));
        check("连 client-connection 这个键名都没有留下", !projected.contains("client-connection"));
        check("provider 凭据还在", projected.contains("keep-me"));
        check("version: 1 还在（否则 DSH 读不了这个文件）", projected.contains("version: 1"));

        // ② 没有机器记录时逐字节不变。否则每次备份都会因为重新序列化而产生
        //    「内容没变但文件变了」的假差异，掩盖真正的变化。
        System.out.println("② 没有机器记录 → 逐字节不变");
        byte[] clean = "version: 1\nrecords: {provider/main: {kind: api-key, key: keep-me}}\n"
                .getBytes(StandardCharsets.UTF_8);
        check("原样返回", Arrays.equals(clean, CredentialProjection.portable(clean)));

        // ③ 坏输入必须被拒绝，且**诊断里不许带密钥**。
        //    这一条是整件事的意义所在：报错信息本身是最容易泄漏密钥的地方
        //    （解析器把出错的那一行原文贴出来是最自然的写法）。
        System.out.println("③ 坏输入被拒绝，且诊断不带密钥");
        List<String> malformed = List.of(
                "version: 1\nrecords: {provider/main: {kind: api-key, key: '" + SECRET + "', key: other}}\n",
                "version: 1\nrecords: &record {provider/main: {kind: grant, payload: '" + SECRET + "'}}\nrefs: *record\n",
                "version: 1\nrecords: {provider/main: {kind: grant, payload: !!java.lang.String '" + SECRET + "'}}\n",
                "version: 1\nrecords: {provider/main: {kind: grant, payload: .inf}} # " + SECRET + "\n");
        for (int i = 0; i < malformed.size(); i++) {
            String text = malformed.get(i);
            try {
                portable(text);
                check("用例 " + i + " 应当被拒绝", false);
            } catch (IOException failure) {
                check("用例 " + i + " 诊断里没有密钥", !failure.toString().contains(SECRET));
                check("用例 " + i + " 没把解析器原始异常当 cause 带出来", failure.getCause() == null);
            }
        }

        // ④ 超限与非法编码必须在**解析之前**就被挡住。
        System.out.println("④ 字节层面的先决检查");
        try {
            CredentialProjection.portable(new byte[CredentialProjection.LIMIT + 1]);
            check("超过 LIMIT 应被拒绝", false);
        } catch (IOException failure) {
            check("超过 LIMIT 报 LIMIT", "CREDENTIAL_PROJECTION_LIMIT".equals(failure.getMessage()));
        }
        try {
            CredentialProjection.portable(new byte[] {(byte) 0xff});
            check("非法 UTF-8 应被拒绝", false);
        } catch (IOException failure) {
            check("非法 UTF-8 报 ENCODING", "CREDENTIAL_PROJECTION_ENCODING".equals(failure.getMessage()));
        }

        if (failures > 0) {
            System.out.printf("%n凭据投影核验失败：%d 条不成立。%n", failures);
            System.exit(1);
        }
        System.out.println("\n凭据投影核验通过：机器本地记录不进归档，provider 凭据原样保留，诊断不泄漏密钥。");
    }
}
