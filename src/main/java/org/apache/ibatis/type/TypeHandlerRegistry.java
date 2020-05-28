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

package org.apache.ibatis.type;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.JapaneseDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

    /**
     * 记录 JDBC 类型与 {@link TypeHandler} 之间映射关系，
     * 用于从结果集读取数据时，将 JDBC 类型转换对应的 java 类型
     */
    private final Map<JdbcType, TypeHandler<?>> jdbcTypeHandlerMap = new EnumMap<>(JdbcType.class);

    /**
     * 记录 java 类型转 JDBC 类型时所需要的 {@link TypeHandler}，
     * 一个 java 类型可能存在多个 JDBC 类型
     */
    private final Map<Type, Map<JdbcType, TypeHandler<?>>> typeHandlerMap = new ConcurrentHashMap<>();

    private final TypeHandler<Object> unknownTypeHandler;

    private final Map<Class<?>, TypeHandler<?>> allTypeHandlersMap = new HashMap<>();

    private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections.emptyMap();

    private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

    /**
     * The default constructor.
     */
    public TypeHandlerRegistry() {
        this(new Configuration());
    }

    /**
     * The constructor that pass the MyBatis configuration.
     *
     * @param configuration a MyBatis configuration
     * @since 3.5.4
     */
    public TypeHandlerRegistry(Configuration configuration) {
        this.unknownTypeHandler = new UnknownTypeHandler(configuration);

        this.register(Boolean.class, new BooleanTypeHandler());
        this.register(boolean.class, new BooleanTypeHandler());
        this.register(JdbcType.BOOLEAN, new BooleanTypeHandler());
        this.register(JdbcType.BIT, new BooleanTypeHandler());

        this.register(Byte.class, new ByteTypeHandler());
        this.register(byte.class, new ByteTypeHandler());
        this.register(JdbcType.TINYINT, new ByteTypeHandler());

        this.register(Short.class, new ShortTypeHandler());
        this.register(short.class, new ShortTypeHandler());
        this.register(JdbcType.SMALLINT, new ShortTypeHandler());

        this.register(Integer.class, new IntegerTypeHandler());
        this.register(int.class, new IntegerTypeHandler());
        this.register(JdbcType.INTEGER, new IntegerTypeHandler());

        this.register(Long.class, new LongTypeHandler());
        this.register(long.class, new LongTypeHandler());

        this.register(Float.class, new FloatTypeHandler());
        this.register(float.class, new FloatTypeHandler());
        this.register(JdbcType.FLOAT, new FloatTypeHandler());

        this.register(Double.class, new DoubleTypeHandler());
        this.register(double.class, new DoubleTypeHandler());
        this.register(JdbcType.DOUBLE, new DoubleTypeHandler());

        this.register(Reader.class, new ClobReaderTypeHandler());
        this.register(String.class, new StringTypeHandler());
        this.register(String.class, JdbcType.CHAR, new StringTypeHandler());
        this.register(String.class, JdbcType.CLOB, new ClobTypeHandler());
        this.register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
        this.register(String.class, JdbcType.LONGVARCHAR, new StringTypeHandler());
        this.register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
        this.register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
        this.register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
        this.register(JdbcType.CHAR, new StringTypeHandler());
        this.register(JdbcType.VARCHAR, new StringTypeHandler());
        this.register(JdbcType.CLOB, new ClobTypeHandler());
        this.register(JdbcType.LONGVARCHAR, new StringTypeHandler());
        this.register(JdbcType.NVARCHAR, new NStringTypeHandler());
        this.register(JdbcType.NCHAR, new NStringTypeHandler());
        this.register(JdbcType.NCLOB, new NClobTypeHandler());

        this.register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
        this.register(JdbcType.ARRAY, new ArrayTypeHandler());

        this.register(BigInteger.class, new BigIntegerTypeHandler());
        this.register(JdbcType.BIGINT, new LongTypeHandler());

        this.register(BigDecimal.class, new BigDecimalTypeHandler());
        this.register(JdbcType.REAL, new BigDecimalTypeHandler());
        this.register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
        this.register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

        this.register(InputStream.class, new BlobInputStreamTypeHandler());
        this.register(Byte[].class, new ByteObjectArrayTypeHandler());
        this.register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
        this.register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
        this.register(byte[].class, new ByteArrayTypeHandler());
        this.register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
        this.register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
        this.register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
        this.register(JdbcType.BLOB, new BlobTypeHandler());

        this.register(Object.class, unknownTypeHandler);
        this.register(Object.class, JdbcType.OTHER, unknownTypeHandler);
        this.register(JdbcType.OTHER, unknownTypeHandler);

        this.register(Date.class, new DateTypeHandler());
        this.register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
        this.register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
        this.register(JdbcType.TIMESTAMP, new DateTypeHandler());
        this.register(JdbcType.DATE, new DateOnlyTypeHandler());
        this.register(JdbcType.TIME, new TimeOnlyTypeHandler());

        this.register(java.sql.Date.class, new SqlDateTypeHandler());
        this.register(java.sql.Time.class, new SqlTimeTypeHandler());
        this.register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

        this.register(String.class, JdbcType.SQLXML, new SqlxmlTypeHandler());

        this.register(Instant.class, new InstantTypeHandler());
        this.register(LocalDateTime.class, new LocalDateTimeTypeHandler());
        this.register(LocalDate.class, new LocalDateTypeHandler());
        this.register(LocalTime.class, new LocalTimeTypeHandler());
        this.register(OffsetDateTime.class, new OffsetDateTimeTypeHandler());
        this.register(OffsetTime.class, new OffsetTimeTypeHandler());
        this.register(ZonedDateTime.class, new ZonedDateTimeTypeHandler());
        this.register(Month.class, new MonthTypeHandler());
        this.register(Year.class, new YearTypeHandler());
        this.register(YearMonth.class, new YearMonthTypeHandler());
        this.register(JapaneseDate.class, new JapaneseDateTypeHandler());

        // issue #273
        this.register(Character.class, new CharacterTypeHandler());
        this.register(char.class, new CharacterTypeHandler());
    }

    /**
     * Set a default {@link TypeHandler} class for {@link Enum}.
     * A default {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
     *
     * @param typeHandler a type handler class for {@link Enum}
     * @since 3.4.5
     */
    public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
        this.defaultEnumTypeHandler = typeHandler;
    }

    public boolean hasTypeHandler(Class<?> javaType) {
        return this.hasTypeHandler(javaType, null);
    }

    public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
        return this.hasTypeHandler(javaTypeReference, null);
    }

    public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
        return javaType != null && this.getTypeHandler((Type) javaType, jdbcType) != null;
    }

    public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
        return javaTypeReference != null && this.getTypeHandler(javaTypeReference, jdbcType) != null;
    }

    public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
        return allTypeHandlersMap.get(handlerType);
    }

    public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
        return this.getTypeHandler((Type) type, null);
    }

    public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
        return this.getTypeHandler(javaTypeReference, null);
    }

    public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
        return jdbcTypeHandlerMap.get(jdbcType);
    }

    public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
        return this.getTypeHandler((Type) type, jdbcType);
    }

    public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
        return this.getTypeHandler(javaTypeReference.getRawType(), jdbcType);
    }

    @SuppressWarnings("unchecked")
    private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
        if (ParamMap.class.equals(type)) {
            return null;
        }
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = this.getJdbcHandlerMap(type);
        TypeHandler<?> handler = null;
        if (jdbcHandlerMap != null) {
            handler = jdbcHandlerMap.get(jdbcType);
            if (handler == null) {
                handler = jdbcHandlerMap.get(null);
            }
            if (handler == null) {
                // #591
                handler = this.pickSoleHandler(jdbcHandlerMap);
            }
        }
        // type drives generics here
        return (TypeHandler<T>) handler;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(type);
        if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
            return null;
        }
        if (jdbcHandlerMap == null && type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (Enum.class.isAssignableFrom(clazz)) {
                Class<?> enumClass = clazz.isAnonymousClass() ? clazz.getSuperclass() : clazz;
                jdbcHandlerMap = this.getJdbcHandlerMapForEnumInterfaces(enumClass, enumClass);
                if (jdbcHandlerMap == null) {
                    this.register(enumClass, this.getInstance(enumClass, defaultEnumTypeHandler));
                    return typeHandlerMap.get(enumClass);
                }
            } else {
                jdbcHandlerMap = this.getJdbcHandlerMapForSuperclass(clazz);
            }
        }
        typeHandlerMap.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
        return jdbcHandlerMap;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz, Class<?> enumClazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(iface);
            if (jdbcHandlerMap == null) {
                jdbcHandlerMap = this.getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
            }
            if (jdbcHandlerMap != null) {
                // Found a type handler regsiterd to a super interface
                HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<>();
                for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
                    // Create a type handler instance with enum type as a constructor arg
                    newMap.put(entry.getKey(), this.getInstance(enumClazz, entry.getValue().getClass()));
                }
                return newMap;
            }
        }
        return null;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
        Class<?> superclass = clazz.getSuperclass();
        if (superclass == null || Object.class.equals(superclass)) {
            return null;
        }
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(superclass);
        if (jdbcHandlerMap != null) {
            return jdbcHandlerMap;
        } else {
            return this.getJdbcHandlerMapForSuperclass(superclass);
        }
    }

    private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
        TypeHandler<?> soleHandler = null;
        for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
            if (soleHandler == null) {
                soleHandler = handler;
            } else if (!handler.getClass().equals(soleHandler.getClass())) {
                // More than one type handlers registered.
                return null;
            }
        }
        return soleHandler;
    }

    public TypeHandler<Object> getUnknownTypeHandler() {
        return unknownTypeHandler;
    }

    public void register(JdbcType jdbcType, TypeHandler<?> handler) {
        jdbcTypeHandlerMap.put(jdbcType, handler);
    }

    //
    // REGISTER INSTANCE
    //

    // Only handler

    @SuppressWarnings("unchecked")
    public <T> void register(TypeHandler<T> typeHandler) {
        boolean mappedTypeFound = false;
        // 获取 MappedTypes 注解配置
        MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
        if (mappedTypes != null) {
            // 一个 TypeHandler 可以关联多个 java 类型，遍历逐一注册
            for (Class<?> handledType : mappedTypes.value()) {
                this.register(handledType, typeHandler);
                mappedTypeFound = true;
            }
        }
        // 尝试基于 typeHandler 自动发现对应的 java 类型，需要实现 TypeReference 接口（@since 3.1.0）
        if (!mappedTypeFound && typeHandler instanceof TypeReference) {
            try {
                TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
                this.register(typeReference.getRawType(), typeHandler);
                mappedTypeFound = true;
            } catch (Throwable t) {
                // maybe users define the TypeReference with a different type and are not assignable, so just ignore it
            }
        }
        if (!mappedTypeFound) {
            this.register((Class<T>) null, typeHandler);
        }
    }

    // java type + handler

    public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
        this.register((Type) javaType, typeHandler);
    }

    private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
        // 获取 MappedJdbcTypes 注解配置
        MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);
        if (mappedJdbcTypes != null) {
            // 一个 TypeHandler 可以关联多个 JDBC 类型，遍历逐一注册
            for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
                this.register(javaType, handledJdbcType, typeHandler);
            }
            // 允许处理 null 值
            if (mappedJdbcTypes.includeNullJdbcType()) {
                this.register(javaType, null, typeHandler);
            }
        } else {
            this.register(javaType, null, typeHandler);
        }
    }

    public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
        this.register(javaTypeReference.getRawType(), handler);
    }

    // java type + jdbc type + handler

    // Cast is required here
    @SuppressWarnings("cast")
    public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
        this.register((Type) type, jdbcType, handler);
    }

    private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
        // 如果 javaType 不为空，则添加对应的类型处理器到 typeHandlerMap 集合中
        if (javaType != null) {
            Map<JdbcType, TypeHandler<?>> map = typeHandlerMap.get(javaType);
            if (map == null || map == NULL_TYPE_HANDLER_MAP) {
                map = new HashMap<>();
            }
            map.put(jdbcType, handler);
            typeHandlerMap.put(javaType, map);
        }
        // 记录所有的 TypeHandler 对象
        allTypeHandlersMap.put(handler.getClass(), handler);
    }

    //
    // REGISTER CLASS
    //

    // Only handler type

    public void register(Class<?> typeHandlerClass) {
        boolean mappedTypeFound = false;
        MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
        if (mappedTypes != null) {
            for (Class<?> javaTypeClass : mappedTypes.value()) {
                this.register(javaTypeClass, typeHandlerClass);
                mappedTypeFound = true;
            }
        }
        if (!mappedTypeFound) {
            this.register(this.getInstance(null, typeHandlerClass));
        }
    }

    // java type + handler type

    public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
        this.register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
    }

    public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
        this.register(javaTypeClass, this.getInstance(javaTypeClass, typeHandlerClass));
    }

    // java type + jdbc type + handler type

    public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
        this.register(javaTypeClass, jdbcType, this.getInstance(javaTypeClass, typeHandlerClass));
    }

    // Construct a handler (used also from Builders)

    @SuppressWarnings("unchecked")
    public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
        if (javaTypeClass != null) {
            try {
                Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
                return (TypeHandler<T>) c.newInstance(javaTypeClass);
            } catch (NoSuchMethodException ignored) {
                // ignored
            } catch (Exception e) {
                throw new TypeException("Failed invoking constructor for handler " + typeHandlerClass, e);
            }
        }
        try {
            Constructor<?> c = typeHandlerClass.getConstructor();
            return (TypeHandler<T>) c.newInstance();
        } catch (Exception e) {
            throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass, e);
        }
    }

    // scan

    public void register(String packageName) {
        ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
        resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
        Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
        for (Class<?> type : handlerSet) {
            //Ignore inner classes and interfaces (including package-info.java) and abstract classes
            if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                this.register(type);
            }
        }
    }

    // get information

    /**
     * @since 3.2.2
     */
    public Collection<TypeHandler<?>> getTypeHandlers() {
        return Collections.unmodifiableCollection(allTypeHandlersMap.values());
    }

}
