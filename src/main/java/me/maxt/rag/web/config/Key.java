package me.maxt.rag.web.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置键声明：标注在配置节 record 的构造参数上，一处声明 json 路径、环境变量名与默认值。
 *
 * <p>由 {@link ConfigBinder} 反射读取，按优先级链（本注解默认值 → config.json → 环境变量）解析取值。
 * {@code json} 支持 dot 路径（如 {@code document.chunking.mode}）；{@code def} 为字符串形式的默认值，
 * 按构造参数类型转换（List 以逗号分隔）。</p>
 *
 * @author maxt
 * @since 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Key {

    /** config.json 中的 dot 路径（相对根对象） */
    String json();

    /** 环境变量名（如 {@code RAG_LLM_API_KEY}） */
    String env();

    /** 字符串形式的默认值（按参数类型转换） */
    String def();
}
