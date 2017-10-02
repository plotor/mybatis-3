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

package org.apache.ibatis.scripting.xmltags;

/**
 * 表示 SQL 语句中定义的动态 SQL 节点、文本节点等
 *
 * @author Clinton Begin
 */
public interface SqlNode {

    /**
     * 基于传递的实参，解析动态 SQL
     *
     * @param context
     * @return
     */
    boolean apply(DynamicContext context);
}
