package com.fastpay;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守门测试：扫一遍已经编译好的测试类，看有没有两个 @Test 方法挂了同一个 @Order 值。
 *
 * 背景：MTM-197。这个仓库的端到端测试是一长串按顺序跑的步骤，@Order 决定谁先谁后。之前
 * 出过好几次「两个人各自选了同一个号，合并之后谁先跑谁后跑不确定，一红就查不出规律」的事故。
 * 有了这道检查，同样的事下次会变成 mvn test 直接红一行明确的报错，改个数字 30 秒解决。
 *
 * 只看 @TestMethodOrder(MethodOrderer.OrderAnnotation.class) 的类，因为只有这种类里 @Order
 * 才真的决定执行顺序；其他排序策略（Random / Alphabetic）撞号无所谓，不必拦。
 *
 * 走的路子：直接扫 target/test-classes 下的 .class 文件，通过反射读注解 —— 不解析源码、
 * 不加新依赖、跑起来是毫秒级。getDeclaredMethods 只看当前类自己声明的方法，抽象父类和
 * 子类各自独立扫，同一个方法不会被数两遍。
 */
class DuplicateTestOrderGuardTest {

    @Test
    void noTwoTestMethodsShareTheSameOrderValue() throws Exception {
        Path testClassesRoot = locateTestClassesRoot();
        List<Class<?>> orderedTestClasses = discoverOrderedTestClasses(testClassesRoot);

        List<String> collisions = new ArrayList<>();
        for (Class<?> cls : orderedTestClasses) {
            Map<Integer, List<String>> byOrder = new TreeMap<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class) && m.isAnnotationPresent(Order.class)) {
                    int orderValue = m.getAnnotation(Order.class).value();
                    byOrder.computeIfAbsent(orderValue, k -> new ArrayList<>()).add(m.getName());
                }
            }
            for (Map.Entry<Integer, List<String>> e : byOrder.entrySet()) {
                if (e.getValue().size() > 1) {
                    collisions.add(String.format(
                            "  %s @Order(%d) 被 %d 个方法共用：%s",
                            cls.getName(), e.getKey(), e.getValue().size(),
                            String.join(" , ", e.getValue())));
                }
            }
        }

        assertThat(collisions)
                .as("以下测试方法在同一个测试类里挂了重复的 @Order 值，" +
                        "合并之后谁先跑谁后跑不确定，会变成很难查的偶发红。" +
                        "请给其中一个换个空的号（这个仓库的号段规矩：1~19 主线剧本、20 以上是专题用例）：\n%s",
                        String.join("\n", collisions))
                .isEmpty();
    }

    /**
     * 找到 target/test-classes 根目录：从守门测试类自己的 class 文件位置反推。
     * 这样做而不是写死路径，是为了在 IDE / mvn / CI 里都能跑对。
     */
    private static Path locateTestClassesRoot() throws Exception {
        String selfResource = DuplicateTestOrderGuardTest.class.getSimpleName() + ".class";
        URL selfUrl = DuplicateTestOrderGuardTest.class.getResource(selfResource);
        if (selfUrl == null) {
            throw new IllegalStateException("找不到守门测试类自身的 class 文件，无法定位 test-classes 根目录");
        }
        // 用 toURI + Paths.get 处理 Windows/Linux 差异（Windows 上 URL.getPath() 会多一个前导斜杠）
        Path selfClass = Paths.get(selfUrl.toURI());
        // .../test-classes/com/fastpay/DuplicateTestOrderGuardTest.class → .../test-classes
        return selfClass.getParent().getParent().getParent();
    }

    /**
     * 扫描 target/test-classes 目录，返回所有带 @TestMethodOrder(OrderAnnotation.class) 的类。
     * 加载不出来的类（依赖缺失、反射异常）直接跳过 —— 我们只关心那些标了要按 @Order 排序的类。
     */
    private static List<Class<?>> discoverOrderedTestClasses(Path root) throws IOException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String className = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                        Class<?> cls;
                        try {
                            cls = Class.forName(className, false,
                                    DuplicateTestOrderGuardTest.class.getClassLoader());
                        } catch (Throwable ignore) {
                            return;
                        }
                        TestMethodOrder ann = cls.getAnnotation(TestMethodOrder.class);
                        if (ann != null && ann.value() == MethodOrderer.OrderAnnotation.class) {
                            out.add(cls);
                        }
                    });
        }
        return out;
    }
}
