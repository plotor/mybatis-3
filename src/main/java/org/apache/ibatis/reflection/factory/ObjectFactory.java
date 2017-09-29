/**
 * Copyright 2009-2016 the original author or authors.
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

package org.apache.ibatis.reflection.factory;

import java.util.List;
import java.util.Properties;

/**
 * MyBatis uses an ObjectFactory to create all needed new Objects.
 *
 * @author Clinton Begin
 */
public interface ObjectFactory {

    /**
     * 设置配置信息
     *
     * @param properties 配置信息对象
     */
    void setProperties(Properties properties);

    /**
     * 基于无参构造方法创建指定类型对象
     *
     * @param type 目标类型
     * @return
     */
    <T> T create(Class<T> type);

    /**
     * 基于指定的构造参数（类型）选择对应的构造方法创建目标对象
     *
     * @param type 目标类型
     * @param constructorArgTypes 构造参数类型列表
     * @param constructorArgs 构造参数列表
     * @return
     */
    <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs);

    /**
     * 检测指定类型是否是集合类型
     *
     * It's main purpose is to support non-java.util.Collection objects like Scala collections.
     *
     * @param type 目标类型
     * @return
     * @since 3.1.0
     */
    <T> boolean isCollection(Class<T> type);

}
