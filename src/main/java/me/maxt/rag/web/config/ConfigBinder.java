package me.maxt.rag.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 通用配置绑定器：把带 {@link Key} 注解的配置节 record 从三层数据源装配成不可变实例。
 *
 * <p>优先级链（后者覆盖前者）：{@code @Key} 默认值 → config.json（dot 路径）→ 环境变量。
 * 类型由 record 构造参数反射决定，支持 String、int、double、boolean、List&lt;String&gt;
 * （List 兼容 json 数组与逗号分隔串）。环境变量数值非法时打 warn 日志后回退默认值。</p>
 *
 * <p>环境变量查找以函数注入（生产传 {@code System::getenv}），便于测试替换。</p>
 *
 * @author maxt
 * @since 1.0
 */
public final class ConfigBinder {

    private static final Logger log = LoggerFactory.getLogger(ConfigBinder.class);

    private ConfigBinder() {
    }

    /**
     * 装配一个配置节。
     *
     * @param sectionType 带 {@link Key} 注解的 record 类
     * @param fileJson    config.json 解析出的根对象（可为空 Map）
     * @param envLookup   环境变量查找函数（生产传 {@code System::getenv}）
     * @param <R>         节 record 类型
     * @return 装配完成的不可变节实例
     */
    public static <R> R bind(Class<R> sectionType, Map<String, Object> fileJson,
                             Function<String, String> envLookup) {
        RecordComponent[] components = sectionType.getRecordComponents();
        Constructor<R> canonical = canonicalConstructor(sectionType, components);

        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Key key = components[i].getAnnotation(Key.class);
            if (key == null) {
                throw new IllegalArgumentException(
                        "配置节 " + sectionType.getSimpleName() + " 的组件 " + components[i].getName()
                                + " 缺少 @Key 注解");
            }
            args[i] = resolve(components[i], key, fileJson, envLookup);
        }
        try {
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("配置节 " + sectionType.getSimpleName() + " 构造失败", e);
        }
    }

    private static <R> Constructor<R> canonicalConstructor(Class<R> sectionType, RecordComponent[] components) {
        Class<?>[] parameterTypes = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class[]::new);
        try {
            return sectionType.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("找不到配置节 " + sectionType.getSimpleName() + " 的规范构造器", e);
        }
    }

    /** 按优先级链解析单个键：默认值 → config.json → 环境变量 */
    private static Object resolve(RecordComponent component, Key key, Map<String, Object> fileJson,
                                  Function<String, String> envLookup) {
        Class<?> type = component.getType();
        Object fileValue = lookupJsonPath(fileJson, key.json());

        if (type == String.class) {
            String env = envLookup.apply(key.env());
            if (env != null && !env.isEmpty()) {
                return env;
            }
            if (fileValue instanceof String) {
                return fileValue;
            }
            return key.def();
        }
        if (type == int.class) {
            String env = envLookup.apply(key.env());
            if (env != null && !env.isEmpty()) {
                try {
                    return Integer.parseInt(env);
                } catch (NumberFormatException e) {
                    log.warn("环境变量 {}={} 不是合法整数，回退默认值 {}", key.env(), env, key.def());
                }
            }
            if (fileValue instanceof Number) {
                return ((Number) fileValue).intValue();
            }
            return Integer.parseInt(key.def());
        }
        if (type == double.class) {
            String env = envLookup.apply(key.env());
            if (env != null && !env.isEmpty()) {
                try {
                    return Double.parseDouble(env);
                } catch (NumberFormatException e) {
                    log.warn("环境变量 {}={} 不是合法数值，回退默认值 {}", key.env(), env, key.def());
                }
            }
            if (fileValue instanceof Number) {
                return ((Number) fileValue).doubleValue();
            }
            return Double.parseDouble(key.def());
        }
        if (type == boolean.class) {
            String env = envLookup.apply(key.env());
            if (env != null && !env.isEmpty()) {
                return Boolean.parseBoolean(env);
            }
            if (fileValue instanceof Boolean) {
                return (Boolean) fileValue;
            }
            return Boolean.parseBoolean(key.def());
        }
        if (type == List.class) {
            String env = envLookup.apply(key.env());
            if (env != null && !env.isEmpty()) {
                return Arrays.asList(env.split(","));
            }
            if (fileValue instanceof List) {
                return fileValue;
            }
            if (fileValue instanceof String) {
                return Arrays.asList(((String) fileValue).split(","));
            }
            return Arrays.asList(key.def().split(","));
        }
        throw new IllegalArgumentException("配置节组件 " + component.getName()
                + " 的类型 " + type.getSimpleName() + " 不受 @Key 绑定支持");
    }

    /** 沿 dot 路径（如 document.chunking.mode）在 config.json 根对象中取值，任一层缺失返回 null */
    @SuppressWarnings("unchecked")
    private static Object lookupJsonPath(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
        }
        return current;
    }
}
