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

package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ResultSetHandler} 目前唯一实现类
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERED = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;

    /** 处理结果集的 {@link ResultHandler} */
    private final ResultHandler<?> resultHandler;

    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;
    private final ReflectorFactory reflectorFactory;

    /** 记录处理嵌套映射过程中生成的所有结果对象 */
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();

    /** "org.zhenchao.mybatis.dao.BlogMapper.blog_result_map" -> "org.zhenchao.mybatis.entity.Blog@942a29c[id=1, title=MyBatis源码解析, posts=<null>, ]" */
    private final Map<String, Object> ancestorObjects = new HashMap<String, Object>();

    private Object previousRowValue;

    // multiple resultsets
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<CacheKey, List<PendingRelation>>();

    // Cached Automappings
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<String, List<UnMappedColumnAutoMapping>>();

    // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
    private boolean useConstructorMappings;

    private final PrimitiveTypes primitiveTypes;

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

    private static class UnMappedColumnAutoMapping {
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(
            Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler,
            ResultHandler<?> resultHandler, BoundSql boundSql, RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
        this.primitiveTypes = new PrimitiveTypes();
    }

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        /* 1. 处理普通映射情况 */

        // 用于记录结果集映射的结果对象集合
        final List<Object> multipleResults = new ArrayList<Object>();
        int resultSetCount = 0;
        // 获取第一个结果集
        ResultSetWrapper rsw = this.getFirstResultSet(stmt);
        // 获取之前解析得到的封装结果集映射配置的 ResultMap 对象集合
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        int resultMapCount = resultMaps.size();
        // 验证，如果结果集不为 null，则 resultMaps 也不能为空
        this.validateResultMapsCount(rsw, resultMapCount);
        // 遍历处理所有的结果集，基于结果集映射规则进行映射，并将结果记录到 multipleResults 集合中
        while (rsw != null && resultMapCount > resultSetCount) {
            // 遍历获取一个配置的结果集映射对象 <resultMap/>
            ResultMap resultMap = resultMaps.get(resultSetCount);
            // 依据结果集映射配置对结果集对象进行解析，并记录到 multipleResults 集合中
            this.handleResultSet(rsw, resultMap, multipleResults, null);
            // 获取下一个结果集
            rsw = this.getNextResultSet(stmt);
            // 清空 nestedResultObjects
            this.cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        /*
         * 2. 处理多结果集的情况
         * 常见于存储过程，存在 <select resultSets="aaa,bbb"/> 类似的配置
         * 针对过程 1 未执行映射的结果集进行映射
         */
        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {
                // 获取 resultSet 配置名称对应的 ResultMapping 配置
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    // 获取对应的 <resultMap/> 配置
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    // 执行结果集映射
                    this.handleResultSet(rsw, resultMap, null, parentMapping);
                }
                // 获取下一个结果集
                rsw = this.getNextResultSet(stmt);
                // 清空 nestedResultObjects
                this.cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        // multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults
        return this.collapseSingleResultList(multipleResults);
    }

    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        // 获取第一个结果集，封装成 ResultSetWrapper 对象
        ResultSetWrapper rsw = this.getFirstResultSet(stmt);

        // 获取对应的 ResultMap 集合
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();
        this.validateResultMapsCount(rsw, resultMapCount);
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        // 构造 DefaultCursor 对象返回
        return new DefaultCursor<E>(this, resultMap, rsw, rowBounds);
    }

    /**
     * 获取第一个结果集的 {@link ResultSetWrapper} 包装对象
     *
     * @param stmt
     * @return
     * @throws SQLException
     */
    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            if (stmt.getMoreResults()) {
                // 如果还有待处理的结果集
                rs = stmt.getResultSet();
            } else {
                // 已经没有未处理的结果集
                if (stmt.getUpdateCount() == -1) {
                    break;
                }
            }
        }
        // 如果存在未处理的结果集，则封装成 ResultSetWrapper 对象返回
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    /**
     * 获取下一个 {@link ResultSet}
     *
     * @param stmt
     * @return
     * @throws SQLException
     */
    private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
        try {
            // 检测是否支持多结果集
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // 检测是否还有待处理的结果集
                if (!((!stmt.getMoreResults()) && (stmt.getUpdateCount() == -1))) {
                    ResultSet rs = stmt.getResultSet();
                    if (rs == null) {
                        return this.getNextResultSet(stmt);
                    } else {
                        // 封装成 ResultSetWrapper 对象返回
                        return new ResultSetWrapper(rs, configuration);
                    }
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    /**
     * 关闭结果集
     *
     * @param rs
     */
    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
    }

    /**
     * 验证，如果结果集不为 null，则 resultMaps 也不能为空
     *
     * @param rsw
     * @param resultMapCount
     */
    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                    + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    /**
     * 依据 ResultMap 中定义的映射规则对 {@link ResultSet} 进行映射，并将结果对象记录到 multipleResults 参数中
     *
     * @param rsw
     * @param resultMap
     * @param multipleResults
     * @param parentMapping 用于指定父映射配置
     * @throws SQLException
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping)
            throws SQLException {
        try {
            if (parentMapping != null) {
                // 处理多结果集嵌套映射的情况
                this.handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    // 未指定 ResultHandler，构造默认的处理器
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    // 对结果集进行映射，并将映射结果记录到 DefaultResultHandler 对象中
                    this.handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    // 获取保存在 DefaultResultHandler 对象中映射结果，记录到 multipleResults 中
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    // 用户指定了 ResultHandler，使用指定的处理器进行处理
                    this.handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // 关闭结果集
            this.closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
    }

    /**
     * 对结果集进行映射处理
     *
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    public void handleRowValues(
            ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        if (resultMap.hasNestedResultMaps()) {
            // 包含嵌套的结果集映射
            this.ensureNoRowBounds(); // 检测是否允许在嵌套的情况下使用 RowBounds
            this.checkResultHandler(); // 检测是否允许在嵌套的情况下设置 ResultHandler
            // 处理嵌套结果集映射
            this.handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            // 不包含嵌套的简单结果集映射
            this.handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    /**
     * 检测是否允许在嵌套的情况下使用 RowBounds
     */
    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null
                && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                    + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    /**
     * 检测使用允许在嵌套的情况下使用 ResultHandler
     */
    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                    + "Use safeResultHandlerEnabled=false setting to bypass this check "
                    + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    /**
     * 不包含嵌套的简单结果集映射处理
     *
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    private void handleRowValuesForSimpleResultMap(
            ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        // 针对设置了 RowBounds 定位指定的记录行
        this.skipRows(rsw.getResultSet(), rowBounds);
        // 检测是否可以继续对后续的记录行进行映射操作，可以的话就一直循环
        while (this.shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            // 确定具体使用的映射配置，如果配置了 <discriminator/> 则获取最终引用的 ResultMap，否则使用当前 ResultMap
            ResultMap discriminatedResultMap = this.resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            // 基于映射配置对当前记录行进行解析
            Object rowValue = this.getRowValue(rsw, discriminatedResultMap);
            // 保存映射得到的结果对象
            this.storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    /**
     * 保存映射得到的结果对象
     *
     * @param resultHandler
     * @param resultContext
     * @param rowValue
     * @param parentMapping
     * @param rs
     * @throws SQLException
     */
    private void storeObject(
            ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs)
            throws SQLException {
        if (parentMapping != null) {
            // 嵌套查询或映射，将结果对象保存到父对象对应的属性中
            this.linkToParents(rs, parentMapping, rowValue);
        } else {
            // 将结果对象保存到 ResultHandler 中，这里是 DefaultResultHandler
            this.callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
        resultContext.nextResultObject(rowValue);
        ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
    }

    /**
     * 检测是否可以继续对后续的记录行进行映射操作
     *
     * @param context
     * @param rowBounds
     * @return
     * @throws SQLException
     */
    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) throws SQLException {
        // 上下文没有关闭，且当前行数在期望返回之内
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    /**
     * 定位到指定的记录行
     *
     * @param rs
     * @param rowBounds
     * @throws SQLException
     */
    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                // 直接定位到指定的记录行
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                // 通过移动的方法定位到指定的记录行
                rs.next();
            }
        }
    }

    /**
     * 对当前行进行映射解析
     *
     * @param rsw
     * @param resultMap
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        // 创建记录行映射结果对象
        Object rowValue = this.createResultObject(rsw, resultMap, lazyLoader, null);
        // 如果结果对象不为 null，且没有对应的类型处理器
        if (rowValue != null && !this.hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            // 创建结果对象的 MetaObject 对象
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            boolean foundValues = this.useConstructorMappings; // 标记是否成功映射任何一个属性
            // 是否需要自动映射
            if (this.shouldApplyAutomaticMappings(resultMap, false)) {
                // 自动映射未在 <resultMap/> 中指定的映射列
                foundValues = this.applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
            }
            // 映射在 <resultMap/> 中指定的映射列
            foundValues = this.applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            rowValue = (foundValues || configuration.isReturnInstanceForEmptyRow()) ? rowValue : null;
        }
        return rowValue;
    }

    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        if (resultMap.getAutoMapping() != null) {
            return resultMap.getAutoMapping();
        } else {
            if (isNested) {
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    /**
     * 映射明确指定映射关系的列
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private boolean applyPropertyMappings(
            ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        // 获取所有指明了映射关系的列名集合
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        // 获取当前 ResultMap 包含的所有映射关系配置对象 ResultMapping
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        // 遍历处理映射关系 ResultMapping 集合
        for (ResultMapping propertyMapping : propertyMappings) {
            // 处理列前缀
            String column = this.prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // 忽略嵌套的 ResultMap 映射
                column = null;
            }
            // 嵌套查询 || 配置了映射关系 || 多结果集
            if (propertyMapping.isCompositeResult()
                    || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
                    || propertyMapping.getResultSet() != null) { // 存在多结果集
                // 执行映射，返回属性值
                Object value = this.getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                final String property = propertyMapping.getProperty();
                if (property == null) {
                    continue;
                } else if (value == DEFERED) {
                    // 延迟加载的情况
                    foundValues = true;
                    continue;
                }
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
                    // 设置属性值
                    metaObject.setValue(property, value);
                }
            }
        }
        return foundValues;
    }

    /**
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getPropertyMappingValue(
            ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) {
            // 嵌套查询
            return this.getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {
            // 多结果集情况，记录对应的 resultSet，后续处理
            this.addPendingChildRelation(rs, metaResultObject, propertyMapping);
            return DEFERED;
        } else {
            // 基于 TypeHandler 获取属性值
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = this.prependPrefix(propertyMapping.getColumn(), columnPrefix);
            return typeHandler.getResult(rs, column);
        }
    }

    /**
     * 为未映射的列查找对应的属性名，封装成 UnMappedColumnAutoMapping 对象
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private List<UnMappedColumnAutoMapping> createAutomaticMappings(
            ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        final String mapKey = resultMap.getId() + ":" + columnPrefix; // 拼接缓存 key
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
        if (autoMapping == null) {
            // 缓存不命中
            autoMapping = new ArrayList<UnMappedColumnAutoMapping>();
            // 获取未明确指明映射关系的列集合
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            // 遍历处理
            for (String columnName : unmappedColumnNames) {
                String propertyName = columnName;
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    // 指明了列前缀
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        // 移除列前缀
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        // 忽略不包含列前缀的列
                        continue;
                    }
                }
                // 查找结果对象中同名的属性
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
                if (property != null && metaObject.hasSetter(property)) {
                    // 对应属性存在，且存在 setter 方法
                    if (resultMap.getMappedProperties().contains(property)) {
                        // 如果对应的属性有映射配置，则跳过
                        continue;
                    }
                    // 封装成 UnMappedColumnAutoMapping 对象记录到集合中
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        // 异常处理
                        configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    // 异常处理
                    configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }
            // 缓存
            autoMappingsCache.put(mapKey, autoMapping);
        }
        return autoMapping;
    }

    /**
     * 自动映射未在 <resultMap/> 标签中指明的列
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private boolean applyAutomaticMappings(
            ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        // 获取未明确指定映射关系的列集合
        List<UnMappedColumnAutoMapping> autoMapping = this.createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;
        if (!autoMapping.isEmpty()) {
            // 遍历自动映射
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                // 获取对应列的值
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
                    // 设置值到结果对象中
                    metaObject.setValue(mapping.property, value);
                }
            }
        }
        return foundValues;
    }

    // MULTIPLE RESULT SETS

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = this.createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns)
            throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    /**
     * 创建映射得到的结果对象
     *
     * @param rsw
     * @param resultMap
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createResultObject(
            ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        this.useConstructorMappings = false; // reset previous mapping result
        final List<Class<?>> constructorArgTypes = new ArrayList<Class<?>>();
        final List<Object> constructorArgs = new ArrayList<Object>();
        // 创建记录行对应的结果对象（此时还是一个空对象）
        Object resultObject = this.createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        if (resultObject != null && !this.hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    // 存在嵌套查询，且配置为延迟加载，则创建对应的动态代理对象（默认使用 JavassistProxyFactory）
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        this.useConstructorMappings = (resultObject != null && !constructorArgTypes.isEmpty()); // set current mapping result
        return resultObject;
    }

    private Object createResultObject(
            ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
            throws SQLException {
        // 获取 <resultMap/> 的 type 属性，记录行映射的目标对象类型，比如 org.zhenchao.mybatis.entity.Blog
        final Class<?> resultType = resultMap.getType();
        // 创建结果类型的 MetaClass 对象
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        // 获取 <constructor/> 节点配置信息，以确定结果类型中的构造方法
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

        // 创建结果对象

        if (this.hasTypeHandlerForResultObject(rsw, resultType)) {
            // 结果对象含有类型处理器，基于类型处理器转换为对应的 JAVA 类型对象
            return this.createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            // 存在 <constructor/> 配置，基于对应的构造方法创建对象
            return this.createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // 结果类型为接口或存在默认构造函数，则于无参构造方法创建指定类型对象
            return objectFactory.create(resultType);
        } else if (this.shouldApplyAutomaticMappings(resultMap, false)) {
            // 基于自动映射获取对应的构造方法创建结果对象
            return this.createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 基于 <resultMap/> 中配置的构造方法参数类型和值选择合适的构造方法创建结果对象
     *
     * @param rsw
     * @param resultType
     * @param constructorMappings
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     */
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        //
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                if (constructorMapping.getNestedQueryId() != null) {
                    // 如果构造参数值是嵌套查询，执行嵌套查询返回对应的参数值
                    value = this.getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = this.getRowValue(rsw, resultMap);
                } else {
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            } catch (SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                                String columnPrefix) throws SQLException {
        final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
        final Constructor<?> annotatedConstructor = findAnnotatedConstructor(constructors);
        if (annotatedConstructor != null) {
            return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, annotatedConstructor);
        } else {
            for (Constructor<?> constructor : constructors) {
                if (allowedConstructor(constructor, rsw.getClassNames())) {
                    return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
                }
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor)
            throws SQLException {
        boolean foundValues = false;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            Class<?> parameterType = constructor.getParameterTypes()[i];
            String columnName = rsw.getColumnNames().get(i);
            TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
            Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Constructor<?> findAnnotatedConstructor(final Constructor<?>[] constructors) {
        for (final Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
                return constructor;
            }
        }
        return null;
    }

    private boolean allowedConstructor(final Constructor<?> constructor, final List<String> classNames) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (typeNames(parameterTypes).equals(classNames)) return true;
        if (parameterTypes.length != classNames.size()) return false;
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> parameterType = parameterTypes[i];
            if (parameterType.isPrimitive() && !primitiveTypes.getWrapper(parameterType).getName().equals(classNames.get(i))) {
                return false;
            } else if (!parameterType.isPrimitive() && !parameterType.getName().equals(classNames.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<String> typeNames(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<String>();
        for (Class<?> type : parameterTypes) {
            names.add(type.getName());
        }
        return names;
    }

    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = this.prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    /**
     * 对于构造参数值是嵌套查询的情况，执行嵌套查询返回对应的参数值
     *
     * @param rs
     * @param constructorMapping
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        // 获取嵌套查询id对应的 MappedStatement 对象
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        // 获取传递给嵌套查询的参数值
        final Object nestedQueryParameterObject = this.prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            // 执行嵌套查询
            value = resultLoader.loadResult();
        }
        return value;
    }

    /**
     * 处理嵌套查询
     * 如果开启了延迟加载，则创建相应的 {@link ResultLoader} 对象，并返回 DEFERED 标记对象，否则直接执行嵌套查询
     *
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        // 获取嵌套查询 SQL 对应的 MappedStatement 对象
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        // 获取传递给嵌套查询的参数类型和参数值
        final Object nestedQueryParameterObject = this.prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();
            if (executor.isCached(nestedQuery, key)) {
                // 从缓存中加载结果对象
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERED; // 返回 DEFERED 对象标识
            } else {
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    // 开启了延迟加载，则记录 resultLoader 到 ResultLoaderMap 中，需要的时候再执行
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERED;
                } else {
                    // 没有开启延迟记载，直接执行
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix)
            throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix)
            throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix)
            throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<Object, Object>();
        } else if (ParamMap.class.equals(parameterType)) {
            return new HashMap<Object, Object>(); // issue #649
        } else {
            return objectFactory.create(parameterType);
        }
    }

    /**
     * 解析 <discriminator/> 引用链以确定具体使用的 {@link ResultMap}
     *
     * <discriminator javaType="int" column="draft">
     * <case value="1" resultMap="xxx"/>
     * </discriminator>
     *
     * @param rs
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        Set<String> pastDiscriminators = new HashSet<String>(); // 记录已经处理过的 ResultMap 对象的 ID
        Discriminator discriminator = resultMap.getDiscriminator(); // 获取配置的 Discriminator 对象
        while (discriminator != null) {
            // 获取 <discriminator/> 对应的 value
            final Object value = this.getDiscriminatorValue(rs, discriminator, columnPrefix);
            // 依据 value 获取对应的 <resultMap/> 配置 ID
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            // 判断是否存在对应的 <resultMap/> 配置
            if (configuration.hasResultMap(discriminatedMapId)) {
                // 获取 <discriminator/> 指向的 ResultMap
                resultMap = configuration.getResultMap(discriminatedMapId);
                Discriminator lastDiscriminator = discriminator;
                discriminator = resultMap.getDiscriminator();
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    // 出现循环引用
                    break;
                }
            } else {
                break;
            }
        }
        return resultMap;
    }

    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, this.prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    /**
     * 处理嵌套映射
     *
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    private void handleRowValuesForNestedResultMap(
            ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        // 定位到指定记录行
        this.skipRows(rsw.getResultSet(), rowBounds);
        Object rowValue = previousRowValue;
        // 检查是否可以继续处理后续的行，如果可以则一直循环
        while (this.shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            // 尝试解析 <discriminator/> 引用链以确定具体使用的 ResultMap，否则使用当前传递的 resultMap
            final ResultMap discriminatedResultMap = this.resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            final CacheKey rowKey = this.createRowKey(discriminatedResultMap, rsw, null);
            // 获取 CacheKey 对应的嵌套结果对象（嵌套映射处理过程中生成的所有结果对象都会记录到 nestedResultObjects 属性中）
            Object partialObject = nestedResultObjects.get(rowKey);
            /*
             * 检测是否配置 resultOrdered=true，这个设置仅针对嵌套结果 select 语句适用：
             * 如果为 true，则假设包含了嵌套结果集或是分组了，这样的话当返回一个主结果行的时候，就不会发生有对前面结果集的引用的情况。
             * 这就使得在获取嵌套的结果集的时候不至于导致内存不够用。
             */
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    this.storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                rowValue = this.getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                rowValue = this.getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                if (partialObject == null) {
                    this.storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && this.shouldProcessMoreRows(resultContext, rowBounds)) {
            this.storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    /**
     * 映射记录行
     *
     * @param rsw
     * @param resultMap
     * @param combinedKey
     * @param columnPrefix
     * @param partialObject
     * @return
     * @throws SQLException
     */
    private Object getRowValue(
            ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject)
            throws SQLException {
        final String resultMapId = resultMap.getId();
        Object rowValue = partialObject;
        // 判断外层结果对象是否存在
        if (rowValue != null) {
            // 创建 rowValue 的 MetaObject 对象
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            this.putAncestor(rowValue, resultMapId, columnPrefix);
            this.applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {
            // 外层对象不存在
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            // 创建外层结果对象（此时对象映射的属性还未填充）
            rowValue = this.createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            // 如果外层结果对象存在，且不存在对应的类型处理器
            if (rowValue != null && !this.hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                // 创建 rowValue 的 MetaObject 对象
                final MetaObject metaObject = configuration.newMetaObject(rowValue);
                boolean foundValues = this.useConstructorMappings; // 标记是否成功映射任何一个属性
                // 如果需要应用自动映射
                if (this.shouldApplyAutomaticMappings(resultMap, true)) {
                    // 自动映射未在 <resultMap/> 标签中指明的列
                    foundValues = this.applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                // 映射在 <resultMap/> 标签中明确指明的列
                foundValues = this.applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                // 记录外层结果对象到 ancestorObjects 属性中
                this.putAncestor(rowValue, resultMapId, columnPrefix);
                // 处理嵌套映射
                foundValues = this.applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                rowValue = (foundValues || configuration.isReturnInstanceForEmptyRow()) ? rowValue : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                /*
                 * 记录外层结果对象到 nestedResultObjects 中，以备后用
                 *
                 * "-144180651:1479347478:org.zhenchao.mybatis.dao.BlogMapper.blog_result_map:blog_id:1" -> "org.zhenchao.mybatis.entity.Blog@3527942a[id=1,  title=MyBatis源码解析,  posts=[org.zhenchao.mybatis.entity.Post@534a5a98[id=1,  subject=MyBatis配置文件解析,  author=org.zhenchao.mybatis.entity.Author@130c12b7[id=1,  username=zhenchao,  password=123456,  age=26, phone=13212345678,  email=zhenchao.wang@hotmail.com]  comments=[org.zhenchao.mybatis.entity.Comment@5e600dd5[id=1, comment=火钳刘明,  postId=<null>]]]]]"
                 * "832838039:616302599:org.zhenchao.mybatis.dao.BlogMapper.mapper_resultMap[blog_result_map]_collection[posts]_collection[comments]:comment_id:1:-276678898:-2000134882:org.zhenchao.mybatis.dao.BlogMapper.mapper_resultMap[blog_result_map]_collection[posts]:post_id:1:-144180651:1479347478:org.zhenchao.mybatis.dao.BlogMapper.blog_result_map:blog_id:1" -> "org.zhenchao.mybatis.entity.Comment@5e600dd5[id=1, comment=火钳刘明, postId=<null>]"
                 * "1536042362:2749421202:org.zhenchao.mybatis.dao.BlogMapper.author_result_map:author_id:1:-276678898:-2000134882:org.zhenchao.mybatis.dao.BlogMapper.mapper_resultMap[blog_result_map]_collection[posts]:post_id:1:-144180651:1479347478:org.zhenchao.mybatis.dao.BlogMapper.blog_result_map:blog_id:1" -> "org.zhenchao.mybatis.entity.Author@130c12b7[id=1, username=zhenchao, password=123456, age=26, phone=13212345678, email=zhenchao.wang@hotmail.com]"
                 * "-276678898:-2000134882:org.zhenchao.mybatis.dao.BlogMapper.mapper_resultMap[blog_result_map]_collection[posts]:post_id:1:-144180651:1479347478:org.zhenchao.mybatis.dao.BlogMapper.blog_result_map:blog_id:1" -> "org.zhenchao.mybatis.entity.Post@534a5a98[id=1, subject=MyBatis配置文件解析, author=org.zhenchao.mybatis.entity.Author@130c12b7[id=1, username=zhenchao, password=123456, age=26, phone=13212345678, email=zhenchao.wang@hotmail.com] comments=[org.zhenchao.mybatis.entity.Comment@5e600dd5[id=1, comment=火钳刘明, postId=<null>]]]"
                 */
                nestedResultObjects.put(combinedKey, rowValue);
            }
        }
        return rowValue;
    }

    private void putAncestor(Object resultObject, String resultMapId, String columnPrefix) {
        ancestorObjects.put(resultMapId, resultObject);
    }

    /**
     * 处理嵌套映射
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param parentPrefix
     * @param parentRowKey
     * @param newObject
     * @return
     */
    private boolean applyNestedResultMappings(
            ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        // 遍历处理带有 property 属性的映射关系，[title, posts]
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            // 获取嵌套映射 ID，如果不是嵌套映射，nestedResultMapId 为 null，这里会处理 posts
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    // 获取列前缀
                    final String columnPrefix = this.getColumnPrefix(parentPrefix, resultMapping);
                    // 获取嵌套映射对应的 ResultMap 对象
                    final ResultMap nestedResultMap = this.getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    // 处理循环引用
                    if (resultMapping.getColumnPrefix() == null) {
                        Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                        if (ancestorObject != null) {
                            if (newObject) {
                                this.linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
                            }
                            continue;
                        }
                    }
                    // 创建嵌套对象的 key 对象
                    final CacheKey rowKey = this.createRowKey(nestedResultMap, rsw, columnPrefix);
                    final CacheKey combinedKey = this.combineKeys(rowKey, parentRowKey);
                    // 查找集合中是否存在对应的对象
                    Object rowValue = nestedResultObjects.get(combinedKey);
                    boolean knownValue = (rowValue != null);
                    // 初始化外层对象中 Collection 类型的属性
                    this.instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
                    // 根据 notNullColumns 属性配置检测结果集中的空值
                    if (this.anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
                        // 完成映射嵌套，生成嵌套对象
                        rowValue = this.getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
                        if (rowValue != null && !knownValue) {
                            linkObjects(metaObject, resultMapping, rowValue);
                            foundValues = true;
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            ResultSet rs = rsw.getResultSet();
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    return true;
                }
            }
            return false;
        } else if (columnPrefix != null) {
            for (String columnName : rsw.getColumnNames()) {
                if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    //
    // UNIQUE RESULT KEY
    //

    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = this.getResultMappingsForRowKey(resultMap);
        if (resultMappings.isEmpty()) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                this.createRowKeyForMap(rsw, cacheKey);
            } else {
                this.createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            this.createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.isEmpty()) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix)
            throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null || configuration.isReturnInstanceForEmptyRow()) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix)
            throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        if (collectionProperty != null) {
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    /**
     * 判断结果对象是否存在类型处理器
     *
     * @param rsw
     * @param resultType
     * @return
     */
    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
