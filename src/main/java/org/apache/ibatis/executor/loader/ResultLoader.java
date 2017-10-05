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

package org.apache.ibatis.executor.loader;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * 主要负责保存一次延迟加载操作所需要的全部信息
 *
 * @author Clinton Begin
 */
public class ResultLoader {

    protected final Configuration configuration;

    /** SQL执行器 */
    protected final Executor executor;

    /** 封装需要延迟执行的 SQL 配置标签信息 */
    protected final MappedStatement mappedStatement;

    /** 封装需要延迟执行的 SQL 语句 */
    protected final BoundSql boundSql;

    /** 延迟执行 SQL 对应的实参 */
    protected final Object parameterObject;

    /** 延迟加载得到的对象类型 */
    protected final Class<?> targetType;

    /** 延迟加载得到的结果对象 */
    protected Object resultObject;

    /** 用于将延迟加载得到的结果对象转换成 targetType 类型的对象 */
    protected final ResultExtractor resultExtractor;

    protected final ObjectFactory objectFactory;

    protected final CacheKey cacheKey;

    protected final long creatorThreadId;

    protected boolean loaded;

    public ResultLoader(
            Configuration config, Executor executor, MappedStatement mappedStatement,
            Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
        this.configuration = config;
        this.executor = executor;
        this.mappedStatement = mappedStatement;
        this.parameterObject = parameterObject;
        this.targetType = targetType;
        this.objectFactory = configuration.getObjectFactory();
        this.cacheKey = cacheKey;
        this.boundSql = boundSql;
        this.resultExtractor = new ResultExtractor(configuration, objectFactory);
        this.creatorThreadId = Thread.currentThread().getId();
    }

    /**
     * 执行对应的 SQL 并返回对应的结果对象
     *
     * @return
     * @throws SQLException
     */
    public Object loadResult() throws SQLException {
        // 执行对应的 SQL 返回结果集合
        List<Object> list = this.selectList();
        // 将上面的返回结果转换成为 targetType 指定的结果对象
        resultObject = resultExtractor.extractObjectFromList(list, targetType);
        return resultObject;
    }

    private <E> List<E> selectList() throws SQLException {
        Executor localExecutor = executor;
        // 如果当前运行线程并不是创建 ResultLoader 对象对应的线程，或者对应的 Executor 已经被关闭
        if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
            // 创建新的 Executor 对象
            localExecutor = this.newExecutor();
        }
        try {
            // 执行数据库操作
            return localExecutor.<E>query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
        } finally {
            // 如果是当前方法中新建的 Executor，则需要关闭
            if (localExecutor != executor) {
                localExecutor.close(false);
            }
        }
    }

    private Executor newExecutor() {
        final Environment environment = configuration.getEnvironment();
        if (environment == null) {
            throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
        }
        final DataSource ds = environment.getDataSource();
        if (ds == null) {
            throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
        }
        final TransactionFactory transactionFactory = environment.getTransactionFactory();
        final Transaction tx = transactionFactory.newTransaction(ds, null, false);
        return configuration.newExecutor(tx, ExecutorType.SIMPLE);
    }

    public boolean wasNull() {
        return resultObject == null;
    }

}
