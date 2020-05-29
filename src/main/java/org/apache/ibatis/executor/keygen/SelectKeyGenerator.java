/**
 * Copyright 2009-2019 the original author or authors.
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

package org.apache.ibatis.executor.keygen;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;

import java.sql.Statement;
import java.util.List;

/**
 * @author Clinton Begin
 * @author Jeff Butler
 */
public class SelectKeyGenerator implements KeyGenerator {

    public static final String SELECT_KEY_SUFFIX = "!selectKey";
    private final boolean executeBefore;
    private final MappedStatement keyStatement;

    public SelectKeyGenerator(MappedStatement keyStatement, boolean executeBefore) {
        this.executeBefore = executeBefore;
        this.keyStatement = keyStatement;
    }

    @Override
    public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
        if (executeBefore) {
            this.processGeneratedKeys(executor, ms, parameter);
        }
    }

    @Override
    public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
        if (!executeBefore) {
            this.processGeneratedKeys(executor, ms, parameter);
        }
    }

    private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
        try {
            if (parameter != null && keyStatement != null && keyStatement.getKeyProperties() != null) {
                // 获取 keyProperty 属性配置，用于指定生成结果所映射的目标属性，可能存在多个
                String[] keyProperties = keyStatement.getKeyProperties();
                final Configuration configuration = ms.getConfiguration();
                // 创建实参 parameter 对应的 MetaObject 对象，便于反射操作
                final MetaObject metaParam = configuration.newMetaObject(parameter);
                // 创建 SQL 执行器，并执行 <selectKey/> 中定义的 SQL 语句
                Executor keyExecutor = configuration.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
                List<Object> values = keyExecutor.query(
                    keyStatement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);

                /* 处理 <selectKey/> 的返回值，填充目标属性 */

                if (values.size() == 0) {
                    throw new ExecutorException("SelectKey returned no data.");
                } else if (values.size() > 1) {
                    throw new ExecutorException("SelectKey returned more than one value.");
                } else {
                    // 创建主键值对应的 MetaObject 对象
                    MetaObject metaResult = configuration.newMetaObject(values.get(0));
                    // 单列主键的情况
                    if (keyProperties.length == 1) {
                        if (metaResult.hasGetter(keyProperties[0])) {
                            this.setValue(metaParam, keyProperties[0], metaResult.getValue(keyProperties[0]));
                        }
                        // 没有 getter 方法，尝试直接获取属性值
                        else {
                            // no getter for the property - maybe just a single value object, so try that
                            this.setValue(metaParam, keyProperties[0], values.get(0));
                        }
                    }
                    // 多列主键的情况，依次从主键对象中获取对应的属性记录到用户参数对象中
                    else {
                        this.handleMultipleProperties(keyProperties, metaParam, metaResult);
                    }
                }
            }
        } catch (ExecutorException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecutorException("Error selecting key or setting result to parameter object. Cause: " + e, e);
        }
    }

    private void handleMultipleProperties(String[] keyProperties,
                                          MetaObject metaParam, MetaObject metaResult) {
        String[] keyColumns = keyStatement.getKeyColumns();

        if (keyColumns == null || keyColumns.length == 0) {
            // no key columns specified, just use the property names
            for (String keyProperty : keyProperties) {
                this.setValue(metaParam, keyProperty, metaResult.getValue(keyProperty));
            }
        } else {
            if (keyColumns.length != keyProperties.length) {
                throw new ExecutorException("If SelectKey has key columns, the number must match the number of key properties.");
            }
            for (int i = 0; i < keyProperties.length; i++) {
                this.setValue(metaParam, keyProperties[i], metaResult.getValue(keyColumns[i]));
            }
        }
    }

    private void setValue(MetaObject metaParam, String property, Object value) {
        if (metaParam.hasSetter(property)) {
            metaParam.setValue(property, value);
        } else {
            throw new ExecutorException("No setter found for the keyProperty '" + property + "' in " + metaParam.getOriginalObject().getClass().getName() + ".");
        }
    }
}
