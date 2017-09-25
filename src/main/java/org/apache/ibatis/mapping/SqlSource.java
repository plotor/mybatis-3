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

package org.apache.ibatis.mapping;

/**
 * Represents the content of a mapped statement read from an XML file or an annotation.
 * It creates the SQL that will be passed to the database out of the input parameter received from the user.
 *
 * 用于表示映射文件或注解定义的 SQL 语句，需要注意的是这里的 SQL 语句是不能被直接执行的，因为其中可能包含动态占位符
 *
 * @author Clinton Begin
 */
public interface SqlSource {

    /**
     * 获取配置的 SQL，并基于传入的参数返回可执行的 SQL
     *
     * @param parameterObject
     * @return
     */
    BoundSql getBoundSql(Object parameterObject);

}
