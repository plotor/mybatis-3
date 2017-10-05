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

package org.apache.ibatis.executor.statement;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * @author Clinton Begin
 */
public interface StatementHandler {

    /**
     * 获取一个 {@link Statement } 对象
     *
     * @param connection
     * @param transactionTimeout
     * @return
     * @throws SQLException
     */
    Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException;

    /**
     * 绑定 statement 执行 SQL 时需要的实参
     *
     * @param statement
     * @throws SQLException
     */
    void parameterize(Statement statement) throws SQLException;

    /**
     * 批量执行 SQL 语句
     *
     * @param statement
     * @throws SQLException
     */
    void batch(Statement statement) throws SQLException;

    /**
     * 执行数据库更新操作：insert、update、delete
     *
     * @param statement
     * @return
     * @throws SQLException
     */
    int update(Statement statement) throws SQLException;

    /**
     * 执行 select 操作
     *
     * @param statement
     * @param resultHandler
     * @param <E>
     * @return
     * @throws SQLException
     */
    <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException;

    <E> Cursor<E> queryCursor(Statement statement) throws SQLException;

    BoundSql getBoundSql();

    ParameterHandler getParameterHandler();

}
