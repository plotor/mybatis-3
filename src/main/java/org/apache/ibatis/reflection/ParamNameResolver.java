/**
 * Copyright 2009-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ibatis.reflection;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class ParamNameResolver {

    public static final String GENERIC_NAME_PREFIX = "param";

    /**
     * 记录参数在参数列表中的下标和参数名称之间的对应关系。
     * 参数名称通过 {@link Param} 注解指定，如果没有指定则使用参数下标作为参数名称，
     * 需要注意的是，如果参数列表中包含 {@link RowBounds} 或 {@link ResultHandler} 类型的参数，
     * 这两种功能型参数不会记录到集合中，此时如果用下标表示参数名称，索引值 key 与对应的参数名称（实际索引）可能会不一致。
     *
     * <p>
     * The key is the index and the value is the name of the parameter.<br />
     * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
     * the parameter index is used. Note that this index could be different from the actual index
     * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
     * </p>
     * <ul>
     * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
     * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
     * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
     * </ul>
     */
    private final SortedMap<Integer, String> names;

    private boolean hasParamAnnotation;

    public ParamNameResolver(Configuration config, Method method) {
        // 获取参数类型列表
        final Class<?>[] paramTypes = method.getParameterTypes();
        // 获取参数列表上的注解列表
        final Annotation[][] paramAnnotations = method.getParameterAnnotations();
        final SortedMap<Integer, String> map = new TreeMap<>();
        int paramCount = paramAnnotations.length;

        // 遍历处理方法所有的参数
        for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
            // 跳过 RowBounds 和 ResultHandler 类型参数
            if (isSpecialParameter(paramTypes[paramIndex])) {
                continue;
            }

            // 查找当前参数是否有 @Param 注解
            String name = null;
            for (Annotation annotation : paramAnnotations[paramIndex]) {
                // 获取注解指定的参数名称
                if (annotation instanceof Param) {
                    hasParamAnnotation = true;
                    name = ((Param) annotation).value();
                    break;
                }
            }

            // 没有 @Param 注解
            if (name == null) {
                // 基于配置开关决定是否获取参数的真实名称
                if (config.isUseActualParamName()) {
                    name = this.getActualParamName(method, paramIndex);
                }
                // 使用下标作为参数名称
                if (name == null) {
                    // use the parameter index as the name ("0", "1", ...)
                    // gcode issue #71
                    name = String.valueOf(map.size());
                }
            }
            map.put(paramIndex, name);
        }
        names = Collections.unmodifiableSortedMap(map);
    }

    private String getActualParamName(Method method, int paramIndex) {
        return ParamNameUtil.getParamNames(method).get(paramIndex);
    }

    private static boolean isSpecialParameter(Class<?> clazz) {
        return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
    }

    /**
     * Returns parameter names referenced by SQL providers.
     */
    public String[] getNames() {
        return names.values().toArray(new String[0]);
    }

    /**
     * <p>
     * A single non-special parameter is returned without a name.
     * Multiple parameters are named using the naming rule.
     * In addition to the default names, this method also adds the generic names (param1, param2,
     * ...).
     * </p>
     */
    public Object getNamedParams(Object[] args) {
        // names 属性记录参数在参数列表中的下标和参数名称之间的对应关系
        final int paramCount = names.size();
        // 无参方法，直接返回
        if (args == null || paramCount == 0) {
            return null;
        }
        // 没有 @Param 注解，且只有一个参数
        else if (!hasParamAnnotation && paramCount == 1) {
            return args[names.firstKey()];
        }
        // 有 @Param 注解，或存在多个参数
        else {
            final Map<String, Object> param = new ParamMap<>();
            int i = 0;
            // 遍历处理参数列表中的非功能性参数
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                // 记录参数名称与参数值之间的映射关系
                param.put(entry.getValue(), args[entry.getKey()]);
                // 构造一般参数名称，即 (param1, param2, ...) 形式参数
                final String genericParamName = GENERIC_NAME_PREFIX + (i + 1);
                // 以“param + 索引”的形式再记录一次，如果 @Param 指定的参数名称已经是这种形式则不覆盖
                if (!names.containsValue(genericParamName)) {
                    param.put(genericParamName, args[entry.getKey()]);
                }
                i++;
            }
            return param;
        }
    }
}
