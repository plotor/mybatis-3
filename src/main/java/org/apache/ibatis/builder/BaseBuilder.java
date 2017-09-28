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

package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 抽象构造器
 *
 * @author Clinton Begin
 */
public abstract class BaseBuilder {

    /** 全局唯一的配置对象(几乎包含全部的配置信息) */
    protected final Configuration configuration;

    /** 记录别名与类型的映射关系 */
    protected final TypeAliasRegistry typeAliasRegistry;

    /** 记录类型对应的类型处理器 */
    protected final TypeHandlerRegistry typeHandlerRegistry;

    public BaseBuilder(Configuration configuration) {
        this.configuration = configuration;
        this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
        this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * 编译正则表达式
     *
     * @param regex
     * @param defaultValue
     * @return
     */
    protected Pattern parseExpression(String regex, String defaultValue) {
        return Pattern.compile(regex == null ? defaultValue : regex);
    }

    /**
     * 字符串类型转 Boolean
     *
     * @param value
     * @param defaultValue
     * @return
     */
    protected Boolean booleanValueOf(String value, Boolean defaultValue) {
        return value == null ? defaultValue : Boolean.valueOf(value);
    }

    /**
     * 字符串类型转 Integer
     *
     * @param value
     * @param defaultValue
     * @return
     */
    protected Integer integerValueOf(String value, Integer defaultValue) {
        return value == null ? defaultValue : Integer.valueOf(value);
    }

    /**
     * 字符串类型（以逗号分隔）转 Set 集合
     *
     * @param value
     * @param defaultValue
     * @return
     */
    protected Set<String> stringSetValueOf(String value, String defaultValue) {
        value = (value == null ? defaultValue : value);
        return new HashSet<String>(Arrays.asList(value.split(",")));
    }

    /**
     * 获取别名对应的 {@link JdbcType}
     *
     * @param alias
     * @return
     */
    protected JdbcType resolveJdbcType(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return JdbcType.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
        }
    }

    /**
     * 获取别名对应的 {@link ResultSetType}
     *
     * @param alias
     * @return
     */
    protected ResultSetType resolveResultSetType(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return ResultSetType.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
        }
    }

    /**
     * 获取别名对应的 {@link ParameterMode}
     *
     * @param alias
     * @return
     */
    protected ParameterMode resolveParameterMode(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return ParameterMode.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
        }
    }

    /**
     * 创建别名对应的类型的实例，基于 {@link TypeAliasRegistry}
     *
     * @param alias
     * @return
     */
    protected Object createInstance(String alias) {
        Class<?> clazz = this.resolveClass(alias);
        if (clazz == null) {
            return null;
        }
        try {
            return this.resolveClass(alias).newInstance();
        } catch (Exception e) {
            throw new BuilderException("Error creating instance. Cause: " + e, e);
        }
    }

    /**
     * 获取别名对应类型，基于 {@link TypeAliasRegistry}
     *
     * @param alias
     * @return
     */
    protected Class<?> resolveClass(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return this.resolveAlias(alias);
        } catch (Exception e) {
            throw new BuilderException("Error resolving class. Cause: " + e, e);
        }
    }

    /**
     * 获取类型处理器别名对应的 {@link TypeHandler}
     *
     * @param javaType
     * @param typeHandlerAlias
     * @return
     */
    protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
        if (typeHandlerAlias == null) {
            return null;
        }
        // 获取别名对应的类型
        Class<?> type = this.resolveClass(typeHandlerAlias);
        if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
            // 不是 TypeHandler 类型
            throw new BuilderException("Type " + type.getName() + " is not a valid TypeHandler because it does not implement TypeHandler interface");
        }
        Class<? extends TypeHandler<?>> typeHandlerType = (Class<? extends TypeHandler<?>>) type;
        // 获取对应的 TypeHandler
        return this.resolveTypeHandler(javaType, typeHandlerType);
    }

    /**
     * 获取类型处理器对象
     *
     * @param javaType
     * @param typeHandlerType
     * @return
     */
    protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, Class<? extends TypeHandler<?>> typeHandlerType) {
        if (typeHandlerType == null) {
            return null;
        }
        // 基于类型处理器 Class 获取对应的类型处理器对象
        TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
        if (handler == null) {
            // 没有注册，创建一个
            handler = typeHandlerRegistry.getInstance(javaType, typeHandlerType);
        }
        return handler;
    }

    protected Class<?> resolveAlias(String alias) {
        return typeAliasRegistry.resolveAlias(alias);
    }
}
