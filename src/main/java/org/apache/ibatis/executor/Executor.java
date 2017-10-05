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

package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * @author Clinton Begin
 */
public interface Executor {

    ResultHandler NO_RESULT_HANDLER = null;

    /**
     * 执行数据库更新操作：update、insert、delete
     *
     * @param ms
     * @param parameter
     * @return
     * @throws SQLException
     */
    int update(MappedStatement ms, Object parameter) throws SQLException;

    /**
     * 执行数据库查询操作
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param cacheKey
     * @param boundSql
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql)
            throws SQLException;

    /**
     * 执行数据库查询操作
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;

    /**
     * 执行数据库查询操作, 返回游标对象
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;

    /**
     * 批量执行 SQL 语句
     *
     * @return
     * @throws SQLException
     */
    List<BatchResult> flushStatements() throws SQLException;

    /**
     * 提交事务
     *
     * @param required
     * @throws SQLException
     */
    void commit(boolean required) throws SQLException;

    /**
     * 回滚事务
     *
     * @param required
     * @throws SQLException
     */
    void rollback(boolean required) throws SQLException;

    /**
     * 创建缓存 key
     *
     * @param ms
     * @param parameterObject
     * @param rowBounds
     * @param boundSql
     * @return
     */
    CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);

    /**
     * 判断是否缓存
     *
     * @param ms
     * @param key
     * @return
     */
    boolean isCached(MappedStatement ms, CacheKey key);

    /**
     * 清空一级缓存
     */
    void clearLocalCache();

    /**
     * 延迟加载一级缓存中的数据
     *
     * @param ms
     * @param resultObject
     * @param property
     * @param key
     * @param targetType
     */
    void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);

    /**
     * 获取事务对象
     *
     * @return
     */
    Transaction getTransaction();

    /**
     * 关闭当前 Executor
     *
     * @param forceRollback
     */
    void close(boolean forceRollback);

    /**
     * 是否已经关闭
     *
     * @return
     */
    boolean isClosed();

    /**
     * 设置装饰的 Executor 对象
     *
     * @param executor
     */
    void setExecutorWrapper(Executor executor);

}
