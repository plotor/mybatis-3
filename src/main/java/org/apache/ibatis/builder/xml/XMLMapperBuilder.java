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
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 用于解析 mapper 映射配置文件
 *
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

    private final XPathParser parser;
    private final MapperBuilderAssistant builderAssistant;
    private final Map<String, XNode> sqlFragments;
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    /**
     * 映射文件解析入口
     */
    public void parse() {
        // 1. 加载并解析映射文件
        if (!configuration.isResourceLoaded(resource)) {
            // 映射文件未加载过, 解析 <mapper/> 下面的配置项
            this.configurationElement(parser.evalNode("/mapper"));
            // 记录到 Configuration.loadedResources 中，标记为已经加载过
            configuration.addLoadedResource(resource);
            // 注册 Mapper 接口（配置在 <mapper namespace=""/> 的 namespace 属性）
            this.bindMapperForNamespace();
        }

        // 2. 处理解析过程中失败的节点

        // 处理解析失败的 <resultMap/> 节点
        this.parsePendingResultMaps();
        // 处理解析失败的 <cache-ref/> 节点
        this.parsePendingCacheRefs();
        // 处理解析失败的 SQL 语句节点
        this.parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析 <mapper /> 配置
     *
     * @param context
     */
    private void configurationElement(XNode context) {
        try {
            // 获取 <mapper/> 节点的 namespace 属性，设置当前映射文件关联的 Mapper 类
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            builderAssistant.setCurrentNamespace(namespace);
            // 解析 <cache-ref/> 配置，多个 mapper 之间可以共享同一个二级缓存
            this.cacheRefElement(context.evalNode("cache-ref"));
            // 解析 <cache/> 配置
            this.cacheElement(context.evalNode("cache"));
            // 解析 <parameterMap/> 配置, 已经废弃
            this.parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            // 解析 <resultMap/> 配置，建立结果集与对象属性之间的映射关系
            this.resultMapElements(context.evalNodes("/mapper/resultMap"));
            // 解析 <sql/> 配置
            this.sqlElement(context.evalNodes("/mapper/sql"));
            // 解析 <select/>, <insert/>, <update/>, <delete/> 配置
            this.buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
        }
    }

    /**
     * 解析 <select/>, <insert/>, <update/>, <delete/> 配置
     *
     * @param list
     */
    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            this.buildStatementFromContext(list, configuration.getDatabaseId());
        }
        this.buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        // 遍历处理获取到的所有 SQL 语句标签
        for (XNode context : list) {
            // 创建 XMLStatementBuilder 对象，负责解析具体的一个 SQL 语句标签
            final XMLStatementBuilder statementParser =
                    new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                // 执行解析操作
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // 记录解析异常的 SQL 语句标签节点到 Configuration.incompleteStatements 属性中
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        // 获取记录的 ResultMapResolver 集合
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            // 遍历应用各个 ResultMapResolver 对象的 resolve 方法
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    /**
     * 解析引用的缓存对象，一个缓存缓存对象，可以被多个 mapper 共享
     *
     * @param context
     */
    private void cacheRefElement(XNode context) {
        if (context != null) {
            // 记录 (当前节点所在的 namespace, 引用缓存对象所在的 namespace) 到 Configuration.cacheRefMap 中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            // 构造缓存引用解析器 CacheRefResolver 对象
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                // 从记录缓存对象的 Configuration.caches 集合中获取引用的缓存对象
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // 如果解析出现异常则记录到 Configuration.incompleteCacheRefs 中，后续再处理
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    /**
     * 解析 <cache/> 配置，MyBatis 二级缓存解析
     * <cache eviction="FIFO" flushInterval="60000" size="512" readOnly="true"/>
     *
     * @param context
     * @throws Exception
     */
    private void cacheElement(XNode context) throws Exception {
        if (context != null) {
            // 获取相应的是属性配置
            String type = context.getStringAttribute("type", "PERPETUAL"); // type，缓存类型
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
            String eviction = context.getStringAttribute("eviction", "LRU"); // eviction， 缓存策略
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
            Long flushInterval = context.getLongAttribute("flushInterval"); // flushInterval，刷新间隔
            Integer size = context.getIntAttribute("size"); // size, 缓存大小
            boolean readWrite = !context.getBooleanAttribute("readOnly", false); // readOnly，是否只读
            boolean blocking = context.getBooleanAttribute("blocking", false); // blocking， 是否阻塞
            Properties props = context.getChildrenAsProperties();
            // 创建二级缓存对象，并记录到 Configuration.caches 中
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    /**
     * 解析 <parameterMap/> 配置, 已经废弃
     *
     * @param list
     * @throws Exception
     */
    private void parameterMapElement(List<XNode> list) throws Exception {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                @SuppressWarnings("unchecked")
                Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    /**
     * 解析 <resultMap/> 配置，建立结果集与对象属性之间的映射关系
     *
     * @param list
     * @throws Exception
     */
    private void resultMapElements(List<XNode> list) throws Exception {
        // 遍历解析所有的 <resultMap/>
        for (XNode resultMapNode : list) {
            try {
                this.resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    /**
     * 解析 <resultMap/> 节点
     *
     * @param resultMapNode
     * @return
     * @throws Exception
     */
    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return this.resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList());
    }

    /**
     * <resultMap id="BaseResultMap" type="org.zhenchao.mybatis.entity.User">
     * <id column="id" jdbcType="BIGINT" property="id" />
     * <result column="username" jdbcType="VARCHAR" property="username" />
     * <result column="password" jdbcType="CHAR" property="password" />
     * <result column="age" jdbcType="INTEGER" property="age" />
     * <result column="phone" jdbcType="VARCHAR" property="phone" />
     * <result column="email" jdbcType="VARCHAR" property="email" />
     * </resultMap>
     *
     * @param resultMapNode
     * @param additionalResultMappings
     * @return
     * @throws Exception
     */
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        // 获取 id 属性（标识当前 <resultMap/>），如果没有指定则使用 XNode.getValueBasedIdentifier() 生成默认值
        String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());

        // 获取 type 属性，支持 type, ofType, resultType, javaType 属性配置
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));

        // 获取 extends 属性，用于指定继承关系
        String extend = resultMapNode.getStringAttribute("extends");

        // 获取 autoMapping 属性，是否启动自动映射（自动查找与列名相同的属性名称，并执行注入）
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

        // 基于 TypeAliasRegistry 解析 type 属性对应的 Class 对象
        Class<?> typeClass = this.resolveClass(type);
        Discriminator discriminator = null;
        List<ResultMapping> resultMappings = new ArrayList<ResultMapping>(); // 用于记录解析结果
        resultMappings.addAll(additionalResultMappings);
        List<XNode> resultChildren = resultMapNode.getChildren(); // 获取所有的子节点
        // 遍历处理子标签
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                // 解析 <constructor /> 子标签
                this.processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                // 解析 <discriminator /> 子标签
                discriminator = this.processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                // 解析 <id/>, <result/>, <association/>, <collection/> 子标签
                List<ResultFlag> flags = new ArrayList<ResultFlag>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                // 创建 ResultMapping 对象，并记录到 resultMappings 集合中
                resultMappings.add(this.buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        ResultMapResolver resultMapResolver =
                new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            // 创建 ResultMap 对象，记录到 Configuration.resultMaps 中
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            // 记录解析异常的 <resultMap/> 节点到 Configuration.incompleteResultMaps 属性中
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    /**
     * 解析 <constructor /> 节点
     *
     * @param resultChild
     * @param resultType
     * @param resultMappings
     * @throws Exception
     */
    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        // 获取 <constructor /> 标签中配置的子节点
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID); // 添加 ID 标识
            }
            // 创建 ResultMapping 对象，并记录到 resultMappings 集合中
            resultMappings.add(this.buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    /**
     * 解析 <discriminator /> 节点
     *
     * @param context
     * @param resultType
     * @param resultMappings
     * @return
     * @throws Exception
     */
    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings)
            throws Exception {
        // 获取相关配置属性
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        // 基于 TypeAliasRegistry 解析类型属性对应的类型对象
        Class<?> javaTypeClass = this.resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
        // 遍历处理子标签配置
        Map<String, String> discriminatorMap = new HashMap<String, String>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            // 嵌套调用
            String resultMap = caseChild.getStringAttribute("resultMap", this.processNestedResultMappings(caseChild, resultMappings));
            discriminatorMap.put(value, resultMap);
        }
        // 创建 Discriminator 对象，本质上依赖于 Discriminator 的构造器进行创建
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    /**
     * 解析 <sql/> 配置，用于配置可复用 SQL
     *
     * @param list
     * @throws Exception
     */
    private void sqlElement(List<XNode> list) throws Exception {
        if (configuration.getDatabaseId() != null) {
            // 获取当前运行数据库环境对应的 databaseId
            this.sqlElement(list, configuration.getDatabaseId());
        }
        this.sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
        // 遍历处理所有的 <sql/> 节点
        for (XNode context : list) {
            String databaseId = context.getStringAttribute("databaseId"); // 获取 databaseId 属性配置
            String id = context.getStringAttribute("id"); // 获取 id 属性配置
            id = builderAssistant.applyCurrentNamespace(id, false); // 格式化 id：namespace.id
            /*
             * 判断 databaseId 与当前 configuration 中配置的是否一致
             * 1. 如果指定了 requiredDatabaseId，则 databaseId 必须和 requiredDatabaseId 一致
             * 2. 如果没有指定了 requiredDatabaseId，则 databaseId 必须为 null
             */
            if (this.databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // 记录到 Configuration.sqlFragments 属性中
                sqlFragments.put(id, context);
            }
        }
    }

    /**
     * @param id
     * @param databaseId
     * @param requiredDatabaseId
     * @return
     */
    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            // 如果指定了 requiredDatabaseId，则 databaseId 必须和 requiredDatabaseId 一致
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果没有指定 requiredDatabaseId
            if (databaseId != null) {
                return false;
            }
            // skip this fragment if there is a previous one with a not null databaseId
            if (this.sqlFragments.containsKey(id)) {
                // 如果当前 sql 节点已经解析过，且之前已经有配置 databaseId，则跳过
                XNode context = this.sqlFragments.get(id);
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 解析配置并创建 {@link ResultMapping} 对象
     *
     * @param context
     * @param resultType
     * @param flags
     * @return
     * @throws Exception
     */
    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        // 获取对应属性配置
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        // 存在嵌套配置，嵌套解析
        String nestedResultMap = context.getStringAttribute("resultMap", this.processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        // 基于 TypeAliasRegistry 解析 JavaType 对应的 Class 对象
        Class<?> javaTypeClass = this.resolveClass(javaType);
        // 基于 TypeAliasRegistry 解析 TypeHandler 对应的 Class 对象
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        // 获取 JdbcType 对应的具体枚举对象
        JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
        // 创建 ResultMapping 对象
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum,
                nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    /**
     * 处理嵌套的情况
     *
     * @param context
     * @param resultMappings
     * @return
     * @throws Exception
     */
    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
        if ("association".equals(context.getName())
                || "collection".equals(context.getName()) || "case".equals(context.getName())) {
            // 如果是 <association/>，<collection/>，<case/> 下的 select 属性
            if (context.getStringAttribute("select") == null) {
                ResultMap resultMap = this.resultMapElement(context, resultMappings);
                return resultMap.getId();
            }
        }
        return null;
    }

    /**
     * 完成映射配置文件与对应 Mapper 接口的绑定
     * 每个 namespace 绑定一个 Mapper 接口
     */
    private void bindMapperForNamespace() {
        // 获取当前映射文件的 namespace 配置
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 获取 namespace 的类型
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                // 当前 boundType 还未加载
                if (!configuration.hasMapper(boundType)) {
                    // 记录当前已经加载的 namespace 标识到 Configuration.loadedResources 属性中
                    configuration.addLoadedResource("namespace:" + namespace);
                    // 注册对应的 Mapper 接口到 Configuration.mapperRegistry 属性中（对应 MapperRegistry）
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
