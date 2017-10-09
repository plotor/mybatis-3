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
 * 存储过程中的参数类型
 * 关于IN和OUT的区别, 个人理解 IN 类似于值传递, OUT 类似于引用传递
 *
 * @author Clinton Begin
 */
public enum ParameterMode {
    /** 输入参数. 在调用存储过程时指定, 默认未指定类型时则是此类型 */
    IN,

    /** 输出参数. 在存储过程里可以被改变, 并且可返回 */
    OUT,

    /** 输入输出参数. IN 和 OUT 结合 */
    INOUT
}
