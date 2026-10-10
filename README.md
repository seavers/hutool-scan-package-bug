# Hutool 关闭 Spring Boot 共享嵌套 JAR 的复现

此 demo 使用 **Hutool 5.8.48** 和 **Spring Boot Loader 3.5.6**，在单线程中复现 `ClassUtil.scanPackageBySuper()` 扫描结束后关闭共享 `NestedJarFile`，导致第二次调用已有连接时报 `IllegalStateException: Zip file closed`。

2026-10-10 核查 [Maven Central 元数据](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/maven-metadata.xml)，最新发布的 Hutool 5.x 为 **5.8.48**（元数据更新时间：2026-10-09）。版本固定在根 `pom.xml` 中。

## 运行

需要 JDK 17+、Maven 3.9+。在项目根目录执行：

```bash
mvn -B -q package
java -jar demo-app/target/demo-app-1.0-SNAPSHOT.jar
```

如果默认 Maven 缓存不可写，可使用：

```bash
mvn -B -q -Dmaven.repo.local=/tmp/hutool-scan-bug-m2 package
```

两个对照模式分别在独立 JVM 中运行：

```bash
# 跳过第一次扫描，第二次调用已有连接可以正常读取
java -jar demo-app/target/demo-app-1.0-SNAPSHOT.jar --control

# 连续调用两次 Hutool 扫描，验证重新建立连接时的行为
java -jar demo-app/target/demo-app-1.0-SNAPSHOT.jar --rescan
```

复现成功输出 `REPRODUCED`；跳过扫描的对照输出 `CONTROL_OK`；连续扫描的对照输出 `RESCAN_OK`。符合预期时退出码为 0，前置条件不成立、未发现枚举或结果不符合预期时返回非零退出码。

必须使用 `java -jar` 运行。从 IDE 直接运行 main 会使用普通 classes 目录，不具备嵌套 JAR 的触发条件，程序会明确报错。

## 模块和执行过程

- `permission-definitions`：普通依赖 JAR，包含 `PermissionKey` 接口和实现它的 `AiPermissionKey` 枚举。
- `demo-app`：由 Spring Boot 插件打包成可执行 JAR，将前一个模块放入 `BOOT-INF/lib`。仅使用 Boot Loader，不启动 Spring 容器或 HTTP 服务。

全部代码在 main 线程中顺序执行，不创建线程或使用并发同步工具：

1. 获取嵌套 JAR 中 `demo/permissions` 的包 URL。
2. 建立两个 `JarURLConnection`，提前调用各自的 `getJarFile()`，确认返回同一个共享 `NestedJarFile`。实际对象类型为其子类 `UrlNestedJarFile`。
3. 第一次调用先读取条目，确认 JAR 正常，再执行真实公开 API `ClassUtil.scanPackageBySuper("demo.permissions", PermissionKey.class)`。
4. 第二次调用已有连接的 `getJarFile()`，仍取得旧对象，随后调用 `entries()` 抛出关闭异常。

demo 不主动调用 `JarFile.close()`，不模拟 Boot 类加载器，也不反射调用 Hutool 私有方法。

## 实测结果与触发边界

JDK 17.0.19、Hutool 5.8.48、Boot Loader 3.5.6：

```text
JAR 类型=org.springframework.boot.loader.net.protocol.jar.UrlNestedJarFile，共享同一对象=true
[第一次调用] 扫描前读取成功，条目=META-INF/
[第 1 次扫描] Hutool 扫描完成，发现类=[class demo.permissions.AiPermissionKey]
[第二次调用] 已有连接仍持有旧 JAR=true
[第二次调用] 读取失败，异常=java.lang.IllegalStateException: Zip file closed
java.lang.IllegalStateException: Zip file closed
    at org.springframework.boot.loader.jar.NestedJarFile.ensureOpen(NestedJarFile.java:414)
    at org.springframework.boot.loader.jar.NestedJarFile.entries(NestedJarFile.java:175)
REPRODUCED：单线程第二次调用已有连接，复现共享 NestedJarFile 已关闭
```

`--control` 跳过扫描，第二次读取成功。

`--rescan` 在第一次扫描完成后重新建立连接，实测新连接取得的 JAR 与旧对象不同，两次扫描均成功：

```text
[第 1 次扫描] Hutool 扫描完成，发现类=[class demo.permissions.AiPermissionKey]
[第二次调用] 新连接复用旧 JAR=false
[第 2 次扫描] Hutool 扫描完成，发现类=[class demo.permissions.AiPermissionKey]
RESCAN_OK：单线程连续两次扫描成功
```

因此，并发不是复现的必要条件，关键是第二次访问仍使用已经被扫描关闭的旧对象。仅仅顺序发起两次全新连接或调用两次扫描，在这个版本组合下不会触发异常。已有连接必须在扫描前完成连接并持有旧 JAR；仅提前创建尚未连接的 URLConnection 不等于已经取得共享对象。

本单线程版本在 `entries()` 检查阶段失败，因此堆栈为 `ensureOpen:414 → entries:175`；原先持有迭代器再继续遍历的版本在 `nextElement:653` 失败。两者的根因都是同一个共享 JAR 已关闭。

## 根因证据

[Hutool 5.8.48 发布源码](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.48/hutool-core-5.8.48-sources.jar) 的 `cn/hutool/core/lang/ClassScanner.java` 中：

- `scan(boolean)` 的 `jar` 协议分支调用 `scanJar(URLUtil.getJarFile(url))`。
- `scanJar(JarFile)` 在 `finally` 中调用 `IoUtil.close(jar)`。

[Boot Loader 3.5.6 发布源码](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-loader/3.5.6/spring-boot-loader-3.5.6-sources.jar) 中，`JarUrlConnection` 持有连接得到的 JAR 对象；`NestedJarFile.ensureOpen()` 在对象已关闭时抛出 `Zip file closed`。新的连接可以获得替换对象，但已连接的旧连接仍然持有关闭的对象。

本 demo 只验证上述版本组合，不推断所有 Boot 版本的行为，也不包含业务修复。
