/**
 * Copyright 2009-2020 the original author or authors.
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
import org.apache.ibatis.reflection.MetaClass;
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
 * @author Clinton Begin
 * @author Kazuki Shimizu
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

    public void parse() {
        /* 1. 加载并解析映射文件 */
        if (!configuration.isResourceLoaded(resource)) {
            // 加载并解析 <mapper/> 标签下的配置
            this.configurationElement(parser.evalNode("/mapper"));
            // 标记该映射文件已被加载
            configuration.addLoadedResource(resource);
            // 注册 Mapper 接口（<mapper namespace=""/> 配置对应的 namespace 属性）
            this.bindMapperForNamespace();
        }

        /* 2. 处理解析过程中失败的标签 */

        // 处理解析失败的 <resultMap/> 标签
        this.parsePendingResultMaps();
        // 处理解析失败的 <cache-ref/> 标签
        this.parsePendingCacheRefs();
        // 处理解析失败的 SQL 语句标签
        this.parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    private void configurationElement(XNode context) {
        try {
            // 获取 <mapper/> 标签的 namespace 属性，设置当前映射文件关联的 Mapper 接口
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            builderAssistant.setCurrentNamespace(namespace);
            // 解析 <cache-ref/> 子标签，多个 mapper 可以共享同一个二级缓存
            this.cacheRefElement(context.evalNode("cache-ref"));
            // 解析 <cache/> 子标签
            this.cacheElement(context.evalNode("cache"));
            // 解析 <parameterMap/> 子标签，已废弃
            this.parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            // 解析 <resultMap/> 子标签，建立结果集与对象属性之间的映射关系
            this.resultMapElements(context.evalNodes("/mapper/resultMap"));
            // 解析 <sql/> 子标签
            this.sqlElement(context.evalNodes("/mapper/sql"));
            // 解析 <select/>、<insert/>、<update/> 和 <delete/> 子标签
            this.buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            this.buildStatementFromContext(list, configuration.getDatabaseId());
        }
        this.buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        // 遍历处理获取到的所有 SQL 语句标签
        for (XNode context : list) {
            // 创建 XMLStatementBuilder 解析器，负责解析具体的 SQL 语句标签
            final XMLStatementBuilder statementParser =
                new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                // 执行解析操作
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // 记录解析异常的 SQL 语句标签，稍后尝试二次解析
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
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

    private void cacheRefElement(XNode context) {
        if (context != null) {
            // 记录 <当前节点所在的 namespace, 引用缓存对象所在的 namespace> 到 Configuration 中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            // 构造缓存引用解析器 CacheRefResolver 对象
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                // 从记录缓存对象的 Configuration#caches 集合中获取引用的缓存对象
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // 如果解析出现异常则记录到 Configuration#incompleteCacheRefs 中，稍后再处理
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    private void cacheElement(XNode context) {
        if (context != null) {
            // 获取相应的是属性配置
            String type = context.getStringAttribute("type", "PERPETUAL"); // type，缓存类型，可以指定自定义实现
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
            String eviction = context.getStringAttribute("eviction", "LRU"); // eviction，缓存策略
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
            Long flushInterval = context.getLongAttribute("flushInterval"); // flushInterval，刷新间隔
            Integer size = context.getIntAttribute("size"); // size，缓存大小
            boolean readWrite = !context.getBooleanAttribute("readOnly", false); // readOnly，是否只读
            boolean blocking = context.getBooleanAttribute("blocking", false); // blocking，是否阻塞
            Properties props = context.getChildrenAsProperties();
            // 创建二级缓存，并填充 Configuration 对象
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = this.resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = this.resolveParameterMode(mode);
                Class<?> javaTypeClass = this.resolveClass(javaType);
                JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = this.resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    private void resultMapElements(List<XNode> list) {
        for (XNode resultMapNode : list) {
            try {
                this.resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) {
        return this.resultMapElement(resultMapNode, Collections.emptyList(), null);
    }

    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        // 获取 type 属性，支持 type、ofType、resultType，以及 javaType 配置
        String type = resultMapNode.getStringAttribute("type",
            resultMapNode.getStringAttribute("ofType",
                resultMapNode.getStringAttribute("resultType",
                    resultMapNode.getStringAttribute("javaType"))));
        // 基于 TypeAliasRegistry 解析 type 属性对应的 Class 对象
        Class<?> typeClass = this.resolveClass(type);
        if (typeClass == null) {
            typeClass = this.inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;
        // 用于记录解析结果
        List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
        // 获取并遍历处理所有的子标签
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            // 解析 <constructor/> 子标签
            if ("constructor".equals(resultChild.getName())) {
                this.processConstructorElement(resultChild, typeClass, resultMappings);
            }
            // 解析 <discriminator/> 子标签
            else if ("discriminator".equals(resultChild.getName())) {
                discriminator = this.processDiscriminatorElement(resultChild, typeClass, resultMappings);
            }
            // 解析 <association/>、<collection/>、<id/> 和 <result/> 子标签
            else {
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                // 创建 ResultMapping 对象，并记录到 resultMappings 集合中
                resultMappings.add(this.buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        // 获取 id 属性（标识当前 <resultMap/> 标签），如果没有指定则基于规则生成一个
        String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());
        // 获取 extends 属性，用于指定继承关系
        String extend = resultMapNode.getStringAttribute("extends");
        // 获取 autoMapping 属性，是否启用自动映射（自动查找与列名相同的属性名称，并执行注入）
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        ResultMapResolver resultMapResolver = new ResultMapResolver(
            builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            // 创建 ResultMap 对象，记录到 Configuration#resultMaps 中
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            // 记录解析异常的 <resultMap/> 标签，后续尝试二次解析
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    private void processConstructorElement(
        XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
        // 获取并处理 <constructor/> 标签中配置的子标签列表
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID); // 添加 ID 标识
            }
            // 创建 ResultMapping 对象，记录到 resultMappings 集合中
            resultMappings.add(this.buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    private Discriminator processDiscriminatorElement(
        XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
        // 获取相关属性配置
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        // 基于 TypeAliasRegistry 解析类型属性对应的 Class 对象
        Class<?> javaTypeClass = this.resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = this.resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
        // 遍历处理子标签列表
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            String resultMap = caseChild.getStringAttribute("resultMap",
                // 嵌套解析
                this.processNestedResultMappings(caseChild, resultMappings, resultType));
            discriminatorMap.put(value, resultMap);
        }
        // 创建 Discriminator 对象，本质上依赖于 Discriminator 的构造器构建
        return builderAssistant.buildDiscriminator(
            resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            // 获取当前运行数据库环境对应的 databaseId
            this.sqlElement(list, configuration.getDatabaseId());
        }
        this.sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        // 遍历处理所有的 <sql/> 标签
        for (XNode context : list) {
            // 获取 databaseId 属性
            String databaseId = context.getStringAttribute("databaseId");
            // 获取 id 属性
            String id = context.getStringAttribute("id");
            // 格式化 id，格式：namespace.id
            id = builderAssistant.applyCurrentNamespace(id, false);
            /*
             * 判断 databaseId 与当前 Configuration 中配置的是否一致：
             * 1. 如果指定了 requiredDatabaseId，则 databaseId 必须和 requiredDatabaseId 一致
             * 2. 如果没有指定了 requiredDatabaseId，则 databaseId 必须为 null
             */
            if (this.databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            return requiredDatabaseId.equals(databaseId);
        }
        if (databaseId != null) {
            return false;
        }
        if (!this.sqlFragments.containsKey(id)) {
            return true;
        }
        // skip this fragment if there is a previous one with a not null databaseId
        XNode context = this.sqlFragments.get(id);
        return context.getStringAttribute("databaseId") == null;
    }

    private ResultMapping buildResultMappingFromContext(
        XNode context, Class<?> resultType, List<ResultFlag> flags) {
        // 获取对应的属性配置
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
        String nestedResultMap = context.getStringAttribute("resultMap", () ->
            this.processNestedResultMappings(context, Collections.emptyList(), resultType));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        // 基于 TypeAliasRegistry 解析 JavaType 对应的 Class 对象
        Class<?> javaTypeClass = this.resolveClass(javaType);
        // 基于 TypeAliasRegistry 解析 TypeHandler 对应的 Class 对象
        Class<? extends TypeHandler<?>> typeHandlerClass = this.resolveClass(typeHandler);
        // 获取 JdbcType 对应的具体枚举对象
        JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
        // 创建 ResultMapping 对象
        return builderAssistant.buildResultMapping(
            resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap,
            notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
        if ("association".equals(context.getName())
            || "collection".equals(context.getName())
            || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                this.validateCollection(context, enclosingType);
                ResultMap resultMap = this.resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
            && context.getStringAttribute("javaType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                    "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
            }
        }
    }

    private void bindMapperForNamespace() {
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    configuration.addLoadedResource("namespace:" + namespace);
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
