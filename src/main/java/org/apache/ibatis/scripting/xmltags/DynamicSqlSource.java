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

package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

    private final Configuration configuration;
    private final SqlNode rootSqlNode;

    public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
        this.configuration = configuration;
        this.rootSqlNode = rootSqlNode;
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // 构造上下文对象
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        // 应用 SqlNode#apply 方法（树型结构，会遍历应用树中各个节点的 SqlNode#apply 方法），各司其职追加 SQL 片段到上下文中
        rootSqlNode.apply(context);
        // 创建 SqlSourceBuilder 对象，解析占位符属性，并将 SQL 语句中的 #{} 占位符替换成 ? 字符
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass(); // 解析用户实参类型
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings()); // 解析并封装结果为 StaticSqlSource 对象
        // 基于 SqlSourceBuilder 解析结果和实参创建 BoundSql 对象
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        // 将 DynamicContext#bindings 中的参数信息复制到 BoundSql#additionalParameters 属性中
        context.getBindings().forEach(boundSql::setAdditionalParameter);
        return boundSql;
    }

}
