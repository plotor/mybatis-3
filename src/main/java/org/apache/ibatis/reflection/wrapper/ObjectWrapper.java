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

package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * 对对象的封装和处理
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

    /**
     * 获取对应属性的值（对于集合而言，则是获取对应下标的值）
     *
     * @param prop
     * @return
     */
    Object get(PropertyTokenizer prop);

    /**
     * 设置对应属性的值（对于集合而言，则是设置对应下标的值）
     *
     * @param prop
     * @param value
     */
    void set(PropertyTokenizer prop, Object value);

    /**
     * 查找属性表达式对应的属性
     *
     * @param name
     * @param useCamelCaseMapping
     * @return
     */
    String findProperty(String name, boolean useCamelCaseMapping);

    /**
     * 获取可读属性名称集合
     *
     * @return
     */
    String[] getGetterNames();

    /**
     * 获取可写属性名称集合
     *
     * @return
     */
    String[] getSetterNames();

    /**
     * 获取属性表达式指定属性 setter 方法的入参类型
     *
     * @param name
     * @return
     */
    Class<?> getSetterType(String name);

    /**
     * 获取属性表达式指定属性 getter 方法的返回类型
     *
     * @param name
     * @return
     */
    Class<?> getGetterType(String name);

    /**
     * 判断属性是否有 setter 方法
     *
     * @param name
     * @return
     */
    boolean hasSetter(String name);

    /**
     * 判断属性是否有 getter 方法
     *
     * @param name
     * @return
     */
    boolean hasGetter(String name);

    /**
     * 为属性表达式指定的属性创建对应的 {@link MetaObject} 对象
     *
     * @param name
     * @param prop
     * @param objectFactory
     * @return
     */
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

    /**
     * 是否是 {@link java.util.Collection} 类型
     *
     * @return
     */
    boolean isCollection();

    /**
     * 调用 {@link java.util.Collection} 对应的 add 方法
     *
     * @param element
     */
    void add(Object element);

    /**
     * 调用 {@link java.util.Collection} 对应的 addAll 方法
     *
     * @param element
     * @param <E>
     */
    <E> void addAll(List<E> element);

}
