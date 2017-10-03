/**
 * Copyright 2009-2015 the original author or authors.
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

package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.BuilderException;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Clinton Begin
 */
public class ExpressionEvaluator {

    /**
     * 解析 OGNL 表达式对应的值，并转换成对应的 boolean 值
     *
     * @param expression
     * @param parameterObject
     * @return
     */
    public boolean evaluateBoolean(String expression, Object parameterObject) {
        // 获取 OGNL 表达式对应的值
        Object value = OgnlCache.getValue(expression, parameterObject);
        // 转换为 boolean 类型返回
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return !new BigDecimal(String.valueOf(value)).equals(BigDecimal.ZERO);
        }
        return value != null;
    }

    /**
     * 解析 OGNL 表达式对应的值，返回值对应的迭代器
     *
     * @param expression
     * @param parameterObject
     * @return
     */
    public Iterable<?> evaluateIterable(String expression, Object parameterObject) {
        // 获取 OGNL 表达式对应的值
        Object value = OgnlCache.getValue(expression, parameterObject);
        if (value == null) {
            throw new BuilderException("The expression '" + expression + "' evaluated to a null value.");
        }
        // 如果是迭代器类型
        if (value instanceof Iterable) {
            return (Iterable<?>) value;
        }
        // 如果是数组类型
        if (value.getClass().isArray()) {
            int size = Array.getLength(value);
            List<Object> answer = new ArrayList<Object>();
            for (int i = 0; i < size; i++) {
                Object o = Array.get(value, i);
                answer.add(o);
            }
            return answer;
        }
        // 如果是 Map 类型
        if (value instanceof Map) {
            return ((Map) value).entrySet();
        }
        throw new BuilderException("Error evaluating expression '" + expression + "'.  Return value (" + value + ") was not iterable.");
    }

}
