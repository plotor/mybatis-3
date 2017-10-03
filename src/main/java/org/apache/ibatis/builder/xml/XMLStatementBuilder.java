/**
 * Copyright 2009-2017 the original author or authors.
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
 * SQL语句解析
 *
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
        this.context = context; // 对应具体的一个 SQL 语句标签
        this.requiredDatabaseId = databaseId;
    }

    /**
     * 解析 SQL 节点
     */
    public void parseStatementNode() {
        // 获取 id 和 databaseId
        String id = context.getStringAttribute("id");
        String databaseId = context.getStringAttribute("databaseId");

        // 判断当前SQL是否适配当前数据库，对于不适配的SQL直接忽略
        if (!this.databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
            return;
        }

        // 获取属性配置
        Integer fetchSize = context.getIntAttribute("fetchSize"); // 设置批量返回的结果行数，默认值为 unset（依赖驱动）
        Integer timeout = context.getIntAttribute("timeout"); // 数据库执行超时时间，默认值为 unset（依赖驱动）
        String parameterMap = context.getStringAttribute("parameterMap"); // parameterMap 参数已经废弃
        String parameterType = context.getStringAttribute("parameterType"); // 传入参数类型的完全限定名或别名
        Class<?> parameterTypeClass = this.resolveClass(parameterType);
        String resultMap = context.getStringAttribute("resultMap"); // 引用的 <resultMap/> 的标签ID
        String resultType = context.getStringAttribute("resultType"); // 期望返回类型完全限定名或别名。注意如果是集合情形，那应该是集合可以包含的类型，而不能是集合本身
        String lang = context.getStringAttribute("lang");
        LanguageDriver langDriver = this.getLanguageDriver(lang);
        Class<?> resultTypeClass = this.resolveClass(resultType);
        String resultSetType = context.getStringAttribute("resultSetType"); // FORWARD_ONLY，SCROLL_SENSITIVE 或 SCROLL_INSENSITIVE 中的一个，默认值为 unset （依赖驱动）
        StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString())); // 具体的 Statement 类型，参考 StatementType
        ResultSetType resultSetTypeEnum = this.resolveResultSetType(resultSetType);
        String nodeName = context.getNode().getNodeName();
        SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT; // 是否是 SELECT 语句
        boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect); // 任何时候只要语句被调用，都会导致本地缓存和二级缓存被清空，默认为 false
        boolean useCache = context.getBooleanAttribute("useCache", isSelect); // 设置本条语句的结果被二级缓存，默认值：对 select 元素为 true
        boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false); // 仅针对嵌套结果 select 语句适用

        // 解析 <include /> 节点
        XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
        includeParser.applyIncludes(context.getNode());

        // 解析 <selectKey /> 节点
        this.processSelectKeyNodes(id, parameterTypeClass, langDriver);

        // 解析 SQL 配置 (pre: <selectKey> and <include> were parsed and removed)
        // 创建 SQL 语句节点对应的 SqlSource 对象
        SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
        // 获取 resultSets、keyProperty，以及 keyColumn
        String resultSets = context.getStringAttribute("resultSets"); // 仅对多结果集适用，将列出语句执行后返回的结果集并给每个结果集一个名称，名称是逗号分隔的。
        String keyProperty = context.getStringAttribute("keyProperty"); // （仅对 insert 和 update 有用）唯一标记一个属性，通过 getGeneratedKeys 的返回值或者通过 insert 语句的 selectKey 子元素设置它的键值
        String keyColumn = context.getStringAttribute("keyColumn"); // （仅对 insert 和 update 有用）通过生成的键值设置表中的列名，这个设置仅在某些数据库（像 PostgreSQL）是必须的，当主键列不是表中的第一列的时候需要设置

        // 获取 <selectKey/> 对应的 SelectKeyGenerator
        KeyGenerator keyGenerator;
        String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
        if (configuration.hasKeyGenerator(keyStatementId)) {
            keyGenerator = configuration.getKeyGenerator(keyStatementId);
        } else {
            keyGenerator = context.getBooleanAttribute("useGeneratedKeys", // （仅对 insert 和 update 有用）这会使用 JDBC 的 getGeneratedKeys 方法来取出由数据库内部生成的主键
                    configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                    ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        }

        // 创建当前 SQL 语句配置对应的 MappedStatement 对象，并记录到 Configuration.mappedStatements 中
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
    }

    /**
     * 解析 <selectKey /> 节点，用于解决主键自增，用于 <insert/> 和 <update/> 节点
     *
     * @param id 上层节点的 id 属性
     * @param parameterTypeClass
     * @param langDriver
     */
    private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
        // 获取所有的 <selectKey/> 节点
        List<XNode> selectKeyNodes = context.evalNodes("selectKey");
        // 解析 <selectKey/> 节点
        if (configuration.getDatabaseId() != null) {
            this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
        }
        this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);

        // 移除 <selectKey/> 节点
        this.removeSelectKeyNodes(selectKeyNodes);
    }

    /**
     * 解析 <selectKey/> 节点
     *
     * @param parentId
     * @param list
     * @param parameterTypeClass
     * @param langDriver
     * @param skRequiredDatabaseId
     */
    private void parseSelectKeyNodes(
            String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
        // 遍历处理所有的 <selectKey/>
        for (XNode nodeToHandle : list) {
            String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
            String databaseId = nodeToHandle.getStringAttribute("databaseId");
            if (this.databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
                // 验证数据库环境是否匹配，忽略不匹配的 <selectKey/> 配置
                this.parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
            }
        }
    }

    private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
        // 获取相应属性配置
        String resultType = nodeToHandle.getStringAttribute("resultType"); // 结果集类型
        Class<?> resultTypeClass = this.resolveClass(resultType);
        StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
        String keyProperty = nodeToHandle.getStringAttribute("keyProperty"); // selectKey 生成结果应用的目标属性，多个用逗号分隔个
        String keyColumn = nodeToHandle.getStringAttribute("keyColumn"); // 匹配属性的返回结果集中的列名称，多个以逗号分隔
        boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER")); // 在操作语句前还是后执行

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

        // 创建对应的 SqlSource（用于封装配置的SQL语句，不可执行），默认使用的是 XMLLanguageDriver
        SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        // 创建 SQL 对应的 MappedStatement 对象，并添加到 Configuration.mappedStatements 属性中
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);
        id = builderAssistant.applyCurrentNamespace(id, false);
        MappedStatement keyStatement = configuration.getMappedStatement(id, false);

        // 创建对应的 KeyGenerator，并添加到 Configuration.keyGenerators 属性中
        configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
    }

    private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
        for (XNode nodeToHandle : selectKeyNodes) {
            nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
        }
    }

    /**
     * 校验 databaseId 是否与当前的 databaseId 匹配
     *
     * @param id
     * @param databaseId
     * @param requiredDatabaseId
     * @return
     */
    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            // databaseId 不等于 requiredDatabaseId
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            if (databaseId != null) {
                return false;
            }
            // skip this statement if there is a previous one with a not null databaseId
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (this.configuration.hasStatement(id, false)) {
                MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
                if (previous.getDatabaseId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private LanguageDriver getLanguageDriver(String lang) {
        Class<?> langClass = null;
        if (lang != null) {
            langClass = this.resolveClass(lang);
        }
        return builderAssistant.getLanguageDriver(langClass);
    }

}
