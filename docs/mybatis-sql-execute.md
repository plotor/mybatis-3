在前面的分析过程中，我们完成了对 MyBatis 所有配置文件（包括配置文件和映射文件）的探秘。回忆一下我们最开始给出的 MyBatis 小示例（如下），经过前面千山万水的跋涉，我们终于完成了第一行代码的 ... 99% ...（手动滑稽），这最后的 1% 就是创建 SqlSessionFactory 对象，所有的配置解析最后都会封装到 Configuration 对象中，接下去就是调用 SqlSessionFactoryBuilder 对象的 build 方法创建 SqlSessionFactory 对象，这里本质上使用的是 DefaultSqlSessionFactory 实现类进行实例化。

```java
SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(Resources.getResourceAsStream("mybatis-config.xml"));
SqlSession sqlSession = sessionFactory.openSession();
UserMapper mapper = sqlSession.getMapper(UserMapper.class);
List<User> users = mapper.selectByIds(Arrays.asList(1L, 2L));
System.out.println(users);
sqlSession.close();
```

SqlSessionFactory 是一个工厂类，用于创建 SqlSession 对象，按照官方文档的说明，SqlSessionFactory 对象一旦被创建就应该在应用的运行期间一直存在，不应该在应用运行期间对其进行清除或重建。调用该工厂的 openSession 方法可以开启一次会话，即创建一个 SqlSession 对象，SqlSession 封装了面向数据库执行 SQL 的所有方法，它不是线程安全的，因此是不能被共享的，所以该对象的最佳的作用域是请求或方法作用域。在上面的示例中，我们用 SqlSession 拿到相应的 Mapper 接口对象（更准确的说是一个动态代理对象），然后执行指定的数据库操作，最后关闭此次对话。

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis.png?raw=false)

上面的时序图更加详细的描绘了此次执行过程中类之间的调用时序关系，我们简单概括一下这个过程，知道个大概就行，后面我们再来对涉及到的类和接口在源码层面进行分析。概括如下：

> 1. 调用 SqlSessionFactory 对象的 openSession 方法创建 SqlSession 对象，开启一次会话。
> 2. 调用 SqlSession 对象的 getMapper 方法获取指定 Mapper 接口对象，这里本质上调用的是 Configuration 接口的 getMapper 方法，由前面分析映射文件解析过程时我们知道所有的 Mapper。 接口都会注册到全局唯一的配置对象 Configuration 的 MapperRegistry 类型属性中。
> 3. MapperRegistry 在执行 getMapper 操作时会反射创建 Mapper 接口的动态代理对象并返回
> 4. 执行对应的数据库操作方法（即 selectByIds 方法），即调用 Mapper 接口动态代理对象的 invoke 方法，在该方法中会获取封装执行方法的 MapperMethod 对象。
> 5. 执行 MapperMethod 对象的 execute，该方法会判定当前数据库操作类型（这里对应 SELECT 类型），依据类型来选择执行 SqlSession 相应的数据库操作方法。
> 6. SqlSession 会委托具体的 Executor 执行数据库操作，对于动态 SQL 语句，在这里会依据参数执行解析。对于查询语句来说，在条件允许的前提下 Executor 会尝试先从缓存中进行查询，缓存不命中才会操作具体的数据库，并更新缓存。MyBatis 强大的结果集映射操作也在这里完成。
> 7. 返回查询结果。
> 8. 调用当前会话对象 SqlSession 的 close 方法关闭本次会话。

上述过程主要描绘了示例程序中查询多个 ID 用户的执行过程，虽然不能覆盖 MyBatis 执行 SQL 操作的各个方面，但主线上还是能够说明白 MyBatis 针对一次 SQL 执行的大概过程，在下面的篇幅中，我们将一起探究这一整套时序背后的源码实现。

### 一. SQL 会话管理

SqlSession 接口是 MyBatis 对外提供的 API，也是 MyBatis 的核心接口之一，对于使用 MyBatis 框架的人来说对于该接口都不陌生。围绕 SqlSession 接口的类继承关系如下图，其中 DefaultSqlSession 是使用最为频繁的 SqlSession 实现。SqlSessionFactory 是一个工厂接口，其作用是用来创建 SqlSession 对象，该接口中声明了 openSession 方法的多个重载版本（比较简单，不多做撰述），DefaultSqlSessionFactory 是该接口的默认实现，上述示例程序中 SqlSessionFactoryBuilder 的 build 方法就是基于该实现类创建的 SqlSessionFactory 对象。SqlSessionManager 类实现了这两个接口，所以具备创建 SqlSession 对象，以及使用 SqlSession 对象的能力，后面再详细说明。

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis-sqlsession.png?raw=false)

SqlSession 接口中的方法定义都比较直观，且文档比较完善，感兴趣的读者可以自行查看源码。我们来看一下 DefaultSqlSession 实现类，该类的属性定义如下：

```java
/** 全局唯一的配置对象 */
private final Configuration configuration;
/** SQL 执行器 */
private final Executor executor;
/** 是否自动提交事务 */
private final boolean autoCommit;
/** 标记当前缓存中是否存在脏数据 */
private boolean dirty;
/** 记录已经打开的游标 */
private List<Cursor<?>> cursorList;
```

DefaultSqlSession 中的方法实现基本上都是对 Executor 方法的封装，实现上都比较简单，不一一分析。这里解释一下 cursorList 这个属性，在 selectCursor 方法中会记录查询返回的游标（Cursor）对象，并在关闭 SqlSession 会话时遍历集合逐一关闭，从而防止打开的游标没有被关闭的现象。

DefaultSqlSessionFactory 是 SqlSessionFactory 接口的默认实现，用于创建 SqlSession 对象，该实现类提供了两种创建 SqlSession 对象的方式，分别是基于当前数据源创建会话和基于当前数据库连接创建会话，对应的实现如下：

```java
/** 基于数据源配置创建对应的 {@link SqlSession} 对象 */
private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level, boolean autoCommit) {
    Transaction tx = null;
    try {
        // 获取当前数据库环境
        final Environment environment = configuration.getEnvironment();
        // 获取当前数据库环境对应的 TransactionFactory 对象，不存在的话就创建一个
        final TransactionFactory transactionFactory = this.getTransactionFactoryFromEnvironment(environment);
        tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
        // 依据指定的 Executor 类型创建对应的 Executor 对象
        final Executor executor = configuration.newExecutor(tx, execType);
        // 创建 SqlSession 对象
        return new DefaultSqlSession(configuration, executor, autoCommit);
    } catch (Exception e) {
        this.closeTransaction(tx); // may have fetched a connection so lets call close()
        throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
    } finally {
        ErrorContext.instance().reset();
    }
}

/** 基于数据库连接创建 {@link SqlSession} 对象 */
private SqlSession openSessionFromConnection(ExecutorType execType, Connection connection) {
    try {
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
        } catch (SQLException e) {
            // 考虑到很多驱动或者数据库不支持事务，设置自动提交事务
            autoCommit = true;
        }
        // 获取当前数据库环境
        final Environment environment = configuration.getEnvironment();
        // 获取当前数据库环境对应的 TransactionFactory 对象，不存在的话就创建一个
        final TransactionFactory transactionFactory = this.getTransactionFactoryFromEnvironment(environment);
        final Transaction tx = transactionFactory.newTransaction(connection);
        // 依据指定的 Executor 类型创建对应的 Executor 对象
        final Executor executor = configuration.newExecutor(tx, execType);
        // 创建 SqlSession 对象
        return new DefaultSqlSession(configuration, executor, autoCommit);
    } catch (Exception e) {
        throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
    } finally {
        ErrorContext.instance().reset();
    }
}
```

两种创建会话的方式在实现模板上基本一致，具体细节参阅上述代码注释。

SqlSessionManager 同时实现了 SqlSessionFactory 和 SqlSession 两个接口，所以具备这两个接口全部的功能，该实现类的属性定义如下：

```java
/** 封装的 {@link SqlSessionFactory} 对象 */
private final SqlSessionFactory sqlSessionFactory;
/** 线程私有的 SqlSession 对象的动态代理对象 */
private final SqlSession sqlSessionProxy;
/** 线程私有的 SqlSession 对象 */
private final ThreadLocal<SqlSession> localSqlSession = new ThreadLocal<SqlSession>();
```

针对 SqlSessionFactory 接口中定义的功能，均通过包装的 SqlSessionFactory 实现类来完成。对于 SqlSession 接口中定义的功能来说，SqlSessionManager 提供了两种实现方式，如果当前线程已经绑定了一个 SqlSession 对象，那么只要未主动调用 SqlSessionManager 对象的 close 方法，就会一直该线程私有的 SqlSession 对象，否则会在每次执行数据库操作时创建一个新的 SqlSession 对象，并在使用完毕之后关闭会话，相关逻辑位于 SqlSessionInterceptor 类中，这是一个定义在 SqlSessionManager 中的内部类，sqlSessionProxy 属性是基于该类实现的动态代理对象：

```java
this.sqlSessionProxy = (SqlSession) Proxy.newProxyInstance(
            SqlSessionFactory.class.getClassLoader(), new Class[] {SqlSession.class}, new SqlSessionInterceptor());
```

SqlSessionInterceptor 类实现如下：

```java
private class SqlSessionInterceptor implements InvocationHandler {
    
    public SqlSessionInterceptor() {}
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 获取当前线程私有的 SqlSession 对象
        final SqlSession sqlSession = SqlSessionManager.this.localSqlSession.get();
        if (sqlSession != null) {
            try {
                // 直接反射调用相应的方法
                return method.invoke(sqlSession, args);
            } catch (Throwable t) {
                throw ExceptionUtil.unwrapThrowable(t);
            }
        } else {
            // 当前线程没有私有的 SqlSession，创建一个
            final SqlSession autoSqlSession = openSession();
            try {
                // 反射调用相应的方法
                final Object result = method.invoke(autoSqlSession, args);
                // 提交事务
                autoSqlSession.commit();
                return result;
            } catch (Throwable t) {
                autoSqlSession.rollback();
                throw ExceptionUtil.unwrapThrowable(t);
            } finally {
                // 使用完毕之后即关闭会话
                autoSqlSession.close();
            }
        }
    }
}
```

可以看到实现上首先会尝试获取线程私有的 SqlSession 对象，对于未绑定的线程来说会创建一个新的 SqlSession 对象，并在使用完毕之后立刻关闭。

### 二. Mapper 接口动态代理

### 三. SQL 执行器

Executor 接口定义了基本的数据库操作，前面在介绍 SqlSession 时曾描述 SqlSession 为 MyBatis 对外提供的 API 接口，其中声明了对数据库的基本操作方法，这些操作方法基本上都是对 Executor 操作方法的封装，该接口的定义如下：

```java
public interface Executor {

    ResultHandler NO_RESULT_HANDLER = null;
    
    /** 执行数据库更新操作：update、insert、delete */
    int update(MappedStatement ms, Object parameter) throws SQLException;
    /** 执行数据库查询操作 */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;
    /** 执行数据库查询操作 */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;
    /** 执行数据库查询操作, 返回游标对象 */
    <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;
    /** 批量执行 SQL 语句 */
    List<BatchResult> flushStatements() throws SQLException;
    /** 提交事务 */
    void commit(boolean required) throws SQLException;
    /** 回滚事务 */
    void rollback(boolean required) throws SQLException;
    /** 创建缓存 key 对象 */
    CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);
    /** 判断是否缓存 */
    boolean isCached(MappedStatement ms, CacheKey key);
    /** 清空一级缓存 */
    void clearLocalCache();
    /** 延迟加载一级缓存中的数据 */
    void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);
    /** 获取事务对象 */
    Transaction getTransaction();
    /** 关闭当前 Executor */
    void close(boolean forceRollback);
    /** 是否已经关闭 */
    boolean isClosed();
    /** 设置装饰的 Executor 对象 */
    void setExecutorWrapper(Executor executor);
}
```

围绕 Executor 的类继承关系如下图，其中 CachingExecutor 实现类用于为 Executor 提供二级缓存支持。BaseExecutor 抽象类实现了 Executor 接口中声明的所有方法，并抽象了 4 个模板方法交由子类实现，这 4 个方法分别是：doUpdate、doFlushStatements、doQuery，以及 doQueryCursor。SimpleExecutor 继承了 BaseExecutor 抽象类，并为这 4 个模板方法提供了最简单的实现。ReuseExecutor 如其名，提供了重用的特性，用于对 Statement 对象进行重用，以减少 SQL 预编译以及创建和销毁 Statement 对象的开销。BatchExecutor 实现类则提供了对 SQL 语句批量执行的功能，也是针对提升性能的一种策略。

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis-executor.png?raw=false)

#### 3.1 缓存结构设计

考虑到 Executor 在执行数据库操作时与缓存存在密切关系，所以在具体介绍 Executor 的实现之前我们先来了解一下 MyBatis 的缓存结构设计。我们在谈论数据库架构设计时，往往需要引入缓存的概念，数据库是相对脆弱且缓慢的，所以我们需要避免请求尽量落库。在实际项目架构设计中，我们一般会引入 Redis、Memcached 等组件来对数据进行缓存，MyBatis 作为一个强大的 ORM 框架，也为缓存提供了具体的实现，前面我们在分析配置文件加载过程时曾分析过 MyBatis 缓存组件的具体实现，MyBatis 在数据存储上采用 HashMap 作为基本存储结构，并提供了多种装饰器从多个侧面为缓存增加相应的特性。

在本小节中，我们关注的是 MyBatis 在缓存结构方法的设计，MyBatis 缓存从结构可以分为 __一级缓存__ 和 __二级缓存__，一级缓存相对于二级缓存在粒度上更细，生命周期也更短。

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis-cache-construction.png?raw=false)


上图简单描绘了 MyBatis 缓存的结构设计，当我们发起一次数据库查询时，如果启用了二级缓存的话，MyBatis 首先会从二级缓存中检索查询结果，如果缓存不命中则会继续检索一级缓存，只有在这两层缓存都不命中的情况下才会查询数据库，最后会以数据库返回的结果更新一级缓存和二级缓存。

MyBatis 的一级缓存是会话级别的缓存（生命周期与本次会话相同），当我们开启一次会话时，框架会默认为本次会话绑定一个缓存对象，此类缓存主要应对在一个会话范围内的冗余查询操作，比如使用同一个 SqlSession 对象同时连续执行多次相同的查询语句，这种情况下每次查询都落库是没有必要的，因为短时间内数据库变化的可能性会很小，但是每次都落库却是一笔不必要的开销。一级缓存默认是开启的，且无需进行配置，即一级缓存对开发者是透明的，如果确实希望干预一级缓存的内在运行逻辑，可以借助于插件来实现。

对于二级缓存来说，默认也是开启的，MyBatis 提供了相应的治理选项（参考官方文档），二级缓存是应有级别的缓存，随着服务的启动而存在，并随着服务的关闭消亡，前面我们在分析 <cache/> 和 <cache-ref/> 标签时知道一个二级缓存绑定一个 namespace，并且一个引用已定义 namespace 的缓存，即多个 namespace 可以共享同一个缓存。

本小节从缓存的整体结构上进行说明，目的在于对 MyBatis 的缓存有一个整体感知，关于一级缓存和二级缓存的具体实现，留到下面介绍 Executor 具体实现时穿插说明。

#### 3.2 StatementHandler 接口定义及其实现

StatementHandler 接口及其实现类是 Executor 实现的基础，可以将其看作是 MyBatis 与数据库操作之间的纽带，实现了 `java.sql.Statement` 对象的获取，SQL 参数绑定与执行的逻辑，其中 BaseStatementHandler 中实现了一些公共的逻辑，而 SimpleStatementHandler、PreparedStatementHandler，以及 CallableStatementHandler 实现类分别对应 Statement、PreparedStatement、CallableStatement 的相关实现，RoutingStatementHandler 并没有添加新的实现，而是对前面三种 StatementHandler 实现类的封装，它会在构造方法中依据当前的 Statement 类型创建对应的 StatementHandler 实现类对象。下图描述了 StatementHandler 接口及其实现类的类继承关系：

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/img/2017/mybatis-statementhandler.png?raw=false)

StatementHandler 接口定义如下：

```java
public interface StatementHandler {

    /** 获取对应的 {@link Statement } 对象 */
    Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException;
    /** 绑定 Statement 执行 SQL 时需要的实参 */
    void parameterize(Statement statement) throws SQLException;
    /** 批量执行 SQL 语句 */
    void batch(Statement statement) throws SQLException;
    /** 执行数据库更新操作：insert、update、delete */
    int update(Statement statement) throws SQLException;
    /** 执行 select 操作 */
    <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException;
    /** 执行 select 操作，返回游标对象 */
    <E> Cursor<E> queryCursor(Statement statement) throws SQLException;
    /** 获取对应的 SQL 对象 */
    BoundSql getBoundSql();
    /** 获取对应的 {@link ParameterHandler} 对象，用于参数绑定 */
    ParameterHandler getParameterHandler();
}
```

首先来看一下 BaseStatementHandler 实现，该类中主要实现了获取 Statement 对象的逻辑，该类的属性定义如下：

```java
protected final Configuration configuration;
protected final ObjectFactory objectFactory;
protected final TypeHandlerRegistry typeHandlerRegistry;

/** 处理结果集映射 */
protected final ResultSetHandler resultSetHandler;
/** 用于为 SQL 语句绑定实参 */
protected final ParameterHandler parameterHandler;
/** SQL 语句执行器 */
protected final Executor executor;
/** 对应 SQL 语句标签对象 */
protected final MappedStatement mappedStatement;
/** 可执行的 SQL 语句 */
protected BoundSql boundSql;

protected final RowBounds rowBounds;
```

该实现类对于 getBoundSql 方法和 getParameterHandler 方法的实现都是返回相应的对象，我们来看一下 prepare 方法的实现：

```java
public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
        // 模板方法，从数据库连接中获取 Statement 对象
        statement = this.instantiateStatement(connection);
        // 设置超时时间
        this.setStatementTimeout(statement, transactionTimeout);
        // 设置 fetchSize
        this.setFetchSize(statement);
        return statement;
    } catch (SQLException e) {
        // 关闭 Statement
        this.closeStatement(statement);
        throw e;
    } catch (Exception e) {
        // 关闭 Statement
        this.closeStatement(statement);
        throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
}
```

上述方法首先会调用 instantiateStatement 方法获取一个 Statement 对象，这是一个模板方法交由子类实现，然后对拿到的 Statement 对象设置超时时间和 fetchSize 属性。

BaseStatementHandler 中封装了 ParameterHandler 接口类型的属性，接下来对这个类及其实现做进一步说明。ParameterHandler 主要用于为包含 “?” 占位符的 SQL 语句绑定实参，接口定义如下：

```java
public interface ParameterHandler {

    /** 获取输出类型参数 */
    Object getParameterObject();
    /** 为 SQL 语句绑定实参*/
    void setParameters(PreparedStatement ps) throws SQLException;
}
```

getParameterObject 方法与存储过程相关，这里我们主要分析一下 setParameters 方法的实现，该方法用来为 SQL 绑定实参，具体操作等同于我们在直接使用 PreparedStatement 时往该类型对象中注入相应类型的参数来填充 SQL 语句。DefaultParameterHandler 是目前该接口的唯一实现，其 setParameters 实现如下，整体上就是获取 BoundSql 对象记录的参数名称与 SQL 中参数的映射关系，然后获取参数名称对应的用户传递的实参设置到 PreparedStatement 对象中，如果使用过原生 JDBC 操作过数据库，对这一过程应该不难理解。

```java
public void setParameters(PreparedStatement ps) {
    ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
    // 获取 BoundSql 中记录的参数映射关系列表
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    if (parameterMappings != null) {
        // 遍历处理，为 SQL 语句绑定对应的参数值
        for (int i = 0; i < parameterMappings.size(); i++) {
            ParameterMapping parameterMapping = parameterMappings.get(i);
            // 忽略存储过程中的输出参数
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                // 记录对应的参数值
                Object value;
                // 获取参数名称
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    // 获取对应的参数值
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    // 用户未传递实参
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    // 实参类型存在类型处理器，直接转换成对应的目标值
                    value = parameterObject;
                } else {
                    // 获取实参对象中对应的参数值
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                TypeHandler typeHandler = parameterMapping.getTypeHandler();
                JdbcType jdbcType = parameterMapping.getJdbcType();
                if (value == null && jdbcType == null) {
                    jdbcType = configuration.getJdbcTypeForNull();
                }
                try {
                    // 为 SQL 语句绑定对应的实参到 PreparedStatement 对象中
                    typeHandler.setParameter(ps, i + 1, value, jdbcType);
                } catch (TypeException e) {
                    throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                } catch (SQLException e) {
                    throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                }
            }
        }
    }
}
```

关于其余几个实现类的实现都比较简单，就不再多做撰述。

#### 3.3 结果集映射

结果集映射是 MyBatis 提供的一个强大且易用的特性，标签 <resultMap/> 的用于配置数据库返回的结果集与 java bean 属性之间的映射关系，前面我们分析了该标签的解析过程，本小节我们一起来探究一下 MyBatis 如何基于这些配置执行结果集映射。

Executor 在调用具体的 StatementHandler 执行数据库查询操作时会针对数据库返回的结果集调用 ResultSetHandler 的相应方法执行结果集到结果对象的映射处理，例如下面的代码块是 PreparedStatementHandler 在执行 query 时的具体逻辑：

```java
// org.apache.ibatis.executor.statement.PreparedStatementHandler#query
public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    // 执行数据库操作
    ps.execute();
    // 调用 ResultSetHandler#handleResultSets 执行结果集映射
    return resultSetHandler.<E>handleResultSets(ps);
}
```

ResultSetHandler 接口定义了结果集映射所需要的方法，具体如下：

```java
public interface ResultSetHandler {

    /** 处理结果集，返回结果对象集合 */
    <E> List<E> handleResultSets(Statement stmt) throws SQLException;
    /** 处理结果集，返回对应的游标对象 */
    <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException;
    /** 处理存储过程中的输出类型参数 */
    void handleOutputParameters(CallableStatement cs) throws SQLException;
}
```

DefaultResultSetHandler 是目前 ResultSetHandler 接口的唯一实现，该实现类的属性定义如下：

```java
// TODO 此处添加属性定义
```

MyBatis 为结果集映射提供了灵活的配置，灵活的背后是强（复）大（杂）的映射解析过程，尤其是对于嵌套映射配置的情况，本小节力图对整个映射过程做一个比较详细的介绍，不过还是建议读者自己亲自 debug 跟踪整个执行过程。接下来我们基于普通数据库查询操作结果集映射处理方法 handleResultSets 进行分析，该方法的实现如下：

```java
public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    /* 1. 处理普通映射情况 */

    // 用于记录结果集映射的结果对象集合
    final List<Object> multipleResults = new ArrayList<Object>();
    int resultSetCount = 0;
    // 获取第一个结果集
    ResultSetWrapper rsw = this.getFirstResultSet(stmt);
    // 获取之前解析得到的封装结果集映射配置的 ResultMap 对象集合
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    // 验证，如果结果集不为 null，则 resultMaps 也不能为空
    this.validateResultMapsCount(rsw, resultMapCount);
    // 遍历处理所有的结果集，基于结果集映射规则进行映射，并将结果记录到 multipleResults 集合中
    while (rsw != null && resultMapCount > resultSetCount) {
        // 遍历获取一个配置的结果集映射对象 <resultMap/>
        ResultMap resultMap = resultMaps.get(resultSetCount);
        // 依据结果集映射配置对结果集对象进行解析，并记录到 multipleResults 集合中
        this.handleResultSet(rsw, resultMap, multipleResults, null);
        // 获取下一个结果集
        rsw = this.getNextResultSet(stmt);
        // 清空 nestedResultObjects
        this.cleanUpAfterHandlingResultSet();
        resultSetCount++;
    }

    /*
     * 2. 处理多结果集的情况
     * 常见于存储过程，存在 <select resultSets="aaa,bbb"/> 类似的配置
     * 针对过程 1 未执行映射的结果集进行映射
     */
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
        while (rsw != null && resultSetCount < resultSets.length) {
            // 获取 resultSet 配置名称对应的 ResultMapping 配置
            ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
            if (parentMapping != null) {
                // 获取对应的 <resultMap/> 配置
                String nestedResultMapId = parentMapping.getNestedResultMapId();
                ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                // 执行结果集映射
                this.handleResultSet(rsw, resultMap, null, parentMapping);
            }
            // 获取下一个结果集
            rsw = this.getNextResultSet(stmt);
            // 清空 nestedResultObjects
            this.cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }
    }

    // multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults
    return this.collapseSingleResultList(multipleResults);
}
```

handleResultSets 方法的执行过程可以分为两大块执行，其中第一大块可以视为普通的结果映射处理，第二大块则是针对多结果集映射处理，多结果集映射一般用于存储过程，这是一个小众化的需求，所以大部分时候该方法仅执行第一部分的逻辑，这一部分的执行过程如代码注释，其核心在于 handleResultSet 方法，该方法在第二部分中也会被调用，后面会针对该方法进行专门说明。下面就第二部分的触发机制举例说明，能够执行到这里一般都伴随着存储过程，这里以 MySQL 数据库为例创建一个可以返回多结果集的存储过程，其中 t_blog 表和 t_post 表的定义参考官方文档示例：

```sql
CREATE PROCEDURE usp_demo(IN ID INT)
    BEGIN
        SELECT * FROM t_blog WHERE id = ID;
        SELECT * FROM t_post WHERE id = ID;
    END;
```

对应的映射配置如下：

```xml
<resultMap id="usp_demo_result_map" type="org.zhenchao.mybatis.entity.Blog">
    <constructor>
        <idArg column="id" javaType="int"/>
    </constructor>
    <result property="title" column="title"/>
    <collection property="posts" ofType="org.zhenchao.mybatis.entity.Post" resultSet="posts">
        <id property="id" column="id"/>
        <result property="subject" column="subject"/>
    </collection>
</resultMap>

<select id="uspDemo" resultSets="blogs,posts" resultMap="usp_demo_result_map" statementType="CALLABLE">
    {CALL usp_demo(#{id, jdbcType=INTEGER, mode=IN})}
</select>
```

上述配置中，我们基于 resultSets 属性分别为对应的结果集命名，在执行该存储过程时会先映射 t_blog 对应的结果集，映射的过程中遇到名为 posts 的结果集，这个时候 MyBatis 不会转去解析该结果集，而是会将该结果集记录到 `DefaultResultSetHandler#nextResultMaps` 属性中，等到代码运行到第二部分时再对这些未解析的结果集统一进行映射。

上述过程中处理结果集映射的核心逻辑都位于 handleResultSet 方法中，该方法主要执行的逻辑在于判断当前是否指定了结果集处理器（即前面介绍过的 ResultHandler），如果没有指定则会创建一个默认的结果集处理器（默认采用 DefaultResultHandler 实现），然后调用 handleRowValues 方法执行映射逻辑，handleResultSet 方法的实现如下：

```java
private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping)
        throws SQLException {
    try {
        if (parentMapping != null) {
            // 处理多结果集嵌套映射的情况
            this.handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
        } else {
            if (resultHandler == null) {
                // 未指定 ResultHandler，构造默认的处理器
                DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                // 对结果集进行映射，并将映射结果记录到 DefaultResultHandler 对象中
                this.handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                // 获取保存在 DefaultResultHandler 对象中映射结果，记录到 multipleResults 中
                multipleResults.add(defaultResultHandler.getResultList());
            } else {
                // 用户指定了 ResultHandler，使用指定的处理器进行处理
                this.handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
            }
        }
    } finally {
        // 关闭结果集
        this.closeResultSet(rsw.getResultSet());
    }
}
```

handleRowValues 方法会判断当前映射配置中是否存在嵌套映射的情况，如果存在嵌套则执行方法 handleRowValuesForNestedResultMap 处理嵌套结果集映射，否则执行 handleRowValuesForSimpleResultMap 方法，处理简单的结果集映射，下面就这两种情况分别进行分析。

##### 3.3.1 简单结果集映射

handleRowValuesForSimpleResultMap 方法中实现了对简单（相对于嵌套而言）结果集映射的处理逻辑，方法首先会基于 RowBounds 设置定位具体的处理行，MyBatis 对于 LIMIT 分页的处理是逻辑分页，而不是物理分页，即将符合条件的记录全部载入内存，然后在内存中进行截取，如果希望执行物理分页，可以自己编码插件，或者使用第三方插件，然后会遍历结果集中目标记录行对其逐一映射，handleRowValuesForSimpleResultMap 方法的实现如下：

```java
private void handleRowValuesForSimpleResultMap(
        ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
        throws SQLException {
    DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
    // 针对设置了 RowBounds 定位指定的记录行
    this.skipRows(rsw.getResultSet(), rowBounds);
    // 检测是否可以继续对后续的记录行进行映射操作，可以的话就一直循环
    while (this.shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
        // 确定具体使用的映射配置，如果配置了 <discriminator/> 则获取最终引用的 ResultMap，否则使用当前 ResultMap
        ResultMap discriminatedResultMap = this.resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
        // 基于映射配置对当前记录行进行解析
        Object rowValue = this.getRowValue(rsw, discriminatedResultMap);
        // 保存映射得到的结果对象
        this.storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
    }
}
```

针对记录行的映射处理，方法首先会获取记录行对应的真正 ResultMap 映射配置对象，因为可能存在配置了 <discriminator/> 标签执行条件映射的情况，如果没有配置该标签则会使用当前实参对应的 ResultMap 对象。<discriminator/> 标签的处理过程位于 resolveDiscriminatedResultMap 方法中，对照配置应该比较容易理解，不再展开。获取到 ResultMap 映射配置对象之后，下一步就可以调用 getRowValue 方法对当前记录行执行映射处理，该方法的实现如下：

```java
private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    // 创建记录行映射结果对象
    Object rowValue = this.createResultObject(rsw, resultMap, lazyLoader, null);
    // 如果结果对象不为 null，且没有对应的类型处理器
    if (rowValue != null && !this.hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        // 创建结果对象的 MetaObject 对象
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings; // 标记是否成功映射任何一个属性
        // 是否需要自动映射
        if (this.shouldApplyAutomaticMappings(resultMap, false)) {
            // 自动映射未在 <resultMap/> 中指定的映射列
            foundValues = this.applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
        }
        // 映射在 <resultMap/> 中指定的映射列
        foundValues = this.applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = (foundValues || configuration.isReturnInstanceForEmptyRow()) ? rowValue : null;
    }
    return rowValue;
}
```

方法首先会调用 createResultObject 方法创建结果对象，然后为该对象执行属性映射注入，对于未配置映射关系的属性，方法会基于配置决定是否执行自动映射，对于明确指定映射关系的属性，则会调用 applyPropertyMappings 方法执行映射处理，该方法的具体实现如下：

```java
private boolean applyPropertyMappings(
        ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
    // 获取所有指明了映射关系的列名集合
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    // 获取当前 ResultMap 包含的所有映射关系配置对象 ResultMapping
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    // 遍历处理映射关系 ResultMapping 集合
    for (ResultMapping propertyMapping : propertyMappings) {
        // 处理列前缀
        String column = this.prependPrefix(propertyMapping.getColumn(), columnPrefix);
        if (propertyMapping.getNestedResultMapId() != null) {
            // 忽略嵌套的 ResultMap 映射
            column = null;
        }
        // 嵌套查询 || 配置了映射关系 || 多结果集
        if (propertyMapping.isCompositeResult()
                || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
                || propertyMapping.getResultSet() != null) { // 存在多结果集
            // 执行映射，返回属性值
            Object value = this.getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
            final String property = propertyMapping.getProperty();
            if (property == null) {
                continue;
            } else if (value == DEFERED) {
                // 延迟加载的情况
                foundValues = true;
                continue;
            }
            if (value != null) {
                foundValues = true;
            }
            if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
                // 设置属性值
                metaObject.setValue(property, value);
            }
        }
    }
    return foundValues;
}
```

方法会获取当前结果集对应的映射关系配置和列名集合，然后遍历映射配置，针对嵌套查询、多结果集映射，以及普通映射的情况分别进行处理，这一过程位于 getPropertyMappingValue 方法中，针对嵌套查询的情况稍后专门进行分析，对于多结果集的情况会将对应的结果集配置对象记录到 nextResultMaps 属性中，后面会专门处理（即前面的第二部分代码），针对普通的映射则会基于 TypeHandler 获取属性对应的 java 类型值。也就是我们期望的值，getPropertyMappingValue 方法的实现如下：

```java
private Object getPropertyMappingValue(
        ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
    if (propertyMapping.getNestedQueryId() != null) {
        // 嵌套查询
        return this.getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
        // 多结果集情况，记录对应的 resultSet，后续处理
        this.addPendingChildRelation(rs, metaResultObject, propertyMapping);
        return DEFERED;
    } else {
        // 基于 TypeHandler 获取属性值
        final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
        final String column = this.prependPrefix(propertyMapping.getColumn(), columnPrefix);
        return typeHandler.getResult(rs, column);
    }
}
```

最后会调用 storeObject 方法将结果对象记录到 `DefaultResultHandler#list` 属性中，并在 handleResultSet 方法中调用 `DefaultResultHandler#getResultList` 方法拿到这些结果对象。

接下来探究一下嵌套查询的处理过程，一个属性的值可以直接从结果集中映射，也可以由一个具体的 SQL 语句结果对象进行赋值，对于这种嵌套查询的情况由 getNestedQueryMappingValue 方法专门进行处理。嵌套查询往往与延迟加载捆绑在一起，这主要是为了性能的考虑，一个 SQL 语句操作在某些情况下可能是非常昂贵，但不一定是必要的。getNestedQueryMappingValue 方法的实现如下：

```java
private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    final String property = propertyMapping.getProperty();
    // 获取嵌套查询 SQL 对应的 MappedStatement 对象
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // 获取传递给嵌套查询的参数类型和参数值
    final Object nestedQueryParameterObject = this.prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
        final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
        final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
        // 获取嵌套 SQL 结果类型
        final Class<?> targetType = propertyMapping.getJavaType();
        if (executor.isCached(nestedQuery, key)) {
            // 如果 SQL 在缓存中有对应的结果值，则从缓存中加载结果对象
            executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
            value = DEFERED; // 返回 DEFERED 对象标识
        } else {
            // 缓存不命中，即对应的 SQL 没有对应的缓存结果对象
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            if (propertyMapping.isLazy()) {
                // 开启了延迟加载，则记录 resultLoader 到 ResultLoaderMap 中，需要的时候再执行
                lazyLoader.addLoader(property, metaResultObject, resultLoader);
                value = DEFERED;
            } else {
                // 没有开启延迟记载，直接执行
                value = resultLoader.loadResult();
            }
        }
    }
    return value;
}
```

方法首先会获取嵌套 SQL 对应的 MappedStatement 对象，并获取传递给嵌套 SQL 的参数值，后续的处理会尝试先从缓存中获取该 SQL 对应的结果对象，如果缓存命中的话则会注入该属性，如果缓存不命中则会基于是否启用延迟加载的配置来决定是否立即执行当前的 SQL 语句，如果允许延迟加载，则会记录封装该 SQL 及其执行条件的 ResultLoader 对象到 ResultLoaderMap 类型参数中，在 `DefaultResultSetHandler#createResultObject` 方法中会为该参数对象基于动态代理机制（这里的动态代理默认采用 javassist 实现，也可以指定采用 CGLib 实现）创建对应的动态代理对象，并在需要的地方直接执行 SQL 语句返回对应的结果对象。

##### 3.3.2 嵌套结果集映射

#### 3.4 Executor 的具体实现

- __BaseExecutor__

BaseExecutor 是一个抽象类，实现了 Executor 接口中声明的所有方法，并采用模板方法模式抽象出 4 个模板方法交由子类实现。__需要强调的一点是，BaseExecutor 抽象类引入了一级缓存支持，在相应方法实现中增加了对一级缓存的操作，因此该类的所有派生类都具备一级缓存的特性__。该抽象类的属性定义如下：

```java
/** 事务对象 */
protected Transaction transaction;
/** 封装的执行器 */
protected Executor wrapper;
/** 延迟加载队列 */
protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
/** 缓存结果对象（一级缓存） */
protected PerpetualCache localCache;
/** 缓存存储过程输出类型参数（一级缓存） */
protected PerpetualCache localOutputParameterCache;
/** 全局唯一的配置对象 */
protected Configuration configuration;
/** 记录嵌套查询的层数 */
protected int queryStack;
/** 标记当前 Executor 是否已经关闭 */
private boolean closed;
```

下面针对一些比较复杂的方法实现逐一说明，剩余的方法在实现上都比较简单，相信读者自行阅读源码就可以看懂。首先我们来看一下 update 方法的实现，需要注意的是这里的 update 并不等同于 SQL 的 UPDATE 操作，对于 Executor 而言数据库操作只包含 query 和 update 两大类，这里的 query 可以理解为 SQL 的 SELECT 操作，而 update 则对应着 INSERT、UPDATE、DELECT 三类操作。

```java
public int update(MappedStatement ms, Object parameter) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    if (closed) {
        throw new ExecutorException("Executor was closed.");
    }
    // 先清空一级缓存
    this.clearLocalCache();
    // 模板方法
    return this.doUpdate(ms, parameter);
}
```

update 方法的实现如上述所示，方法首先会判定当前 Executor 是否已被关闭，对于没有关闭的 Executor 会首先清空一级缓存，然后调用 doUpdate 模板方法，该方法由子类实现。

再来看一下 query 方法，query 方法用于执行数据库查询操作，因为引入了一级缓存，所以这里的查询不是简单的直接查询数据库，而是会先查询一级缓存，在缓存不命中的情况下才会查询数据库，并利用数据库返回的结果对象更新一级缓存，该方法的实现如下：

```java
public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    // 获取执行的 SQL
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 创建缓存 key
    CacheKey key = this.createCacheKey(ms, parameter, rowBounds, boundSql);
    // 调用重载的 query 方法
    return this.query(ms, parameter, rowBounds, resultHandler, key, boundSql);
}

public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
        throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    if (closed) {
        throw new ExecutorException("Executor was closed.");
    }
    // 如果是非嵌套查询，且配置 <select flushCache="true"/> 要求执行该语句时清空一级缓存和二级缓存
    if (queryStack == 0 && ms.isFlushCacheRequired()) { // queryStack 用于记录嵌套的层数
        this.clearLocalCache();
    }
    List<E> list;
    try {
        queryStack++;
        // 从一级缓存中查询
        list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
        if (list != null) {
            // 一级缓存名命中，针对存储过程特殊处理，获取缓存中保存的输出类型参数记录到实参对象中
            this.handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
        } else {
            // 缓存不命中，查数据库并更新缓存，本质上调用的是 doQuery 方法
            list = this.queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
        }
    } finally {
        queryStack--;
    }
    // 加载以及缓存中记录的嵌套查询的结果对象
    if (queryStack == 0) {
        // 延迟加载
        for (DeferredLoad deferredLoad : deferredLoads) {
            deferredLoad.load();
        }
        deferredLoads.clear();
        // 如果当前配置本地缓存机制是 STATEMENT
        if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
            this.clearLocalCache();
        }
    }
    // 返回查询获取到的结果
    return list;
}
```

整个 query 方法的执行过程如代码注释，这里针对方法最后涉及到的延迟加载相关逻辑进行进一步说明。

// TODO 这里解释说明一下延迟加载

queryCursor 与 query 都是提供数据库查询操作，区别在于前者返回的是一个游标（Cursor）对象，而 query 返回的是已经完成结果集映射的结果对象（数据库查询返回的是一个结果集，这与我们需要的 java bean 之间还差一个映射的过程，这个过程称之为结果集映射，下一节会专门讲解），而游标需要等待用户真正操作其对象时才会执行结果集映射的过程。下面的代码块是 queryCursor 方法的实现，这里并没有引入一级缓存的支持。

```java
public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    // 获取执行的 SQL
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 执行模板方法
    return this.doQueryCursor(ms, parameter, rowBounds, boundSql);
}
```

最后再来看一下几个事务相关的方法，在事务的提交和回滚操作之前都需要清空一级缓存，另外在提交操作之前还会执行当前缓存待执行的 SQL 语句，而回滚操作之前则会选择丢弃这些待执行的 SQL 语句，具体的实现和注释如下：

```java
// 提交事务
public void commit(boolean required) throws SQLException {
    if (closed) {
        throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    // 清空一级缓存
    this.clearLocalCache();
    // 执行缓存的 SQL
    this.flushStatements();
    if (required) {
        // 提交事务
        transaction.commit();
    }
}

// 回滚事务
public void rollback(boolean required) throws SQLException {
    if (!closed) {
        try {
            // 清空一级缓存
            this.clearLocalCache();
            // 丢弃缓存的 SQL
            this.flushStatements(true);
        } finally {
            if (required) {
                // 回滚事务
                transaction.rollback();
            }
        }
    }
}


public List<BatchResult> flushStatements() throws SQLException {
    return this.flushStatements(false);
}

/**
 * @param isRollBack 是否回滚，即不执行当前缓存的 SQL 语句，true 表示不执行
 */
public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    if (closed) {
        throw new ExecutorException("Executor was closed.");
    }
    // 调用模板方法
    return this.doFlushStatements(isRollBack);
}
```

- __SimpleExecutor__

SimpleExecutor 提供了对 Executor 的简单实现，针对每一次数据库操作都会创建一个新的 Statement 对象，并在操作完毕之后进行关闭。SimpleExecutor 各个方法执行的流程都相同，这里我们选择 doQuery 方法为例来一起看一下，该方法的实现如下：

```java
public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {
    Statement stmt = null;
    try {
        // 获取全局配置对象
        Configuration configuration = ms.getConfiguration();
        // 创建 StatementHandler 对象
        StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
        // 创建 Statement 对象并绑定实参
        stmt = this.prepareStatement(handler, ms.getStatementLog());
        // 执行数据库查询操作，结果集映射
        return handler.<E>query(stmt, resultHandler);
    } finally {
        // 关闭 Statement
        this.closeStatement(stmt);
    }
}
```

方法首先会基于 Configuration 对象的 newStatementHandler 方法创建 StatementHandler 对象，这里本质上是采用了前面介绍的 RoutingStatementHandler 实现类依据入参进行创建，然后会调用 prepareStatement 方法创建 Statement 对象并绑定实参，接着执行具体的数据库查询操作，对于查询操作此时会执行结果集映射处理，最后关闭 Statement 对象。prepareStatement 方法的实现如下：

```java
private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    // 获取数据库连接
    Connection connection = this.getConnection(statementLog);
    // 获取 Statement 对象
    stmt = handler.prepare(connection, transaction.getTimeout());
    // 执行参数绑定
    handler.parameterize(stmt);
    return stmt;
}
```

其中 `StatementHandler#prepare` 方法执行的逻辑前面已经分析过，`StatementHandler#parameterize` 方法执行了参数绑定的逻辑，该方法在 SimpleStatementHandler 中为空实现，毕竟对于 `java.sql.Statement` 来说不支持设置参数的操作，而对于 PreparedStatementHandler 和 CallableStatementHandler 来说都是调用了 `ParameterHandler#setParameters` 方法，这个在前面已经专门分析过，就不再重复说明。

- __ReuseExecutor__

ReuseExecutor 提供了对 `java.sql.Statement` 对象重用的机制，以减少该对象创建和销毁以及 SQL 预编译所带来的开销。ReuseExecutor 类中定义了一个 statementMap 属性，其中 key 为 SQL 语句，value 为对应的 Statement 对象，以此来实现 Statement 对象的复用。

```java
/** 缓存 Statement 对象，key 为对应的 SQL 语句（带有 “？” 占位符） */
private final Map<String, Statement> statementMap = new HashMap<String, Statement>();
```

ReuseExecutor 中的方法实现也基本上沿用一套思路，这里同样以 doQuery 为例进行说明，该方法的实现如下：

```java
public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {
    // 获取配置对象
    Configuration configuration = ms.getConfiguration();
    // 创建对应的 StatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
    // 先尝试从缓存中获取当前 SQL 对应的 Statement 对象，缓存不命中则创建一个新的并缓存
    Statement stmt = this.prepareStatement(handler, ms.getStatementLog());
    // 执行数据库查询操作，结果集映射
    return handler.<E>query(stmt, resultHandler);
}
```

与 SimpleExecutor 的区别在于在获取 Statement 对象时会先尝试从本地缓存中获取，如果缓存不命中则会创建一个新的 Statement 对象，并更新缓存，实现位于 prepareStatement 方法中：

```java
private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    BoundSql boundSql = handler.getBoundSql();
    String sql = boundSql.getSql();
    if (this.hasStatementFor(sql)) {
        // 获取缓存的 Statement 对象
        stmt = this.getStatement(sql);
        // 设置超时时间
        this.applyTransactionTimeout(stmt);
    } else {
        Connection connection = this.getConnection(statementLog);
        stmt = handler.prepare(connection, transaction.getTimeout());
        // 缓存对应的 Statement 对象
        this.putStatement(sql, stmt);
    }
    // 绑定实参
    handler.parameterize(stmt);
    return stmt;
}
```

- __BatchExecutor__

BatchExecutor 用于批量执行 SQL 语句，通常我们的应用都是单行的执行 SQL，但是某些场景下单行执行数据库操作是比较耗时的，比如需要远程执行数据库操作，JDBC 针对 INSERT、UPDATE，以及 DELETE 操作提供了批量执行的支持。BatchExecutor 是批量 SQL 的执行器，其属性定义如下：

```java
/** 缓存多个 {@link Statement} 对象，每个对象都对应多条 SQL 语句 */
private final List<Statement> statementList = new ArrayList<Statement>();
/** 记录批处理的结果，每个 {@link BatchResult} 对应一个 {@link Statement} 对象 */
private final List<BatchResult> batchResultList = new ArrayList<BatchResult>();
/** 当前执行的 SQL 语句 */
private String currentSql;
/** 当前操作的 {@link MappedStatement} 对象 */
private MappedStatement currentStatement;
```

下面我们一起探究一下 BatchExecutor 的批处理执行过程，首先来看一下 doUpdate 方法实现，该方法用于添加批处理 SQL 语句：

```java
public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    // 获取配置对象
    final Configuration configuration = ms.getConfiguration();
    // 基于参数创建对应的 StatementHandler 对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
    final BoundSql boundSql = handler.getBoundSql();
    final String sql = boundSql.getSql();
    final Statement stmt;
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
        // 当前执行的 SQL 与前一次执行的 SQL （不包含实参）相同，且对应的 MappedStatement 对象也相同
        // 获取 statementList 中缓存的最后一个 Statement 对象
        int last = statementList.size() - 1;
        // 获取上次使用的 Statement 对象
        stmt = statementList.get(last);
        // 设置本次超时时间
        this.applyTransactionTimeout(stmt);
        // 绑定本次实参
        handler.parameterize(stmt);
        BatchResult batchResult = batchResultList.get(last);
        batchResult.addParameterObject(parameterObject);
    } else {
        // 当前执行的 SQL 与前一次执行的 SQL 不同
        Connection connection = this.getConnection(ms.getStatementLog());
        // 获取一个新的 Statement 对象
        stmt = handler.prepare(connection, transaction.getTimeout());
        // 绑定实参
        handler.parameterize(stmt);
        // 记录本次执行的 SQL 模式和 MappedStatement 对象
        currentSql = sql;
        currentStatement = ms;
        // 缓存本次的
        statementList.add(stmt);
        batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
    // 基于底层的 addBatch 方法添加批量 SQL 语句
    handler.batch(stmt);
    return BATCH_UPDATE_RETURN_VALUE;
}
```

上述方法中会判断当前执行的 SQL 模式（包含 “?” 占位符的 SQL 语句）是否与前一次执行的相同，如果相同就会获取上次执行的 Statement 对象，并为之绑定实参，否则就会创建一个新的 Statement 对象，并记录本次执行的 SQL 模式，最后基于底层的数据库批处理方法 addBatch 添加批量的 SQL 语句，有上面的方法我们可以知道对于连续同模式的批处理 SQL 操作会共享同一个 Statement 对象。

那么这些添加的批量 SQL 又是如何被执行的呢，这个过程位于  doFlushStatements 方法中，该方法的实现如下：

```java
public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
        List<BatchResult> results = new ArrayList<BatchResult>(); // 用于存储批量处理结果
        if (isRollback) {
            return Collections.emptyList();
        }
        // 遍历处理 Statement 集合
        for (int i = 0, n = statementList.size(); i < n; i++) {
            Statement stmt = statementList.get(i);
            // 设置超时时间
            this.applyTransactionTimeout(stmt);
            BatchResult batchResult = batchResultList.get(i);
            try {
                // 批量执行当前 Statement 蕴含的多条 SQL，并记录每条 SQL 影响的行数
                batchResult.setUpdateCounts(stmt.executeBatch());
                MappedStatement ms = batchResult.getMappedStatement();
                List<Object> parameterObjects = batchResult.getParameterObjects();
                KeyGenerator keyGenerator = ms.getKeyGenerator();
                if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
                    // 获取数据库生成的主键，并记录到 parameterObjects 中
                    Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
                    jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
                } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) {
                    for (Object parameter : parameterObjects) {
                        keyGenerator.processAfter(this, ms, stmt, parameter);
                    }
                }
            } catch (BatchUpdateException e) {
                // 省略异常处理实现
            }
            // 记录封装当前 Statement 对象的执行结果的 batchResult 到集合中
            results.add(batchResult);
        }
        return results;
    } finally {
        // 关闭所有的 Statement 对象
        for (Statement stmt : statementList) {
            this.closeStatement(stmt);
        }
        currentSql = null;
        statementList.clear();
        batchResultList.clear();
    }
}
```

方法会遍历我们在 doUpdate 中构造的 statementList 集合，分别执行集合中蕴含的 Statement 对象，并将执行的结果记录到 BatchResult 对象中（在 doUpdate 中已经为每个 Statement 对象构造好了一个空的 BatchResult 对象，记录在 batchResultList 集合中），并将 BatchResult 对象封装到集合中返回，因为都是数据库更新一类的操作，所以这里没有复杂的结果集映射，只需要记录每一条 SQL 执行所影响的行数即可。

- __CachingExecutor__

有前面 Executor 的继承关系我们可以看到 CachingExecutor 相对于其它 Executor 实现来说似乎有其特别之处，CachingExecutor 直接实现了 Executor 接口，实际上它是一个 Executor 装饰器，用于为 Executor 提供二级缓存支持。该接口的属性定义如下：

```java
/** 装饰的 {@link Executor} 对象 */
private final Executor delegate;
/** 用于管理当前使用的二级缓存对象 */
private final TransactionalCacheManager tcm = new TransactionalCacheManager();
```

其中第一个属性就是 CachingExecutor 具体修饰的 Executor 对象。我们来看一下第二个属性，TransactionalCacheManager 用来管理当前 CachingExecutor 对应的二级缓存对象，它的方法实现都比较简单，其中相对让人疑惑的是它的唯一一个属性：

```java
/** key 为对应的 {@link CachingExecutor} 使用的二级缓存对象，value 为采用 {@link TransactionalCache} 装饰的二级缓存对象 */
private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<Cache, TransactionalCache>();
```

transactionalCaches 的 key 就是当前对应的二级缓存，而 value 则是对于该二级缓存对象的采用 TransactionalCache 装饰后的对象，所以 key 和 value 本质上都映射到同一个缓存对象，只是 value 采用了 TransactionalCache 进行增强。TransactionalCache 也是一个缓存装饰器，在前面介绍缓存装饰器实现时特意留着没有说明，这里一起来分析一下，该装饰器的属性定义如下：

```java
/** 被装饰的 {@link Cache} 对象（二级缓存） */
private final Cache delegate;
/** 是否提交事务时清空缓存 */
private boolean clearOnCommit;
/** 用于缓存数据，当提交事务时会将其中的数据写入二级缓存 */
private final Map<Object, Object> entriesToAddOnCommit;
/** 缓存未命中的 key */
private final Set<Object> entriesMissedInCache;
```

对应的读缓存和写缓存操作，以及事务提交方法实现如下：

```java
public Object getObject(Object key) {
    // 先尝试从二级缓存中查询
    Object object = delegate.getObject(key);
    if (object == null) {
        // 二级缓存不命中，记录 key 到 entriesMissedInCache
        entriesMissedInCache.add(key);
    }
    if (clearOnCommit) {
        return null;
    } else {
        return object;
    }
}

public void putObject(Object key, Object object) {
    // 缓存数据项到 entriesToAddOnCommit 中
    entriesToAddOnCommit.put(key, object);
}

public void commit() {
    if (clearOnCommit) {
        delegate.clear();
    }
    // 写本地缓存项到二级缓存
    this.flushPendingEntries();
    // 重置属性
    this.reset();
}

private void flushPendingEntries() {
    // 遍历 entriesToAddOnCommit 中的数据写入二级缓存
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
        delegate.putObject(entry.getKey(), entry.getValue());
    }
    // 遍历 entriesMissedInCache，将 entriesToAddOnCommit 不包含的缓存项对应二级缓存中的值置为 null
    for (Object entry : entriesMissedInCache) {
        if (!entriesToAddOnCommit.containsKey(entry)) {
            delegate.putObject(entry, null);
        }
    }
}
```

下面继续回来看 CachingExecutor 的实现，所有实现方法中只有 query 方法稍微复杂一些，该方法的实现如下：

```java
public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler)
            throws SQLException {
    // 获取对应的 BoundSql 对象，创建对应的 CacheKey
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    CacheKey key = this.createCacheKey(ms, parameterObject, rowBounds, boundSql);
    // 调用重载的 query 方法
    return this.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
}

public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
            throws SQLException {
    // 获取当前命名空间对应的二级缓存对象
    Cache cache = ms.getCache();
    // 判断是否启用了二级缓存
    if (cache != null) {
        // 依据配置执行是否清空二级缓存
        this.flushCacheIfRequired(ms);
        if (ms.isUseCache() && resultHandler == null) {
            // 确保不是存储过程输出类型的参数
            this.ensureNoOutParams(ms, parameterObject, boundSql);
            // 查询二级缓存
            @SuppressWarnings("unchecked")
            List<E> list = (List<E>) tcm.getObject(cache, key);
            if (list == null) {
                // 二级缓存不命中，执行一级缓存查询，再不命中就查询数据库
                list = delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
                // 缓存到 TransactionalCache.entriesToAddOnCommit 中
                tcm.putObject(cache, key, list);
            }
            return list;
        }
    }
    // 没有启用二级缓存，则查询一级缓存，再不命中就查询数据库
    return delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
}
```

正如我们一开始对于 MyBatis 缓存结构设计描绘的那样，query 方法会首先在二级缓存中进行检索，如果二级缓存不命中，则会执行被装饰的 Executor 的 query 方法，而 Executor 的实现都自带一级缓存属性，所以接下去会查询一级缓存，只有在一级缓存也不命中的情况下，请求才会落库，并由数据库返回的结果对象更新一级缓存和二级缓存。

那么这里使用的二级缓存对象是在哪里创建的呢，实际上前面我们就定义说二级缓存是应用级别的，所以当应用启动时二级缓存就已经被创建的，这个过程发生在对映射文件进行解析时，在映射文件中我们会按照需要配置一定的 <cache/> 和 <cache-ref> 标签，而在解析 <cache/> 标签时会调用 `MapperBuilderAssistant#useNewCache` 方法创建对应的二级缓存对象。