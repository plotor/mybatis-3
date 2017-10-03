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

/**
 * 对应动态 SQL 中的 <bind/> 节点，可以从 OGNL 表达式中创建一个变量并将其绑定到上下文
 *
 * <select id="selectBlogsLike" resultType="Blog">
 * <bind name="pattern" value="'%' + _parameter.getTitle() + '%'" />
 * SELECT * FROM BLOG
 * WHERE title LIKE #{pattern}
 * </select>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class VarDeclSqlNode implements SqlNode {

    private final String name;
    private final String expression;

    public VarDeclSqlNode(String var, String exp) {
        name = var;
        expression = exp;
    }

    @Override
    public boolean apply(DynamicContext context) {
        // 解析 OGNL 表达式对应的值
        final Object value = OgnlCache.getValue(expression, context.getBindings());
        // 绑定到上下文中，name 对应属性 <bind/> 标签的 name 属性配置
        context.bind(name, value);
        return true;
    }

}
