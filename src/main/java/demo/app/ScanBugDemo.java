package demo.app;

import cn.hutool.core.util.ClassUtil;
import demo.permissions.AiPermissionKey;
import demo.permissions.PermissionKey;

import java.net.JarURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.jar.JarFile;

public class ScanBugDemo {
    private static final String PACKAGE_NAME = "demo.permissions";
    private static final String PACKAGE_PATH = "demo/permissions";

    public static void main(String[] args) throws Exception {
        boolean control = args.length == 1 && "--control".equals(args[0]);
        boolean rescan = args.length == 1 && "--rescan".equals(args[0]);
        if (args.length > 0 && !control && !rescan) {
            throw new IllegalArgumentException("仅支持无参数复现、--control 对照或 --rescan 连续扫描");
        }

        // 步骤 1：确认通过可执行 JAR 的真实类加载器访问嵌套包资源，而非 IDE 的 classes 目录。
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL packageUrl = loader.getResource(PACKAGE_PATH);
        if (packageUrl == null || !"jar".equals(packageUrl.getProtocol())) {
            throw new IllegalStateException("必须先执行 mvn -B clean package，再通过 java -jar 运行；当前包 URL=" + packageUrl);
        }
        System.out.printf("模式=%s，Hutool=%s，类加载器=%s%n", control ? "对照" : rescan ? "连续扫描" : "复现", ClassUtil.class.getPackage().getImplementationVersion(), loader.getClass().getName());

        // 步骤 2：在扫描前建立两个连接，确认第二个连接已经持有同一个共享嵌套 JAR。
        JarURLConnection firstConnection = (JarURLConnection) packageUrl.openConnection();
        JarURLConnection secondConnection = (JarURLConnection) packageUrl.openConnection();
        JarFile firstJar = firstConnection.getJarFile();
        JarFile secondJar = secondConnection.getJarFile();
        System.out.printf("包 URL=%s%nJAR 类型=%s，共享同一对象=%s，对象标识=%x%n", packageUrl, firstJar.getClass().getName(), firstJar == secondJar, System.identityHashCode(firstJar));
        if (firstJar != secondJar || !firstJar.getClass().getName().endsWith("NestedJarFile")) {
            throw new IllegalStateException("前置条件不成立：两个连接必须共享同一个 NestedJarFile");
        }
        System.out.println("[第一次调用] 扫描前读取成功，条目=" + firstJar.entries().nextElement().getName());

        // 步骤 3：第一次调用执行实际业务扫描；demo 自身不调用 JarFile.close()。
        if (control) {
            System.out.println("[第一次调用] 对照组跳过 Hutool 扫描");
        } else {
            scan(1);
        }

        // 步骤 4：重新建立连接的情况单独验证：关闭后的旧对象会被替换，两次扫描不必然报错。
        if (rescan) {
            JarFile freshJar = ((JarURLConnection) packageUrl.openConnection()).getJarFile();
            System.out.println("[第二次调用] 新连接复用旧 JAR=" + (freshJar == firstJar));
            scan(2);
            System.out.println("RESCAN_OK：单线程连续两次扫描成功");
            return;
        }

        // 步骤 5：第二次调用沿用已建立的连接，读取第一次扫描结束后的共享对象。
        IllegalStateException failure = null;
        try {
            JarFile reusedJar = secondConnection.getJarFile();
            System.out.println("[第二次调用] 已有连接仍持有旧 JAR=" + (reusedJar == firstJar));
            System.out.println("[第二次调用] 读取成功，条目=" + reusedJar.entries().nextElement().getName());
        } catch (IllegalStateException exception) {
            System.out.println("[第二次调用] 读取失败，异常=" + exception);
            failure = exception;
        }

        // 步骤 6：核对验证结果并判定是否成功复现问题。
        if (control) {
            if (failure != null) {
                throw new IllegalStateException("对照组不应发生关闭异常", failure);
            }
            System.out.println("CONTROL_OK：未执行 Hutool 扫描，第二次调用仍可读取共享 JAR");
        } else {
            if (failure == null || !"Zip file closed".equals(failure.getMessage()) || !fromNestedJar(failure)) {
                throw new IllegalStateException("未复现目标 NestedJarFile 关闭异常", failure);
            }
            failure.printStackTrace(System.out);
            System.out.println("REPRODUCED：单线程第二次调用已有连接，复现共享 NestedJarFile 已关闭");
        }
    }

    private static void scan(int invocation) {
        try {
            Set<Class<?>> classes = ClassUtil.scanPackageBySuper(PACKAGE_NAME, PermissionKey.class);
            if (!classes.contains(AiPermissionKey.class)) {
                throw new IllegalStateException("未扫描到预期的权限枚举");
            }
            System.out.printf("[第 %d 次扫描] Hutool 扫描完成，发现类=%s%n", invocation, classes);
        } catch (RuntimeException exception) {
            System.err.printf("[第 %d 次扫描] 执行失败，包=%s，异常=%s%n", invocation, PACKAGE_NAME, exception);
            throw exception;
        }
    }

    private static boolean fromNestedJar(IllegalStateException exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (frame.getClassName().endsWith("NestedJarFile") && "ensureOpen".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
