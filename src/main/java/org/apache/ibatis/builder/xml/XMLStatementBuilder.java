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

package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

    private final MapperBuilderAssistant builderAssistant;
    private final XNode context;
    private final String requiredDatabaseId;

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
        this(configuration, builderAssistant, context, null);
    }

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
        super(configuration);
        this.builderAssistant = builderAssistant;
        this.context = context;
        this.requiredDatabaseId = databaseId;
    }

    public void parseStatementNode() {
        // 获取 id 和 databaseId 属性
        String id = context.getStringAttribute("id");
        String databaseId = context.getStringAttribute("databaseId");

        // 判断当前 SQL 语句是否适配当前数据库类型，忽略不适配的 SQL 语句
        if (!this.databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
            return;
        }

        /* 获取并解析属性配置 */

        // 解析 SQL 语句类型
        String nodeName = context.getNode().getNodeName();
        SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
        // 标识是否是 SELECT 语句
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
        // 标识任何时候只要语句被调用，都会导致本地缓存和二级缓存被清空，适用于修改数据操作
        boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
        // 设置本条语句的结果是否被二级缓存，默认适用于 SELECT 语句
        boolean useCache = context.getBooleanAttribute("useCache", isSelect);
        // 仅针对嵌套结果 SELECT 语句适用
        boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

        // 解析 <include/> 子标签
        XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
        includeParser.applyIncludes(context.getNode());

        // 解析传入参数类型的完全限定名或别名
        String parameterType = context.getStringAttribute("parameterType");
        Class<?> parameterTypeClass = this.resolveClass(parameterType);

        String lang = context.getStringAttribute("lang");
        LanguageDriver langDriver = this.getLanguageDriver(lang);

        // 解析 <selectKey/> 子标签
        this.processSelectKeyNodes(id, parameterTypeClass, langDriver);

        // 解析对应的 KeyGenerator 实现，用于生成填充 keyProperty 属性指定的列值
        KeyGenerator keyGenerator;
        String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
        // 当前 SQL 语句标签下存在 <selectKey/> 配置，直接获取对应的 SelectKeyGenerator
        if (configuration.hasKeyGenerator(keyStatementId)) {
            keyGenerator = configuration.getKeyGenerator(keyStatementId);
        }
        // 当前 SQL 语句标签下不存在 <selectKey/> 配置
        else {
            // 依据当前标签的 useGeneratedKeys 配置，或全局的 useGeneratedKeys 配置，以及是否是 INSERT 方法来决定具体的 keyGenerator 实现
            // 属性 useGeneratedKeys 仅对 INSERT 和 UPDATE 有用，使用 JDBC 的 getGeneratedKeys 方法取出由数据库内部生成的主键
            keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
                configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        }

        // 创建 SQL 语句标签对应的 SqlSource 对象
        SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
        // 获取具体的 Statement 类型，默认使用 PreparedStatement
        StatementType statementType = StatementType.valueOf(
            context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
        // 设置批量返回的结果行数，默认值为 unset（依赖驱动）
        Integer fetchSize = context.getIntAttribute("fetchSize");
        // 数据库执行超时时间（单位：秒），默认值为 unset（依赖驱动）
        Integer timeout = context.getIntAttribute("timeout");
        String parameterMap = context.getStringAttribute("parameterMap"); // 已废弃
        // 期望返回类型完全限定名或别名，对于集合类型应该是集合元素类型，而非集合类型本身
        String resultType = context.getStringAttribute("resultType");
        Class<?> resultTypeClass = this.resolveClass(resultType);
        // 引用的 <resultMap/> 的标签 ID
        String resultMap = context.getStringAttribute("resultMap");
        // FORWARD_ONLY，SCROLL_SENSITIVE 或 SCROLL_INSENSITIVE 中的一个，默认值为 unset （依赖驱动）
        String resultSetType = context.getStringAttribute("resultSetType");
        ResultSetType resultSetTypeEnum = this.resolveResultSetType(resultSetType);
        if (resultSetTypeEnum == null) {
            resultSetTypeEnum = configuration.getDefaultResultSetType();
        }
        // （仅对 INSERT 和 UPDATE 有用）唯一标记一个属性，通过 getGeneratedKeys 的返回值或者通过 INSERT 语句的 selectKey 子标签设置它的键值
        String keyProperty = context.getStringAttribute("keyProperty");
        // （仅对 INSERT 和 UPDATE 有用）通过生成的键值设置表中的列名，这个设置仅在某些数据库（如 PostgreSQL）是必须的，当主键列不是表中的第一列的时候需要设置
        String keyColumn = context.getStringAttribute("keyColumn");
        // 仅对多结果集适用，将列出语句执行后返回的结果集并给每个结果集一个名称，名称采用逗号分隔
        String resultSets = context.getStringAttribute("resultSets");

        // 创建当前 SQL 语句配置对应的 MappedStatement 对象，并记录到 Configuration#mappedStatements 中
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
            fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
            resultSetTypeEnum, flushCache, useCache, resultOrdered,
            keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
    }

    private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
        // 获取所有的 <selectKey/> 标签
        List<XNode> selectKeyNodes = context.evalNodes("selectKey");
        // 解析 <selectKey/> 标签
        if (configuration.getDatabaseId() != null) {
            this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
        }
        this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
        // 移除 <selectKey/> 标签
        this.removeSelectKeyNodes(selectKeyNodes);
    }

    private void parseSelectKeyNodes(
        String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
        // 遍历处理所有的 <selectKey/> 标签
        for (XNode nodeToHandle : list) {
            String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
            String databaseId = nodeToHandle.getStringAttribute("databaseId");
            // 验证数据库类型是否匹配，忽略不匹配的 <selectKey/> 标签
            if (this.databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
                this.parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
            }
        }
    }

    private void parseSelectKeyNode(
        String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {

        /* 获取相应属性配置 */

        // 解析结果类型配置
        String resultType = nodeToHandle.getStringAttribute("resultType");
        Class<?> resultTypeClass = this.resolveClass(resultType);
        // 解析 statementType 配置，默认使用 PreparedStatement
        StatementType statementType = StatementType.valueOf(
            nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
        // 标签 <selectKey/> 生成结果应用的目标属性，多个用逗号分隔个
        String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
        // 匹配属性的返回结果集中的列名称，多个以逗号分隔
        String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
        // 设置在目标语句前还是后执行
        boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

        // 设置默认值
        boolean useCache = false;
        boolean resultOrdered = false;
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        // 创建对应的 SqlSource 对象（用于封装配置的 SQL 语句，此时的 SQL 语句仍不可执行），默认使用的是 XMLLanguageDriver
        SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        // 创建 SQL 对应的 MappedStatement 对象，记录到 Configuration#mappedStatements 属性中
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
            fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
            resultSetTypeEnum, flushCache, useCache, resultOrdered,
            keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

        id = builderAssistant.applyCurrentNamespace(id, false);

        MappedStatement keyStatement = configuration.getMappedStatement(id, false);
        // 创建对应的 KeyGenerator，记录到 Configuration#keyGenerators 属性中
        configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
    }

    private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
        for (XNode nodeToHandle : selectKeyNodes) {
            nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            return requiredDatabaseId.equals(databaseId);
        }
        if (databaseId != null) {
            return false;
        }
        id = builderAssistant.applyCurrentNamespace(id, false);
        if (!this.configuration.hasStatement(id, false)) {
            return true;
        }
        // skip this statement if there is a previous one with a not null databaseId
        MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
        return previous.getDatabaseId() == null;
    }

    private LanguageDriver getLanguageDriver(String lang) {
        Class<? extends LanguageDriver> langClass = null;
        if (lang != null) {
            langClass = this.resolveClass(lang);
        }
        return configuration.getLanguageDriver(langClass);
    }

}
