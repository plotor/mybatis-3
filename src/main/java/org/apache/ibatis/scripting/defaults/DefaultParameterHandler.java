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

package org.apache.ibatis.scripting.defaults;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

    private final TypeHandlerRegistry typeHandlerRegistry;

    /** 封装 SQL 配置标签的全部配置信息 */
    private final MappedStatement mappedStatement;

    /** 用户传入的实参 */
    private final Object parameterObject;

    /** 封装可执行的 SQL 语句 */
    private final BoundSql boundSql;

    private final Configuration configuration;

    public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
        this.mappedStatement = mappedStatement;
        this.configuration = mappedStatement.getConfiguration();
        this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
        this.parameterObject = parameterObject;
        this.boundSql = boundSql;
    }

    @Override
    public Object getParameterObject() {
        return parameterObject;
    }

    @Override
    public void setParameters(PreparedStatement ps) {
        ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
        // 获取 BoundSql 中记录的参数映射关系列表
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings != null) {
            // 遍历为 SQL 语句绑定对应的参数值
            for (int i = 0; i < parameterMappings.size(); i++) {
                ParameterMapping parameterMapping = parameterMappings.get(i);
                // 忽略存储过程中的输出参数
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    // 记录对应的参数值
                    Object value;
                    // 获取参数名称
                    String propertyName = parameterMapping.getProperty();
                    if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
                        // 获取对应的参数值
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (parameterObject == null) {
                        // 用户未传递实参
                        value = null;
                    } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                        // 实参类型存在类型处理，直接转换成对应的目标值
                        value = parameterObject;
                    } else {
                        // 获取实参对象中对应的参数值
                        MetaObject metaObject = configuration.newMetaObject(parameterObject);
                        value = metaObject.getValue(propertyName);
                    }
                    TypeHandler typeHandler = parameterMapping.getTypeHandler();
                    JdbcType jdbcType = parameterMapping.getJdbcType();
                    if (value == null && jdbcType == null) {
                        jdbcType = configuration.getJdbcTypeForNull();
                    }
                    try {
                        // 为 SQL 语句绑定对应的实参
                        typeHandler.setParameter(ps, i + 1, value, jdbcType);
                    } catch (TypeException e) {
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    } catch (SQLException e) {
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    }
                }
            }
        }
    }

}
