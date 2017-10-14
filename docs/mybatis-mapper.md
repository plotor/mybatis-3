上一篇中我们分析了配置文件的加载和解析过程，本文我们将一起来探究映射文件的加载与解析实现，MyBatis 提供映射文件以配置 SQL 语句、二级缓存，以及结果集映射等，是区别与其它 ORM 框架的主要特色之一。在前面分析配置文件解析 `<mappers/>` 节点时，我们曾触及到 XMLMapperBuilder 的 parse 方法，这也正是解析映射文件的入口，该方法的实现如下：

```java
public void parse() {
    // 1. 加载并解析映射文件
    if (!configuration.isResourceLoaded(resource)) {
        // 映射文件未加载过, 解析 <mapper/> 下面的配置项
        this.configurationElement(parser.evalNode("/mapper"));
        // 记录到 Configuration.loadedResources 中，标记为已经加载过
        configuration.addLoadedResource(resource);
        // 注册 Mapper 接口（配置在 <mapper namespace=""/> 的 namespace 属性）
        this.bindMapperForNamespace();
    }

    // 2. 处理解析过程中失败的节点
    // 处理解析失败的 <resultMap/> 节点
    this.parsePendingResultMaps();
    // 处理解析失败的 <cache-ref/> 节点
    this.parsePendingCacheRefs();
    // 处理解析失败的 SQL 语句节点
    this.parsePendingStatements();
}
```

方法首先会判断映射文件是否被解析过，对于没有被解析过的文件则会调用 configurationElement 方法解析所有配置项，并注册当前映射文件关联的 Mapper 接口，对于解析过程中处理异常的节点，方法会记录到 Configuration 对象相应的集合中，并在方法最后再次尝试解析。整个方法中 configurationElement 包含了解析的核心步骤，与配置文件解析的实现方式一样，这也是一个调度方法：

```java
private void configurationElement(XNode context) {
    try {
        // 获取 <mapper/> 节点的 namespace 属性，设置当前映射文件关联的 Mapper 类
        String namespace = context.getStringAttribute("namespace");
        if (namespace == null || namespace.equals("")) {
            throw new BuilderException("Mapper's namespace cannot be empty");
        }
        builderAssistant.setCurrentNamespace(namespace);
        // 解析 <cache-ref/> 配置，多个 mapper 之间可以共享同一个二级缓存
        this.cacheRefElement(context.evalNode("cache-ref"));
        // 解析 <cache/> 配置
        this.cacheElement(context.evalNode("cache"));
        // 解析 <parameterMap/> 配置, 已经废弃
        this.parameterMapElement(context.evalNodes("/mapper/parameterMap"));
        // 解析 <resultMap/> 配置，建立结果集与对象属性之间的映射关系
        this.resultMapElements(context.evalNodes("/mapper/resultMap"));
        // 解析 <sql/> 配置
        this.sqlElement(context.evalNodes("/mapper/sql"));
        // 解析 <select/>, <insert/>, <update/>, <delete/> 配置
        this.buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
        throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
    }
}
```

每个映射文件都关联一个具体的 Mapper 接口，而 `<mapper/>` 节点的 namespace 属性则用于指定对应的 Mapper 接口限定名。方法首先会获取 namespace 属性，然后调用相应方法对每个子标签逐一解析。

### 一. 映射文件的解析过程

下面对各个节点的解析过程进行探究，考虑到 `<parameterMap/>` 已废弃，所以不再对其多做说明。

#### 1.1 标签 &lt;cache/&gt; 的解析机制

MyBatis 在设计上分为一级缓存和二级缓存（关于缓存结构设计会在下一篇分析 SQL 执行过程时进行介绍，这里只要知道有这样两个概念即可），该标签用于对二级缓存进行配置。在具体分析缓存设置标签之前，我们需要对 MyBatis 的缓存类设计有一个认知，不然可能会云里雾里。MyBatis 的缓存类设计还是非常巧妙的，不管是一级缓存还是二级缓存，都对应一个 Cache 接口：

```java
public interface Cache {
    /** 缓存对象 ID */
    String getId();
    /** 添加数据到缓存，一般来说 key 是 {@link CacheKey} 类型 */
    void putObject(Object key, Object value);
    /** 从缓存中获取 key 对应的 value */
    Object getObject(Object key);
    /** 从缓存中移除指定对象 */
    Object removeObject(Object key);
    /** 清空缓存 */
    void clear();
    /**
     * 获取缓存对象的个数（不是缓存的容量）
     * 该方法不会在 MyBatis 核心代码中被调用，可以是一个空实现
     */
    int getSize();
    /**
     * 缓存读写锁
     * 该方法不会在 MyBatis 核心代码中被调用，可以是一个空实现
     */
    ReadWriteLock getReadWriteLock();
}
```

Cache 中声明的缓存操作接口中规中矩，围绕该接口 MyBatis 提供了基于 HashMap 数据结构的基本实现 PerpetualCache，该实现类的各项方法实现都是对 HashMap 方法的封装，比较简单。在整个缓存类设计方面，MyBatis 的作者使用了典型的装饰器设计模式为缓存对象增加不同的特性，这些装饰器包括：

> - BlockingCache：阻塞缓存装饰器
> - FifoCache：先进先出缓存装饰器
> - LruCache：近期最少使用缓存装饰器
> - LoggingCache：日志功能缓存装饰器
> - ScheduledCache：周期性清理缓存装饰器
> - SerializedCache：序列化支持缓存装饰器
> - SoftCache：软引用缓存装饰器
> - WeakCache：弱引用缓存装饰器
> - SynchronizedCache：同步缓存装饰器
> - TransactionalCache:事务缓存装饰器（主要用于二级缓存，留到下一篇介绍缓存模块设计时再进行分析）

BlockingCache 采用一个 ConcurrentHashMap 对象记录每个 key 对应的可重入锁对象，当执行 getObject 操作时会尝试获取 key 对应的锁对象，并尝试带有超时机制的加锁操作，在获取到缓存值之后会释放锁。

FifoCache 采用一个双端队列来记录 key 进入缓存的顺序，队列的大小默认是 1024，当执行 putObject 操作时，如果当前缓存的对象数超过缓存大小，则会触发 FIFO 策略。

LruCache 通过一个 LinkedHashMap 类型的 keyMap 属性记录缓存中每个 key 的使用情况，并使用一个 eldestKey 对象记录当前最少被使用的 key，当缓存达到容量上限时将会移除使用频率最小的缓存项。

LoggingCache 并不是如其字面意思是对缓存增加日志记录功能，该缓存装饰器中增加了两个属性 requests 和 hits，分别用于记录缓存被访问的次数和缓存命中的次数，并提供了 getHitRatio 方法以获取当前缓存的命中率。

ScheduledCache 用于定期对缓存进行执行 clear 操作，其中定义了两个属性 clearInterval 和 lastClear 分别用来记录执行清理的时间间隔（默认为 1 小时）和最近一次执行清理的时间戳，在每次操作缓存时都会触发对缓存当前清理状态的检查，如果间隔时间达到设置值，就会触发对缓存的清理操作。

SerializedCache 缓存装饰器会对缓存值进行序列化处理后再进行缓存，当我们执行 putObject 操作时，该装饰器会基于 java 的序列化机制对缓存值进行序列化（序列化结果存储在内存），反之，当我们执行 getObject 操作时，如果对应的缓存值存在，则会对该值执行反序列化再返回。

SoftCache 和 WeakCache 在实现上流程上几乎相同，这两个缓存装饰器都会通过相应的 Entry 内部类对缓存值进行修饰，区别在于前者使用的是软引用，后者使用的是弱引用，也就是说这两类缓存所缓存的对象的生命周期受垃圾回收器 GC 操作的影响。

SynchronizedCache 缓存装饰在相应的缓存对象前都增加了 synchronized 关键字修饰，类似于 HashTable 的实现方式。

介绍完了缓存类的基本设计，我们再回过头来继续探究 `<cache/>` 标签的解析过程，该过程位于 cacheElement 方法中：

```java
private void cacheElement(XNode context) throws Exception {
    if (context != null) {
        // 获取相应的是属性配置
        String type = context.getStringAttribute("type", "PERPETUAL"); // type，缓存类型，可以指定自定义实现
        Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
        String eviction = context.getStringAttribute("eviction", "LRU"); // eviction， 缓存策略
        Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
        Long flushInterval = context.getLongAttribute("flushInterval"); // flushInterval，刷新间隔
        Integer size = context.getIntAttribute("size"); // size, 缓存大小
        boolean readWrite = !context.getBooleanAttribute("readOnly", false); // readOnly，是否只读
        boolean blocking = context.getBooleanAttribute("blocking", false); // blocking， 是否阻塞
        Properties props = context.getChildrenAsProperties();
        // 创建二级缓存对象，并记录到 Configuration.caches 中
        builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
}
```

上述方法首先会获取 `<cache/>` 标签的相关属性配置，然后调用 MapperBuilderAssistant 的 useNewCache 方法创建对应的缓存对象，并记录到 Configuration 的 caches 属性中。useNewCache 方法中使用了缓存对象构造器 CacheBuilder 创建缓存对象，一起来看一下 build 方法实现：

```java
public Cache build() {
    // 如果没有指定则设置缓存默认实现（以 PerpetualCache 作为默认实现，以 LruCache 作为默认装饰器）
    this.setDefaultImplementations();
    // 以反射的形式创建缓存对象
    Cache cache = this.newBaseCacheInstance(implementation, id);
    // 以 properties 配置初始化缓存对象
    this.setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    if (PerpetualCache.class.equals(cache.getClass())) {
        // 如果缓存采用 PerpetualCache 实现（对应自定义缓存实现），则遍历构造装饰器对象，并应用属性配置
        for (Class<? extends Cache> decorator : decorators) {
            // 基于反射的方式装饰缓存对象
            cache = this.newCacheDecoratorInstance(decorator, cache);
            this.setCacheProperties(cache);
        }
        // 注入标准装饰器
        cache = this.setStandardDecorators(cache);
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
        // 采用日志缓存装饰器对缓存对象进行装饰
        cache = new LoggingCache(cache);
    }
    return cache;
}
```

方法首先会判断是否指定了缓存实现类，否则设置默认的缓存实现（即以 PerpetualCache 作为默认实现，以 LruCache 作为默认缓存装饰器），然后选择 String 类型参数的构造方法构造缓存对象，并基于配置对缓存对象进行初始化，最后对依据缓存实现注入相应的缓存装饰器。setCacheProperties 方法中除了设置相应配置属性外，还会判断缓存类是否实现了 InitializingObject 接口，以决定是否调用 initialize 初始化方法。setStandardDecorators 方法中会基于当前的配置为缓存对象注入相应的缓存装饰器，实现如下：

```java
private Cache setStandardDecorators(Cache cache) {
    try {
        MetaObject metaCache = SystemMetaObject.forObject(cache);
        if (size != null && metaCache.hasSetter("size")) {
            metaCache.setValue("size", size);
        }
        if (clearInterval != null) {
            // 采用周期性执行清理工作的缓存装饰器进行装饰
            cache = new ScheduledCache(cache);
            ((ScheduledCache) cache).setClearInterval(clearInterval);
        }
        if (readWrite) {
            // 采用序列化支持缓存装饰器进行装饰
            cache = new SerializedCache(cache);
        }
        // 采用日志缓存装饰器进行装饰
        cache = new LoggingCache(cache);
        // 采用同步缓存装饰器进行装饰
        cache = new SynchronizedCache(cache);
        if (blocking) {
            // 采用阻塞缓存装饰器进行装饰
            cache = new BlockingCache(cache);
        }
        return cache;
    } catch (Exception e) {
        throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
}
```

#### 1.2 标签 &lt;cache-ref/&gt; 的解析机制

`<cache-ref/>` 标签用于引用其它命名空间中定义的缓存对象，从而能够让一个缓存对象在多个命名空间之间共享，该标签的解析位于 cacheRefElement 方法中：

```java
private void cacheRefElement(XNode context) {
    if (context != null) {
        // 记录 (当前节点所在的 namespace, 引用缓存对象所在的 namespace) 到 Configuration.cacheRefMap 中
        configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
        // 构造缓存引用解析器 CacheRefResolver 对象
        CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
        try {
            // 从记录缓存对象的 Configuration.caches 集合中获取引用的缓存对象
            cacheRefResolver.resolveCacheRef();
        } catch (IncompleteElementException e) {
            // 如果解析出现异常则记录到 Configuration.incompleteCacheRefs 中，后续再处理
            configuration.addIncompleteCacheRef(cacheRefResolver);
        }
    }
}
```

方法首先会在 `Configuration#cacheRefMap` 属性中记录一下当前的引用关系，其中 key 是 `<cache-ref/>` 所在的 namespace，value 则是引用的缓存对象所在的 namespace，然后从 `Configuration#caches` 属性中获取引用的缓存对象，在介绍 `<cache/>` 标签的解析实现时，我们曾提及到最终解析构造的缓存对象会记录到 Configuration 的 caches 属性中，这里则是一个逆过程。

#### 1.3 标签 &lt;resultMap/&gt; 的解析机制

`<resultMap/>` 标签用于配置结果集映射，建立结果集与 java bean 属性之间的映射关系，这是一个非常有用且提升开发效率的配置，如果是纯 JDBC 开发，甚至包括我司自研的 ORM 框架，在处理结果集与 java bean 之间的映射时，还需要手动硬编码注入，对于一张字段较多的表来说，简直写到手抽筋，而 `<resultMap/>` 配置配合 mybatis-generator 工具的逆向工程则可以解放我们的双手。下面是一个典型的配置，用于建立数据表 t\_user 与 User 实体之间的属性映射关系：

```xml
<resultMap id="user_result_map" type="org.zhenchao.mybatis.entity.User">
    <id column="id" jdbcType="BIGINT" property="id" />
    <result column="username" jdbcType="VARCHAR" property="username" />
    <result column="password" jdbcType="CHAR" property="password" />
    <result column="age" jdbcType="INTEGER" property="age" />
    <result column="phone" jdbcType="VARCHAR" property="phone" />
    <result column="email" jdbcType="VARCHAR" property="email" />
</resultMap>
```

在开始介绍 `<resultMap/>` 标签的解析过程之前，我们需要对该标签涉及到的两个主要的类 ResultMapping 和 ResultMap 有一个了解，前者用于封装除 `<discriminator/>` 标签（该标签具备自己的封装类）以外的具体的一子节点配置，后者则是对整个 `<resultMap/>` 节点的封装。

```java
public class ResultMapping {

    private Configuration configuration;

    /** 对应节点的 property 属性 */
    private String property;
    /** 对应节点的 column 属，对应数据表列名（or 别名） */
    private String column;
    /** 对应 java 类型，类型全限定名（or 别名） */
    private Class<?> javaType;
    /** 对应列的 JDBC 类型 */
    private JdbcType jdbcType;
    /** 类型处理器，会覆盖默认类型处理器 */
    private TypeHandler<?> typeHandler;
    /** 对应节点的 resultMap 属性，以 id 的方式引某个定义的 <resultMap/> */
    private String nestedResultMapId;
    /** 对应节点的 select 属性，以 id 的方式引用某个定义的 <select/> */
    private String nestedQueryId;
    /** 节点 notNullColumns 属性配置 */
    private Set<String> notNullColumns;
    /** 节点 columnPrefix 属性配置 */
    private String columnPrefix;
    /** 处理后的标志 */
    private List<ResultFlag> flags;
    /** 对应节点 column 拆分后生成的结果 */
    private List<ResultMapping> composites;
    /** 对应节点 resultSet 属性配置 */
    private String resultSet;
    /** 对应节点 foreignColumn 属性配置 */
    private String foreignColumn;
    /** 对应节点 fetchType 属性配置，是否延迟加载 */
    private boolean lazy;
    ResultMapping() {}

    // 省略构造器类，以及 getter 和 setter
}
```

ResultMapping 的中定义的属性如上述代码注释，另外还内置了一个 Builder 内部构造器类，用于封装数据构造 ResultMapping 对象，并对属性值进行基本的校验逻辑。

```java
public class ResultMap {

    private Configuration configuration;

    /** <resultMap/> 的 id 属性 */
    private String id;
    /** <resultMap/> 的 type 属性 */
    private Class<?> type;
    /** 除 <discriminator/> 以外的其他映射关系 */
    private List<ResultMapping> resultMappings;
    /** 记录带有 id 属性的映射关系 */
    private List<ResultMapping> idResultMappings;
    /** 记录带有 constructor 属性的映射关系 */
    private List<ResultMapping> constructorResultMappings;
    /** 记录带有 property 属性的映射关系 */
    private List<ResultMapping> propertyResultMappings;
    /** 记录配置中所有的 column 属性集合 */
    private Set<String> mappedColumns;
    /** 记录配置中所有的 property 属性集合 */
    private Set<String> mappedProperties;
    /** 对应 <discriminator/> 节点 */
    private Discriminator discriminator;
    /** 是否包含嵌套的结果映射 */
    private boolean hasNestedResultMaps;
    /** 是否包含嵌套查询 */
    private boolean hasNestedQueries;
    /** 是否开启自动映射 */
    private Boolean autoMapping;

    private ResultMap() {}

    // 省略构造器类，以及 getter 和 setter
}
```

ResultMap 的中定义的属性如上述代码注释，与 ResultMapping 一样，也是通过内置 Builder 内部构造器类来构造 ResultMap 对象，构造器的实现比较简单，不再贴出。

了解了内部数据结构 ResultMapping 和 ResultMap 的定义以及它们之间的相互依赖关系，接下来一起探究一下 `<resultMap/>` 标签的解析过程，具体实现位于 resultMapElement 方法中：

```java
private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    // 获取 id 属性（标识当前 <resultMap/>），如果没有指定则使用 XNode.getValueBasedIdentifier() 生成默认值
    String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());

    // 获取 type 属性，支持 type, ofType, resultType, javaType 属性配置
    String type = resultMapNode.getStringAttribute("type",
            resultMapNode.getStringAttribute("ofType",
                    resultMapNode.getStringAttribute("resultType",
                            resultMapNode.getStringAttribute("javaType"))));

    // 获取 extends 属性，用于指定继承关系
    String extend = resultMapNode.getStringAttribute("extends");

    // 获取 autoMapping 属性，是否启动自动映射（自动查找与列名相同的属性名称，并执行注入）
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

    // 基于 TypeAliasRegistry 解析 type 属性对应的 Class 对象
    Class<?> typeClass = this.resolveClass(type);
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>(); // 用于记录解析结果
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren(); // 获取所有的子节点
    // 遍历处理子标签
    for (XNode resultChild : resultChildren) {
        if ("constructor".equals(resultChild.getName())) {
            // 解析 <constructor /> 子标签
            this.processConstructorElement(resultChild, typeClass, resultMappings);
        } else if ("discriminator".equals(resultChild.getName())) {
            // 解析 <discriminator /> 子标签
            discriminator = this.processDiscriminatorElement(resultChild, typeClass, resultMappings);
        } else {
            // 解析 <association/>, <collection/>, <id/>, <result/> 子标签
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            if ("id".equals(resultChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            // 创建 ResultMapping 对象，并记录到 resultMappings 集合中
            resultMappings.add(this.buildResultMappingFromContext(resultChild, typeClass, flags));
        }
    }
    ResultMapResolver resultMapResolver =
            new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
        // 创建 ResultMap 对象，记录到 Configuration.resultMaps 中
        return resultMapResolver.resolve();
    } catch (IncompleteElementException e) {
        // 记录解析异常的 <resultMap/> 节点到 Configuration.incompleteResultMaps 属性中
        configuration.addIncompleteResultMap(resultMapResolver);
        throw e;
    }
}
```

一个 `<resultMap/>` 标签包含如下 4 个属性配置，解析过程首先尝试获取标签的 id 标识，如果没有则会调用 `XNode#getValueBasedIdentifier`
基于规则生成一个，接着获取 type 属性，指明当前标签所关联的 java bean，支持 type、ofType、resultType，以及 javaType 属性配置，然后获取 extends 属性，用于指定当前标签的继承关系，最后就是获取 autoMapping 属性，这是 boolean 类型的配置项，如果为 true 则表示开启自动映射功能，会自动查找对象中与结果集列名相同的属性名，并调用 setter 方法进行注入。MyBatis 采取优先使用 `<resultMap/>` 标签中明确指定的映射关系，否则会尝试自动映射。

```xml
<resultMap id="" type="" extends="" autoMapping=""/>
```

获取完对应的属性配置之后，接下来将遍历处理每个子标签配置，`<resultMap/>` 的子标签包含 `<constructor/>`、`<id/>`、`<result/>`、`<association/>`、`<collection/>`、`<discriminator/>` 五种，具体标签的作用可以参阅官方文档，下面来一起探究一下各个子标签的解析细节：

```java
private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    // 获取 <constructor /> 标签中配置的子节点
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
        List<ResultFlag> flags = new ArrayList<ResultFlag>();
        flags.add(ResultFlag.CONSTRUCTOR);
        if ("idArg".equals(argChild.getName())) {
            flags.add(ResultFlag.ID); // 添加 ID 标识
        }
        // 创建 ResultMapping 对象，并记录到 resultMappings 集合中
        resultMappings.add(this.buildResultMappingFromContext(argChild, resultType, flags));
    }
}
```

`<constructor/>` 标签没有属性配置，用于指定 java bean 的构造方法以构造结果对象，所以方法直接遍历处理标签的所有子标签，即 idArg  和 arg，并调用 buildResultMappingFromContext 方法创建 ResultMapping：

```java
private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    // 获取对应属性配置
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
        property = context.getStringAttribute("name");
    } else {
        property = context.getStringAttribute("property");
    }
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    // 存在嵌套配置，嵌套解析
    String nestedResultMap = context.getStringAttribute("resultMap", this.processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    // 基于 TypeAliasRegistry 解析 JavaType 对应的 Class 对象
    Class<?> javaTypeClass = this.resolveClass(javaType);
    // 基于 TypeAliasRegistry 解析 TypeHandler 对应的 Class 对象
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    // 获取 JdbcType 对应的具体枚举对象
    JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
    // 创建 ResultMapping 对象
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum,
            nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
}
```

方法首先会获取标签所有的属性配置项，并基于 TypeAliasRegistry  对属性所表示的类型进行解析，最后调用 `MapperBuilderAssistant#buildResultMapping` 方法构造封装配置项对应的 ResultMapping 对象，这里本质上还是调用 ResultMapping 的构造器进行构造。buildResultMappingFromContext 方法是一个通用方法，除了上面用于封装 `<constructor/>` 子标签，对于上层标签 `<id/>`, `<result/>`, `<association/>`, `<collection/>` 来说也都是直接调用该方法进行解析。

剩下就只有 `<discriminator/>`，这个标签不是直接基于 ResultMapping 对象进行封装，而是采用 Discriminator 类对 ResultMapping 进行了封装，这主要取决于该标签的用途，我们使用该标签基于具体的结果值选择不同的结果集映射。

```java
private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings)
        throws Exception {
    // 获取相关配置属性
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    // 基于 TypeAliasRegistry 解析类型属性对应的类型对象
    Class<?> javaTypeClass = this.resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = this.resolveJdbcType(jdbcType);
    // 遍历处理子标签配置
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
        String value = caseChild.getStringAttribute("value");
        // 嵌套调用
        String resultMap = caseChild.getStringAttribute("resultMap", this.processNestedResultMappings(caseChild, resultMappings));
        discriminatorMap.put(value, resultMap);
    }
    // 创建 Discriminator 对象，本质上依赖于 Discriminator 的构造器进行创建
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
}
```

上述方法用于解析 `<discriminator/>` 标签配置，具体的步骤与其它标签的解析过程如出一辙，可以参考代码注释。

在将这五类子标签解析成为相应对象，并记录到 resultMappings 集合中之后，下一步就是基于这些解析得到的配置构造 ResultMapResolver 解析器对象，调用 `ResultMapResolver#resolve` 方法对配置进行解析，创建 ResultMap 对象，并记录到 Configuration 配置对象的 resultMaps 属性中。ResultMapResolver 的 resolve 方法并没有太多逻辑，而是直接调用了 `MapperBuilderAssistant#addResultMap` 方法：

```java
public ResultMap addResultMap(
        String id, Class<?> type, String extend, Discriminator discriminator, List<ResultMapping> resultMappings, Boolean autoMapping) {
    // 格式化 id 值，形式：namespace.id
    id = this.applyCurrentNamespace(id, false);
    extend = this.applyCurrentNamespace(extend, true);

    // 处理 extend
    if (extend != null) {
        if (!configuration.hasResultMap(extend)) {
            // 需要被继承的 ResultMap 对象不存在
            throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
        }

        // 获取需要被继承的 ResultMap 对象
        ResultMap resultMap = configuration.getResultMap(extend);
        // 获取父 ResultMap 对象中包含的 ResultMapping 对象集合
        List<ResultMapping> extendedResultMappings = new ArrayList<ResultMapping>(resultMap.getResultMappings());
        // 删除需要覆盖的 ResultMapping 对象
        extendedResultMappings.removeAll(resultMappings);

        boolean declaresConstructor = false;
        for (ResultMapping resultMapping : resultMappings) {
            // 查找当前 <resultMap/> 标签中是否定义了 <constructor/> 子节点
            if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                declaresConstructor = true;
                break;
            }
        }
        if (declaresConstructor) {
            // 当前 <resultMap/> 中定义了 <constructor/> 子节点，则无需父 ResultMap 中记录的相应 <constructor/>，遍历删除
            Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
            while (extendedResultMappingsIter.hasNext()) {
                if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    extendedResultMappingsIter.remove();
                }
            }
        }

        // 添加需要继承的 ResultMapping 对象集合
        resultMappings.addAll(extendedResultMappings);
    }

    // 创建 ResultMap 对象，并记录到 Configuration.resultMaps 中
    ResultMap resultMap = new ResultMap.Builder(
            configuration, id, type, resultMappings, autoMapping).discriminator(discriminator).build();
    configuration.addResultMap(resultMap);
    return resultMap;
}
```

#### 1.5 标签 &lt;sql/&gt; 的解析机制

在 MyBatis 中，我们可以通过 `<sql/>` 节点配置一些可以被复用的 SQL 语句片段，当我们在某个 SQL 中需要使用这些片段时，可以通过 `<include/>` 子标签进行引入，具体可以参考官方文档示例。对于该节点的解析由 sqlElement 方法实现，并最终记录到 `Configuration.sqlFragments` 集合中，方法实现如下：

```java
private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    // 遍历处理所有的 <sql/> 节点
    for (XNode context : list) {
        String databaseId = context.getStringAttribute("databaseId"); // 获取 databaseId 属性配置
        String id = context.getStringAttribute("id"); // 获取 id 属性配置
        id = builderAssistant.applyCurrentNamespace(id, false); // 格式化 id：namespace.id
        /*
         * 判断 databaseId 与当前 configuration 中配置的是否一致
         * 1. 如果指定了 requiredDatabaseId，则 databaseId 必须和 requiredDatabaseId 一致
         * 2. 如果没有指定了 requiredDatabaseId，则 databaseId 必须为 null
         */
        if (this.databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
            // 记录到 Configuration.sqlFragments 属性中
            sqlFragments.put(id, context);
        }
    }
}
```

方法首先会获取该标签的属性配置，即 id 和 databaseId，并对 id 进行格式化处理，然后会判断当前` <sql/>` 配置的 databaseId 是否与当前运行的数据库环境相匹配，对于不匹配的 `<sql/>` 节点则选择忽略。requiredDatabaseId 参数在重载方法中指定，可以看到就是从全局配置对象中获取的 databaseId 值：

```java
private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
        // 获取当前运行数据库环境对应的 databaseId
        this.sqlElement(list, configuration.getDatabaseId());
    }
    this.sqlElement(list, null);
}
```

最终这些解析得到的 `<sql/>` 节点会被记录到 Configuration 配置对象的 sqlFragments 属性中（在构造 XMLMapperBuilder 对象时进行初始化），后面分析 `<include/>` 标签时可以看到会从该属性值获取引用的 SQL 语句片段。

#### 1.6 标签 &lt;select/&gt;, &lt;insert/&gt;, &lt;update/&gt;, &lt;delete/&gt; 的解析机制

`<select/>`、`<insert/>`、`<update/>`，以及 `<delete/>` 4 个标签用于配置映射文件中最核心的数据库操作语句（下文统称这 4 个标签为 SQL 语句标签），包括静态 SQL 和动态 SQL。MyBatis 通过 MappedStatement 封装这些 SQL 语句标签的配置，并调用 `XMLStatementBuilder#parseStatementNode` 方法对配置进行解析，构建 MappedStatement 对象并记录到 Configuration 对象的 mappedStatements 属性中。XMLMapperBuilder 的 buildStatementFromContext 方法对于标签的解析主要做了一些统筹调度的工作，具体解析还是交由 XMLStatementBuilder 进行处理，buildStatementFromContext 的实现如下：

```java
private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
        this.buildStatementFromContext(list, configuration.getDatabaseId());
    }
    this.buildStatementFromContext(list, null);
}

private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    // 遍历处理获取到的所有 SQL 语句标签
    for (XNode context : list) {
        // 创建 XMLStatementBuilder 对象，负责解析具体的一个 SQL 语句标签
        final XMLStatementBuilder statementParser =
                new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
        try {
            // 执行解析操作
            statementParser.parseStatementNode();
        } catch (IncompleteElementException e) {
            // 记录解析异常的 SQL 语句标签节点到 Configuration.incompleteStatements 属性中
            configuration.addIncompleteStatement(statementParser);
        }
    }
}
```

上述代码的实现比较简单，无非是遍历获取到的所有 SQL 语句标签列表，然后创建 XMLStatementBuilder 对象并调用 parseStatementNode 方法对各个 SQL 语句标签进解析，对于解析异常的标签则会记录到 Configuration 对象的 incompleteStatements 属性中，后续会再次尝试解析，我们来探究一下 XMLStatementBuilder 的 parseStatementNode 的实现细节：

```java
public void parseStatementNode() {
    // 获取 id 和 databaseId
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    // 判断当前SQL是否适配当前数据库，对于不适配的SQL直接忽略
    if (!this.databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
        return;
    }

    // 获取属性配置
    Integer fetchSize = context.getIntAttribute("fetchSize"); // 设置批量返回的结果行数，默认值为 unset（依赖驱动）
    Integer timeout = context.getIntAttribute("timeout"); // 数据库执行超时时间，默认值为 unset（依赖驱动）
    String parameterMap = context.getStringAttribute("parameterMap"); // parameterMap 参数已经废弃
    String parameterType = context.getStringAttribute("parameterType"); // 传入参数类型的完全限定名或别名
    Class<?> parameterTypeClass = this.resolveClass(parameterType);
    String resultMap = context.getStringAttribute("resultMap"); // 引用的 <resultMap/> 的标签ID
    String resultType = context.getStringAttribute("resultType"); // 期望返回类型完全限定名或别名。注意如果是集合情形，那应该是集合可以包含的类型，而不能是集合本身
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = this.getLanguageDriver(lang);
    Class<?> resultTypeClass = this.resolveClass(resultType);
    String resultSetType = context.getStringAttribute("resultSetType"); // FORWARD_ONLY，SCROLL_SENSITIVE 或 SCROLL_INSENSITIVE 中的一个，默认值为 unset （依赖驱动）
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString())); // 具体的 Statement 类型，参考 StatementType
    ResultSetType resultSetTypeEnum = this.resolveResultSetType(resultSetType);
    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT; // 是否是 SELECT 语句
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect); // 任何时候只要语句被调用，都会导致本地缓存和二级缓存被清空，默认为 false
    boolean useCache = context.getBooleanAttribute("useCache", isSelect); // 设置本条语句的结果被二级缓存，默认值：对 select 元素为 true
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false); // 仅针对嵌套结果 select 语句适用

    // 解析 <include /> 节点
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 解析 <selectKey /> 节点
    this.processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // 解析 SQL 配置 (pre: <selectKey> and <include> were parsed and removed)
    // 创建 SQL 语句节点对应的 SqlSource 对象
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    // 获取 resultSets、keyProperty，以及 keyColumn
    String resultSets = context.getStringAttribute("resultSets"); // 仅对多结果集适用，将列出语句执行后返回的结果集并给每个结果集一个名称，名称是逗号分隔的。
    String keyProperty = context.getStringAttribute("keyProperty"); // （仅对 insert 和 update 有用）唯一标记一个属性，通过 getGeneratedKeys 的返回值或者通过 insert 语句的 selectKey 子元素设置它的键值
    String keyColumn = context.getStringAttribute("keyColumn"); // （仅对 insert 和 update 有用）通过生成的键值设置表中的列名，这个设置仅在某些数据库（像 PostgreSQL）是必须的，当主键列不是表中的第一列的时候需要设置

    // 解析对应的 KeyGenerator
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    if (configuration.hasKeyGenerator(keyStatementId)) {
        // 当前 SQL 语句标签下存在 <selectKey/> 配置
        keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
        // 依据当前节点的 useGeneratedKeys 配置，或全局的 useGeneratedKeys 配置以及是否是 insert 方法来决定具体的 keyGenerator 实现
        keyGenerator = context.getBooleanAttribute("useGeneratedKeys", // （仅对 insert 和 update 有用）这会使用 JDBC 的 getGeneratedKeys 方法来取出由数据库内部生成的主键
                configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    // 创建当前 SQL 语句配置对应的 MappedStatement 对象，并记录到 Configuration.mappedStatements 中
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
            fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
            resultSetTypeEnum, flushCache, useCache, resultOrdered,
            keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
}
```

方法首先会获取标签的 id 和 databaseId 属性，并判断当前 SQL 语句标签是否适用于当前的数据库环境，对于不适用的 SQL 语句标签则直接忽略。然后会获取标签的属性配置，并对配置的类型字面值进行解析，这些配置的含义已在代码中注释，如果希望更进一步了解各项配置的意义可以参考官方文档。接着会解析 `<include/>` 子节点和 `<selectKey/>` 子节点配置，这两个节点的具体解析过程稍后进行详细说明。最后会对 SQL 语句的具体配置进行解析，并封装成 MappedStatement 对象记录到 Configuration 对象的 mappedStatements 属性中，在这个过程中会调用 `LanguageDriver#createSqlSource` 创建 SQL 语句标签对应的 SqlSource 对象，SqlSource 用于封装 SQL 语句标签（或 Mapper 接口方法注解）中配置的 SQL 语句，但是这里的 SQL 并不是最终可以被数据库执行的 SQL，其中可能包含占位符，关于 SqlSource 对象暂时先了解其作用即可，后面会对其实现做详细说明，我们来看一下 createSqlSource 方法的实现，这里对应 XMLLanguageDriver 类的方法实现：

```java
public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
    XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
    return builder.parseScriptNode();
}

// org.apache.ibatis.scripting.xmltags.XMLScriptBuilder#parseScriptNode
public SqlSource parseScriptNode() {
    // 判断是否是动态 SQL，并进行解析
    List<SqlNode> contents = this.parseDynamicTags(context);
    // 创建封装 SqlNode 集合为 MixedSqlNode 对象
    MixedSqlNode rootSqlNode = new MixedSqlNode(contents);
    SqlSource sqlSource = null;
    if (isDynamic) {
        // 如果是动态 SQL，则封装为 DynamicSqlSource 对象
        sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
        // 否则封装为静态的 RawSqlSource 对象
        sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
}
```

parseScriptNode 方法的首先会调用 parseDynamicTags 方法对当前 SQL 语句标签中的占位符进行解析，并判断是否为动态 SQL，该过程实现如下：

```java
List<SqlNode> parseDynamicTags(XNode node) {
    List<SqlNode> contents = new ArrayList<SqlNode>();
    NodeList children = node.getNode().getChildNodes(); // 获取所有子节点
    for (int i = 0; i < children.getLength(); i++) {
        // 构造对应的 XNode 对象，过程中会尝试解析所有的 ‘${}’ 占位符
        XNode child = node.newXNode(children.item(i));
        if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
            String data = child.getStringBody(""); // 获取节点的 value 值
            TextSqlNode textSqlNode = new TextSqlNode(data);
            // 基于是否存在未解析的占位符 ‘${}’ 判断是否是动态 SQL
            if (textSqlNode.isDynamic()) {
                contents.add(textSqlNode);
                isDynamic = true; // 标记为动态 SQL
            } else {
                contents.add(new StaticTextSqlNode(data));
            }
        } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
            // 如果子节点是一个 element，则必定是一个动态 SQL
            String nodeName = child.getNode().getNodeName();
            // 获取 nodeName 对应的 NodeHandler
            NodeHandler handler = this.nodeHandlers(nodeName);
            if (handler == null) {
                throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
            }
            // 基于具体的 NodeHandler 处理动态 SQL
            handler.handleNode(child, contents);
            isDynamic = true;
        }
    }
    return contents;
}
```

整个过程主要是遍历当前 SQL 语句标签的所有子节点，并依据当前子节点的类型分而治之，可以对照官方文档的动态 SQL 配置示例进行理解。如果当前子节点是一个具体的字符串或 CDATA 表达式（即 SQL 语句片段），则会获取字面值并依据是否包含未解析的 “${}” 占位符判断是否是动态 SQL，并封装成对应的 SqlNode 对象，SqlNode 是一个接口，用于封装定义的动态 SQL 节点和文本节点，包含多种实现类，该接口及其具体实现类留到后面针对性讲解。如果当前子节点是一个具体的 XML 标签，则必定是一个动态 SQL 配置，这个时候会获取具体的节点名称，并调用 nodeHandlers 方法构造对应的 NodeHandler 对象进行处理：

```java
NodeHandler nodeHandlers(String nodeName) {
    Map<String, NodeHandler> map = new HashMap<String, NodeHandler>();
    map.put("trim", new TrimHandler());
    map.put("where", new WhereHandler());
    map.put("set", new SetHandler());
    map.put("foreach", new ForEachHandler());
    map.put("if", new IfHandler());
    map.put("choose", new ChooseHandler());
    map.put("when", new IfHandler());
    map.put("otherwise", new OtherwiseHandler());
    map.put("bind", new BindHandler());
    return map.get(nodeName);
}
```

NodeHandler 采用内部接口的形式实现，其实现类也都是内部类，并且实现逻辑都比较简单，这里我们以 ForEachHandler 为例进行说明，其余类的实现与之类似。ForEachHandler 类对应动态 SQL 中的 `<foreach/>` 标签，这是一个我十分喜欢的标签，可以很方便的动态构造较长的条件语句。NodeHandler 中仅声明了 handleNode 这一个方法，ForEachHandler 针对该方法的实现如下：

```java
public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
    // 解析 <foreach/> 的子节点
    List<SqlNode> contents = parseDynamicTags(nodeToHandle);
    MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
    // 获取相关属性配置
    String collection = nodeToHandle.getStringAttribute("collection"); // 迭代的集合表达式
    String item = nodeToHandle.getStringAttribute("item"); // 当前迭代的元素
    String index = nodeToHandle.getStringAttribute("index"); // 当前迭代的次数
    String open = nodeToHandle.getStringAttribute("open");
    String close = nodeToHandle.getStringAttribute("close");
    String separator = nodeToHandle.getStringAttribute("separator");
    // 封装成对应的 ForEachSqlNode 对象
    ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
    targetContents.add(forEachSqlNode);
}
```

在方法中首先会调用这里介绍的 parseDynamicTags 方法对占位符进行解析，然后获取标签相关属性配置，并构造 ForEachSqlNode 对象，ForEachSqlNode 类在后面介绍 SqlNode 类时会进行介绍，这里先不展开。

介绍完了 parseDynamicTags 方法，我们继续回到该方法调用的地方，即 `XMLScriptBuilder#parseScriptNode`，该方法接下来会依据 parseDynamicTags 方法的解析和判定结果分别创建对应的 SqlSource 对象，如果是动态 SQL，则采用 DynamicSqlSource 进行封装，否则采用 RawSqlSource 进行封装，到这里我们在配置文件或注解中定义的 SQL 语句就被解析封装成了对应的 SqlSource 对象驻于内存之中。接下来方法会依据配置创建对应的 KeyGenerator 对象，这个留到后面解析 `<selectKey/>` 子标签时再进行说明，最后会将当前 SQL 语句标签封装成 MappedStatement 对象，记录到 Configuration 的属性 mappedStatements 中。

##### 1.7 标签 &lt;include/&gt; 的解析机制

解析 SQL 语句标签的过程包含对 `<include/>` 标签配置的解析，前面我们曾介绍过 `<sql/>` 标签，该标签用于配置可复用的 SQL 语句片段，而 `<include/>` 标签则是用来引用 `<sql/>` 节点，具体使用方式可以参考官方文档。对于该标签的解析位于 XMLIncludeTransformer 类的 applyIncludes 方法中，该方法首先会尝试获取记录在 Configuration 对象中记录的 `<properties/>` 等属性变量，然后调用 `XMLIncludeTransformer#applyIncludes` 方法进行解析：

```java
private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    /*注意：最开始进入本方法时，source 变量对应的节点并不是 <include/> 节点，而是 <select/> 这类节点*/
    if (source.getNodeName().equals("include")) { // 处理 <include/> 节点
        // 获取 refid 指向的 <sql/> 节点对象的深拷贝
        Node toInclude = this.findSqlFragment(this.getStringAttribute(source, "refid"), variablesContext);
        // 获取 <include/> 下的 <property/> 属性，与 variablesContext 合并返回新的 Properties 对象
        Properties toIncludeContext = this.getVariablesContext(source, variablesContext);
        // 递归处理，这里的 included 参数为 true
        this.applyIncludes(toInclude, toIncludeContext, true);
        if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
            toInclude = source.getOwnerDocument().importNode(toInclude, true);
        }
        // 替换 <include/> 节点为 <sql/> 节点
        source.getParentNode().replaceChild(toInclude, source);
        while (toInclude.hasChildNodes()) {
            // 将 <sql/> 的子节点添加到 <sql/> 节点的前面
            toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
        }
        // 删除 <sql/> 节点
        toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
        // 遍历处理当前 SQL 语句节点的子节点
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            // 递归调用
            this.applyIncludes(children.item(i), variablesContext, included);
        }
    } else if (included && source.getNodeType() == Node.TEXT_NODE && !variablesContext.isEmpty()) {
        // 替换占位符为 variablesContext 中对应的配置值，这里替换的是引用 <sql/> 节点中定义的语句片段中对应的占位符
        source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
}
```

该方法在第一次进入时 source 参数对应的并不是一个 `<include/>` 标签，由参数可以推导出它是一个具体的 SQL 语句标签（即 `Node.ELEMENT_NODE`），所以方法一开始会进入中间的 `else if` 代码块，在这里会获取 SQL 语句标签的所有子节点，并递归调用 applyIncludes 方法进行处理，只有当存在 `<include/>` 节点时才会继续下面的逻辑（注意，最开始调用这里的 applyIncludes 方法时传递的 included 参数为 false，所以对于 SQL 语句标签下面的 `Node.TEXT_NODE` 类型字面值是不会进入最后一个 `else if` 代码块的）。如果当前是 `<include/>` 标签，则会尝试获取 refid 属性，并对属性值中的占位符进行解析替换，然后从 Configuration 对象的 sqlFragments 属性中获取 id 对应的 `<sql/>` 节点，返回节点的深拷贝对象，相关实现如下：

```java
private Node findSqlFragment(String refid, Properties variables) {
    // 解析带有 ‘${}’ 占位符的字符串，将其中的占位符变量替换成 variables 中对应的属性值
    refid = PropertyParser.parse(refid, variables); // 注意：这里替换并不是 <sql/> 语句片段中的占位符
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
        // 从 Configuration.sqlFragments 中获取 id 对应的 <sql/> 节点
        XNode nodeToInclude = configuration.getSqlFragments().get(refid);
        return nodeToInclude.getNode().cloneNode(true); // 深拷贝
    } catch (IllegalArgumentException e) {
        throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
}
```

接下来会尝试获取 `<include/>` 标签下的 `<property/>` 配置，并与入参的 variablesContext 合并成为新的 Properties 对象，然后会递归调用 applyIncludes 方法，此时第三个参数 included 为 true，暗示会进入最后一个 `else if` 代码块，这个过程中会依据之前解析得到的属性值替换引入的 SQL 语句片段中的占位符，最终将对应的 `<include/>` 节点替换成对应解析后的 `<sql/>` 节点，记录到当前隶属的 SQL 语句标签节点中。

##### 1.8 标签 &lt;selectKey/&gt; 的解析机制

下面分析 `<selectKey/>` 标签的解析机制，该标签用于为不支持自动生成自增主键的数据库或驱动提供主键生成支持，以及获取插入操作返回的主键值，该标签的解析位于 processSelectKeyNodes 方法中：

```java
private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    // 获取所有的 <selectKey/> 节点
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    // 解析 <selectKey/> 节点
    if (configuration.getDatabaseId() != null) {
        this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    this.parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    // 移除 <selectKey/> 节点
    this.removeSelectKeyNodes(selectKeyNodes);
}

private void parseSelectKeyNodes(
        String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    // 遍历处理所有的 <selectKey/>
    for (XNode nodeToHandle : list) {
        String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        String databaseId = nodeToHandle.getStringAttribute("databaseId");
        if (this.databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
            // 验证数据库环境是否匹配，忽略不匹配的 <selectKey/> 配置
            this.parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
        }
    }
}
```

上述方法执行过程如代码注释，这里解析的核心步骤位于 parseSelectKeyNode 方法中，该方法首先会获取 `<selectKey>` 相应的属性配置，然后封装定义的 SQL 语句为 SqlSource 对象，最后将整个 `<selectKey/>` 配置封装成为 MappedStatement 对象记录到 Configuration 对象的 mappedStatements 属性中，并创建对应的 KeyGenerator 对象，记录到 Configuration 对象的 keyGenerators 属性中（这里采用的是 SelectKeyGenerator 实现类）：

```java
private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    // 获取相应属性配置
    String resultType = nodeToHandle.getStringAttribute("resultType"); // 结果集类型
    Class<?> resultTypeClass = this.resolveClass(resultType);
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty"); // selectKey 生成结果应用的目标属性，多个用逗号分隔个
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn"); // 匹配属性的返回结果集中的列名称，多个以逗号分隔
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER")); // 在操作语句前还是后执行

    // 设置默认值
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    // 创建对应的 SqlSource（用于封装配置的SQL语句，不可执行），默认使用的是 XMLLanguageDriver
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    // 创建 SQL 对应的 MappedStatement 对象，并添加到 Configuration.mappedStatements 属性中
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
            fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
            resultSetTypeEnum, flushCache, useCache, resultOrdered,
            keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);
    id = builderAssistant.applyCurrentNamespace(id, false);
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);

    // 创建对应的 KeyGenerator，并添加到 Configuration.keyGenerators 属性中
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
}
```

在前面解析 SQL 语句标签时包含如下代码段，用于决策当前的 KeyGenerator 实现，如果当前标签配置了 `<selectKey/>` 则会优先从 Configuration 对象的 keyGenerators 属性中获取，也就是上面过程记录到该属性中的具体实现，对于没有配置 `<selectKey/>` 节点的标签来说，则会判断当前标签是否有设置 useGeneratedKeys 属性，或者判断当前是否有设置全局的 useGeneratedKeys 属性，以及当前是否是 INSERT 数据库操作类型来判断具体的 KeyGenerator 实现：

```java
// 解析对应的 KeyGenerator
KeyGenerator keyGenerator;
String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
if (configuration.hasKeyGenerator(keyStatementId)) {
    // 当前 SQL 语句标签下存在 <selectKey/> 配置
    keyGenerator = configuration.getKeyGenerator(keyStatementId);
} else {
    // 依据当前节点的 useGeneratedKeys 配置，或全局的 useGeneratedKeys 配置以及是否是 insert 方法来决定具体的 keyGenerator 实现
    keyGenerator = context.getBooleanAttribute("useGeneratedKeys", // （仅对 insert 和 update 有用）这会使用 JDBC 的 getGeneratedKeys 方法来取出由数据库内部生成的主键
            configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
            ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
}
```

对于 KeyGenerator 来说，上面我们看到了三种实现类：Jdbc3KeyGenerator、NoKeyGenerator，以及 SelectKeyGenerator。这也是目前 KeyGenerator 接口仅有的三种实现，该接口的定义如下：

```java
public interface KeyGenerator {
    /** 前置操作， order=BEFORE */
    void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter);
    /** 后置操作， order=AFTER */
    void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter);
}
```

对于三种实现来说，其中 NoKeyGenerator 虽然实现了该接口，但是对应方法体全部都是空实现，所以没什么可以分析的，我们接下来分别探究一下 Jdbc3KeyGenerator 和 SelectKeyGenerator 的实现。首先来看 Jdbc3KeyGenerator，这是一个用于获取数据库自增主键值的实现版本，Jdbc3KeyGenerator 的 processBefore 方法是一个空实现，主要实现逻辑位于 processAfter 方法中：

```java
public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // 将 Object 类型参数转换成相应的集合类型
    this.processBatch(ms, stmt, this.getParameters(parameter));
}

public void processBatch(MappedStatement ms, Statement stmt, Collection<Object> parameters) {
    ResultSet rs = null;
    try {
        // 获取数据库自动生成的主键
        rs = stmt.getGeneratedKeys();
        final Configuration configuration = ms.getConfiguration();
        final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        // 获取 keyProperties 属性配置，用于指定主键，可能存在多个
        final String[] keyProperties = ms.getKeyProperties();
        // 获取 ResultSet 元数据信息
        final ResultSetMetaData rsmd = rs.getMetaData();
        TypeHandler<?>[] typeHandlers = null;
        if (keyProperties != null && rsmd.getColumnCount() >= keyProperties.length) {
            for (Object parameter : parameters) {
                // there should be one row for each statement (also one for each parameter)
                if (!rs.next()) {
                    break;
                }
                // 创建实参对应的 MetaObject 对象
                final MetaObject metaParam = configuration.newMetaObject(parameter);
                if (typeHandlers == null) {
                    // 获取每个 keyProperty 对应的类型处理器
                    typeHandlers = this.getTypeHandlers(typeHandlerRegistry, metaParam, keyProperties, rsmd);
                }
                // 将生成的主键值与用户传入的参数相映射
                this.populateKeys(rs, metaParam, keyProperties, typeHandlers);
            }
        }
    } catch (Exception e) {
        throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
    } finally {
        // 省略 ResultSet 的 close 实现
    }
}
```

processAfter 的主要逻辑就是获取数据库自增的主键值，并设置到用户传递的实参中，用户指定的实参可以是一个具体的 java bean、Map 对象，以及集合类型，以处理批量插入的情况，所以方法首先会调用 getParameters 方法将传递的 Object 类型参数转换成对应的 Collection 类型，这个过程比较简单，这里就不再贴出源码，接下来看一下 processBatch 的具体细节。方法中首先会调用 `java.sql.Statement#getGeneratedKeys` 方法获取获取相应的结果集，然后基于 `<selectKey/>` 的 keyProperty 配置确定对象注入的属性，并将获取到的主键值映射到相应属性上。

SelectKeyGenerator 主要应用用于那些不支持自动生成自增主键的数据库，可以为这些数据库生成主键值，同时包含 Jdbc3KeyGenerator 返回具体主键值的功能。SelectKeyGenerator 实现了接口中定义的全部方法，但是这些方法本质上都是调用了 processGeneratedKeys 方法：

```java
private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
    try {
        if (parameter != null && keyStatement != null && keyStatement.getKeyProperties() != null) {
            // 获取 <selectKey/> 中配置的 keyProperty 属性
            String[] keyProperties = keyStatement.getKeyProperties();
            final Configuration configuration = ms.getConfiguration();
            // 创建入参 parameter 对应的 MetaObject 对象
            final MetaObject metaParam = configuration.newMetaObject(parameter);
            if (keyProperties != null) {
                // 创建 SQL 执行器，并执行 <selectKey/> 中定义的 SQL 语句
                Executor keyExecutor = configuration.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
                List<Object> values = keyExecutor.query(keyStatement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
                // 处理返回的主键对象
                if (values.size() == 0) {
                    throw new ExecutorException("SelectKey returned no data.");
                } else if (values.size() > 1) {
                    throw new ExecutorException("SelectKey returned more than one value.");
                } else {
                    // 创建主键值对应的 MetaObject 对象
                    MetaObject metaResult = configuration.newMetaObject(values.get(0));
                    if (keyProperties.length == 1) {
                        // 单列主键的情况
                        if (metaResult.hasGetter(keyProperties[0])) {
                            this.setValue(metaParam, keyProperties[0], metaResult.getValue(keyProperties[0]));
                        } else {
                            // 每个 getter，尝试直接获取属性值
                            this.setValue(metaParam, keyProperties[0], values.get(0));
                        }
                    } else {
                        // 多列主键的情况，依次从主键对象中获取对应的属性记录到用户参数对象中
                        this.handleMultipleProperties(keyProperties, metaParam, metaResult);
                    }
                }
            }
        }
    } catch (ExecutorException e) {
        throw e;
    } catch (Exception e) {
        throw new ExecutorException("Error selecting key or setting result to parameter object. Cause: " + e, e);
    }
}
```

方法会执行 `<selectKey/>` 中定义的 SQL 语句，拿到具体的返回值作为主键对象，并依据配置的 keyProperty 属性，将相应的主键值映射到用户指定的参数对象中。

### 二. SQL 语句封装：SqlNode 和 SqlSource

上面分析的过程中我们曾遇到 SqlNode 和 SqlSource 这两个接口，本小节我们对这两个接口的及其具体实现类做一个分析，在这之前我们需要简单了解一下这两个类各自的作用，由前面的分析我们应该知道对于一个 SQL 语句标签最后会被封装成为一个 MappedStatement 对象，而标签中定义的 SQL 语句则由 SqlSource 进行表示，SqlNode 则用来定义动态 SQL 节点和文本节点等。

由点及面，我们先来看一下 SqlNode 的相关实现，SqlNode 是一个接口，其中仅包含了一个 apply 方法，接口定义如下：

```java
public interface SqlNode {
    /** 基于传递的实参，解析动态 SQL 节点 */
    boolean apply(DynamicContext context);
}
```

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/images/2017/mybatis-sqlnode.png?raw=false)

上图为 SqlNode 的类继承关系图，下面我们逐个来分析其实现，首先来看一下前面多次遇到的 MixedSqlNode，该类型通过一个 `List<SqlNode>` 集合记录包含的 SqlNode 对象，其 apply 方法会遍历该集合并应用记录的各个 SqlNode 对象的 apply 方法，实现比较简单。与 MixedSqlNode 实现类似的还包括 StaticTextSqlNode 类，该类采用一个 String 类型的常量记录非动态的 SQL 节点，其 apply 方法则直接调用 DynamicContext 对象的 appendSql 方法将记录的 SQL 节点添加到一个 StringBuilder 类型属性中（该属性用于记录 SQL 语句片段，当我们最后调用 DynamicContext 对象的 getSql 方法时会调用该属性的 toString 方法拼接记录的 SQL 片段，返回最终完整的 SQL 语句）。

TextSqlNode 用于封装包含占位符 “${}” 的动态 SQL 节点，前面在分析 SQL 语句标签时也曾遇到，该类的 apply 方法实现如下：

```java
public boolean apply(DynamicContext context) {
    // BindingTokenParser 是内部类，基于 DynamicContext 对象的 bindings 中的属性解析 SQL 语句中的占位符
    GenericTokenParser parser = this.createParser(new BindingTokenParser(context, injectionFilter));
    // 解析并添加 SQL 片段到 DynamicContext 中
    context.appendSql(parser.parse(text));
    return true;
}
```

GenericTokenParser 的执行逻辑我们之前遇到过多次，应该比较清楚了，它主要用来查找指定标识的占位符（这里是 “${}”），并基于指定的 TokenHandler 对解析到的占位符变量进行处理。TextSqlNode 实现了内部的 TokenHandler，即 BindingTokenParser，该解析器会基于参数对象 DynamicContext 的属性 bindings 中记录的参数值解析 SQL 语句中的占位符，并将解析结果记录到 DynamicContext 对象中。

VarDeclSqlNode 对应动态 SQL 中的 `<bind/>` 节点，该节点可以从 OGNL 表达式中创建一个变量并将其绑定到上下文中，官方文档中关于该节点的使用示例如下：

```xml
<select id="selectBlogsLike" resultType="Blog">
    <bind name="pattern" value="'%' + _parameter.getTitle() + '%'" />
    SELECT * FROM BLOG WHERE title LIKE #{pattern}
</select>
```

VarDeclSqlNode 同样定义了 name 和 expression 两个属性，分别与 `<bind/>` 标签的属性对应，该实现类的 apply 方法完成了对 OGNL 表达式的解析，并将解析得到的真实值记录到上下文 bindings 属性中：

```java
public boolean apply(DynamicContext context) {
    // 解析 OGNL 表达式对应的值
    final Object value = OgnlCache.getValue(expression, context.getBindings());
    // 绑定到上下文中，name 对应属性 <bind/> 标签的 name 属性配置
    context.bind(name, value);
    return true;
}
```

IfSqlNode 对应动态 SQL 的 `<if/>` 节点，这也是我们频繁使用的条件节点，IfSqlNode 的属性定义如下：

```java
/** 用于解析 <if/> 节点的 test 表达式 */
private final ExpressionEvaluator evaluator;
/** 记录 <if/> 节点中的 test 表达式 */
private final String test;
/** 记录 <if/> 节点的子节点 */
private final SqlNode contents;
```

相应的 apply 实现会首先调用 `ExpressionEvaluator#evaluateBoolean` 方法判定 test 表达式是否为 true，如果为 true 则应用记录的子节点的 apply 方法：

```java
public boolean apply(DynamicContext context) {
    // 检测 test 表达式是否为 true
    if (evaluator.evaluateBoolean(test, context.getBindings())) {
        // 执行子节点的 apply 方法
        contents.apply(context);
        return true;
    }
    return false;
}

// org.apache.ibatis.scripting.xmltags.ExpressionEvaluator#evaluateBoolean
public boolean evaluateBoolean(String expression, Object parameterObject) {
    // 获取 OGNL 表达式对应的值
    Object value = OgnlCache.getValue(expression, parameterObject);
    // 转换为 boolean 类型返回
    if (value instanceof Boolean) {
        return (Boolean) value;
    }
    if (value instanceof Number) {
        return !new BigDecimal(String.valueOf(value)).equals(BigDecimal.ZERO);
    }
    return value != null;
}
```

ChooseSqlNode 对应动态 SQL 中的 `<choose/>` 节点，我们通常利用此节点配合 `<when/>` 和 `<otherwise/>` 节点来实现 switch 功能，具体使用方式可以参考官方示例。在代码层面并没有 WhenSqlNode 和 OtherwiseSqlNode 与另外两个标签相对应，MyBatis 采用 IfSqlNode 表示 `<when/>` 节点，采用 MixedSqlNode 表示 `<otherwise/>` 节点，ChooseSqlNode 类的属性和 apply 方法定义如下：

```java
/** 对应 <otherwise/> 节点，采用 {@link MixedSqlNode} 表示 */
private final SqlNode defaultSqlNode;
/** 对应 <when/> 节点，采用 {@link IfSqlNode} 表示 */
private final List<SqlNode> ifSqlNodes;

public boolean apply(DynamicContext context) {
    // 遍历应用 <when/> 节点，一旦成功一个就返回
    for (SqlNode sqlNode : ifSqlNodes) {
        if (sqlNode.apply(context)) {
            return true;
        }
    }
    // 所有的 <when/> 都不满足，执行 <otherwise/> 节点
    if (defaultSqlNode != null) {
        defaultSqlNode.apply(context);
        return true;
    }
    return false;
}
```

TrimSqlNode 对应 `<trim/>` 节点，用于处理动态 SQL 拼接在一些条件下出现不完整 SQL 的情况，具体使用可以参考官方示例，该实现类的属性和 applay 方法定义如下：

```java
/** 记录 <trim/> 节点的子节点 */
private final SqlNode contents;
/** 期望追加的前缀字符串 */
private final String prefix;
/** 期望追加的后缀字符串 */
private final String suffix;
/** 如果 <trim/> 包裹的 SQL 语句为空，则删除指定前缀 */
private final List<String> prefixesToOverride;
/** 如果 <trim/> 包裹的 SQL 语句为空，则删除指定后缀 */
private final List<String> suffixesToOverride;

public boolean apply(DynamicContext context) {
    // 创建 FilteredDynamicContext 对象，封装上下文
    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
    // 应用子节点的 apply 方法
    boolean result = contents.apply(filteredDynamicContext);
    // 处理前缀和后缀
    filteredDynamicContext.applyAll();
    return result;
}
```

TrimSqlNode 中定义了内部类 FilteredDynamicContext，它是对上下文对象 DynamicContext 的封装，其 applyAll 方法实现了对不完整 SQL 的处理，该方法调用 applyPrefix 和 applySuffix 方法分别处理 SQL 的前缀和后缀，并将处理完后的 SQL 片段记录到上下文对象中：

```java
public void applyAll() {
    sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
    // 全部转换成大写
    String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
    if (trimmedUppercaseSql.length() > 0) {
        // 处理前缀
        this.applyPrefix(sqlBuffer, trimmedUppercaseSql);
        // 处理后缀
        this.applySuffix(sqlBuffer, trimmedUppercaseSql);
    }
    // 添加解析后的结果到 delegate 中
    delegate.appendSql(sqlBuffer.toString());
}
```

applyPrefix 和 applySuffix 方法的实现思路相同，这里以 applyPrefix 方法为例，该方法会遍历指定的前缀并判断当前 SQL 片段是否以包含的前缀开头，是的话则会删除该前缀，如果指定了 prefix 属性则会在 SQL 语句片段前面追加对应的前缀值。WhereSqlNode 和 SetSqlNode 均由 TrimSqlNode 派生而来，实现比较简单，不再多做撰述。

最后再来看一下 ForEachSqlNode 类，该类对应 `<foreach/>` 节点，前面我们曾列举了相关的 ForEachHandler 类实现，ForEachSqlNode 类是所有 SqlNode 实现类中最复杂的一个，其主要的属性定义如下（建议参考官方文档进行理解）：

```java
/** 标识符 */
public static final String ITEM_PREFIX = "__frch_";
/** 用于判断循环的终止条件 */
private final ExpressionEvaluator evaluator;
/** 迭代的集合表达式 */
private final String collectionExpression;
/** 记录子节点 */
private final SqlNode contents;
/** open 标识 */
private final String open;
/** close 标识 */
private final String close;
/** 循环过程中，各项之间的分隔符 */
private final String separator;
/** index 是迭代的次数，item 是当前迭代的元素 */
private final String item;
private final String index;
```

ForEachSqlNode 中定义了两个内部类：FilteredDynamicContext 和 PrefixedContext。FilteredDynamicContext 由 DynamicContext 派生而来，其中稍复杂的实现是 appendSql 方法：

```java
public void appendSql(String sql) {
    GenericTokenParser parser = new GenericTokenParser("#{", "}", new TokenHandler() {
        @Override
        public String handleToken(String content) {
            // 替换 item，为 __frch_item_index
            String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
            if (itemIndex != null && newContent.equals(content)) {
                // 替换 itemIndex 为 __frch_itemIndex_index
                newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
            }
            // 追加 #{}
            return new StringBuilder("#{").append(newContent).append("}").toString();
        }
    });

    delegate.appendSql(parser.parse(sql));
}
```

实际上这里还是之前多次碰到的 GenericTokenParser 解析占位符的套路（这里的占位符是指 “#{}”），只不过这里的 TokenHandler 由匿名内部类实现，它的 handleToken 方法会将对应的 item 替换成 `__frch_item_index` 的形式，拼接的过程由 itemizeItem 方法实现:

```java
private static String itemizeItem(String item, int i) {
    // 返回 __frch_item_i 的形式
    return new StringBuilder(ITEM_PREFIX).append(item).append("_").append(i).toString();
}
```

PrefixedContext 也派生自 DynamicContext 类，在遍历集合拼接时主要用来封装一个由指定前缀和集合元素组成的基本元组，具体实现比较简单。回到 ForEachSqlNode 类本身，我们继续来看 apply 方法实现：

```java
public boolean apply(DynamicContext context) {
    Map<String, Object> bindings = context.getBindings();
    // 解析集合 OGNL 表达式对应的值，返回值对应的迭代器
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    if (!iterable.iterator().hasNext()) {
        return true;
    }
    boolean first = true;
    // 添加 open 前缀标识
    this.applyOpen(context);
    int i = 0;
    // 迭代处理集合
    for (Object o : iterable) {
        DynamicContext oldContext = context;  // 备份一下上下文对象
        if (first || separator == null) {
            // 第一次遍历，或未指定分隔符
            context = new PrefixedContext(context, "");
        } else {
            // 其它情况
            context = new PrefixedContext(context, separator);
        }
        int uniqueNumber = context.getUniqueNumber();
        if (o instanceof Map.Entry) {
            // 如果是 Map 类型，将 key 和 value 记录到 DynamicContext.bindings 中
            @SuppressWarnings("unchecked")
            Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
            this.applyIndex(context, mapEntry.getKey(), uniqueNumber);
            this.applyItem(context, mapEntry.getValue(), uniqueNumber);
        } else {
            // 将当前索引值和元素记录到 DynamicContext.bindings 中
            this.applyIndex(context, i, uniqueNumber);
            this.applyItem(context, o, uniqueNumber);
        }
        // 应用子节点的 apply 方法
        contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
        if (first) {
            first = !((PrefixedContext) context).isPrefixApplied();
        }
        context = oldContext; // 恢复上下文对象
        i++;
    }
    // 添加 close 后缀标识
    this.applyClose(context);
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
}
```

整个过程阅读起来没什么压力，但就是不知道具体在做什么事情，这里我们以批量查询用户表 t\_user 中多个用户为例来走一遍上述方法的执行过程，对应的动态查询语句定义如下：

```xml
<select id="selectByIds" parameterType="java.util.List" resultMap="BaseResultMap">
    SELECT * FROM t_user WHERE id IN
    <foreach collection="ids" index="idx" item="itm" open="(" close=")" separator=",">
        #{itm}
    </foreach>
</select>
```

假设我们现在希望查询 id 为 1 和 2 的两个用户，执行流程可以表述如下：

> 1. 解析获取到集合表达式对应的集合迭代器对象，这里对应的是一个 List 类型集合的迭代器，其中包含了 1 和 2 两个元素
> 2. 调用 applyOpen 方法添加 OPEN 标识符，这里即 “(”
> 3. 进入 for 循环，因为是第一次遍历，所以会创建 prefix 参数为空字符串的 PrefixedContext 对象
> 4. 这里集合类型中封装的是 Long 类型（不是 Map 类型），
>> - 调用 applyIndex 方法，记录键值对 (idx, 0) 和 (\_\_frch\_idx\_0, 0) 到 DynamicContext#bindings 中
>> - 调用 applyItem 方法，记录键值对 (itm, 1) 和 (\_\_frch\_itm\_0, 1) 到 DynamicContext#bindings 中
> 5. 应用子节点的 apply 方法，这里会触发 FilteredDynamicContext#appendSql 方法解析占位符 ‘#{itm}’ 为 ‘#{\_\_frch\_itm\_0}’，此时生成的 SQL 语句片段已然成为 `SELECT * FROM t_user WHERE id IN ( #{__frch_itm_0}`
> 6. 进入 for 循环的第二次遍历，此时 first 变量已经置为 false，且这里设置了分隔符，所以执行 `new PrefixedContext(context, separator)` 来创建上下文对象
> 7. 这里集合类型同样是 Long 类型（不是 Map 类型），
>> - 调用 applyIndex 方法，记录键值对 (idx, 1) 和 (\_\_frch\_idx\_1, 1) 到 DynamicContext#bindings 中
>> - 调用 applyItem 方法，记录键值对 (itm, 2) 和 (\_\_frch\_itm\_1, 2) 到 DynamicContext#bindings 中
> 8. 应用子节点的 apply 方法，这里会触发 FilteredDynamicContext#appendSql 方法解析占位符 ‘#{itm}’ 为 ‘#{\_\_frch\_itm\_1}’，此时生成的 SQL 语句片段已然成为 `SELECT * FROM t_user WHERE id IN ( #{__frch_itm_0}, #{__frch_itm_1}`
> 9. for 循环结束，调用 applyClose 追加 CLOSE 标识符，这里即 “)”

最后解析得到的 SQL 为 `SELECT * FROM t_user WHERE id IN ( #{__frch_itm_0} , #{__frch_itm_1} )`，希望通过这样一个过程来辅助您的理解，如果还是云里雾里可以 debug 一下整个过程。

到这里我们就对 SqlNode 接口及其实现类做了一个完整的介绍，下面来看一下 SqlSource 的实现，前面介绍了 SqlSource 用于表示映射文件或注解定义的 SQL 语句标签中的 SQL 语句，但是这里的 SQL 语句并不是可执行的，其中可能包含一些动态占位符，SqlSource 接口的定义如下：

```java
public interface SqlSource {
    /**
     * 基于传入的参数返回可执行的 SQL
     *
     * @param parameterObject 用户传递的实参
     * @return
     */
    BoundSql getBoundSql(Object parameterObject);
}
```

![image](https://github.com/procyon-lotor/procyon-lotor.github.io/blob/master/images/2017/mybatis-sqlsource.png?raw=false)

上述类继承关系图描述了 SqlSource 及其实现类，其中 RawSqlSource 用于封装静态定义的 SQL 语句，DynamicSqlSource 用于封装动态定义的 SQL 语句，ProviderSqlSource 则用于封装注解形式定义的 SQL 语句，不管是动态还是静态的 SQL 语句，经过处理之后都会封装成为 StaticSqlSource 对象，其中包含的 SQL 语句是可以直接执行的。考虑到 MyBatis 目前的使用方式还是配置优先，所以不打算对 ProviderSqlSource 进行展开说明，在开始探究剩余 3 个实现类之前，需要先对这几个类共享的一个核心组件 SqlSourceBuilder 进行分析，SqlSourceBuilder 继承自 BaseBuilder，主要用于解析前面经过 SqlNode 的 apply 方法处理的 SQL 语句中的占位符属性，同时将占位符替换成 “？” 字符串。

SqlSourceBuilder 中仅定义了一个 parse 方法，实现了对占位符 “#{}” 中属性的解析，并将占位符替换成 “？”，最终将解析得到的 SQL 语句和相关参数封装成 StaticSqlSource 对象返回，该方法的实现如下：

```java
/**
 * @param originalSql 经过 SqlNode#apply(DynamicContext) 方法处理之后的 SQL 语句
 * @param parameterType 用户传递的实参类型
 * @param additionalParameters 记录形参与实参之间的对应关系，即 SqlNode#apply(DynamicContext) 方法处理之后记录在 DynamicContext#bindings 中的键值对
 */
public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    // 创建 ParameterMappingTokenHandler 对象，用于解析 ‘#{}’ 占位符
    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    String sql = parser.parse(originalSql); // SELECT * FROM t_user WHERE id IN ( ? , ? )
    // 构造 StaticSqlSource 对象，其中封装了被替换成 ‘？’ 的 SQL 语句以及参数对应的 ParameterMapping 集合
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
}
```

该方法的实现还是我们熟悉的套路，获取指定占位符中的属性，然后交由对应的 TokenHandler 进行处理，SqlSourceBuilder 定义了内部类 ParameterMappingTokenHandler，该内部类是一个具体的 TokenHandler 实现，同时还继承了 BaseBuilder 抽象类，该实现类的 handleToken 方法定义如下;

```java
public String handleToken(String content) { // content 为占位符中定义的属性，例如 __frch_itm_0
    // 调用 buildParameterMapping 方法构造当前 content 对应的 ParameterMapping 对象，并记录到 parameterMappings 集合中
    // ParameterMapping{property='__frch_itm_0', mode=IN, javaType=class java.lang.Long, jdbcType=null, numericScale=null, resultMapId='null', jdbcTypeName='null', expression='null'}
    parameterMappings.add(this.buildParameterMapping(content));
    return "?"; // 全部返回 “？” 字符串
}
```

该方法会调用 buildParameterMapping 方法构造传递的 content （占位符中的属性） 对应的 ParameterMapping 对象，并记录到 parameterMappings 集合中，同时返回 “?” 占位符将原始 SQL 中对应的占位符全部替换成 “?”，buildParameterMapping 的实现不再展开。这里我们以前面 SqlNode 对象的 apply 解析得到的 `SELECT * FROM t_user WHERE id IN ( #{__frch_itm_0} , #{__frch_itm_1} )` 为例，经过 SqlSourceBuilder 的 parse 方法处理之后，该 SQL 语句会被解析成为 `SELECT * FROM t_user WHERE id IN ( ? , ? )` 的形式封装到 StaticSqlSource 对象中，对应 parameterMappings 参数内容如下：

```
ParameterMapping{property='__frch_itm_0', mode=IN, javaType=class java.lang.Long, jdbcType=null, numericScale=null, resultMapId='null', jdbcTypeName='null', expression='null'}
ParameterMapping{property='__frch_itm_1', mode=IN, javaType=class java.lang.Long, jdbcType=null, numericScale=null, resultMapId='null', jdbcTypeName='null', expression='null'}
```

了解了 SqlSourceBuilder 的作用，我们回头来看 DynamicSqlSource 的实现会比较容易，DynamicSqlSource 实现了 SqlSource 接口中声明的方法 getBoundSql，如下：

```java
public BoundSql getBoundSql(Object parameterObject) {
    // 构造上下文对象
    DynamicContext context = new DynamicContext(configuration, parameterObject);

    // 应用 apply 方法（树型结构，会遍历应用树中各个节点的 apply 方法），各司其职追加 SQL 片段到 context 中
    rootSqlNode.apply(context);

    // 创建 SqlSourceBuilder 对象，解析占位符属性，并将 SQL 语句中的 ‘#{}’ 占位符替换成 ‘？’
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass(); // 解析用户实参类型
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings()); // StaticSqlSource 封装的解析结果

    // 基于 SqlSourceBuilder 解析结果和实参创建 BoundSql 对象
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    // 将 DynamicContext.bindings 中的参数信息复制到 BoundSql 对象的 additionalParameters 属性中
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
        boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    return boundSql;
}
```

DynamicSqlSource 的 getBoundSql 方法最终会将解析得到的 SQL 语句，以及相应的参数全部封装到 BoundSql 对象中返回，具体过程可以参考上述代码注释。相对于 DynamicSqlSource 来说，RawSqlSource 的 getBoundSql 实现就要简单了许多，它的实现直接委托给了 StaticSqlSource 处理，本质上就是基于用户传递的参数来构造 BoundSql 对象。对应 SQL 的解析则放置在构造方法中，在构造方法中会调用 getSql 方法获取对应的 SQL 定义，同样基于 SqlSourceBuilder 进行解析对原始 SQL 语句进行解析，封装成 StaticSqlSource 对象记录到属性中，在实际运行时只要填充参数即可。这也是很容易理解的，毕竟对于静态 SQL 来说，它的模式在整个应用程序运行过程中是不变的，所以在系统初始化时完成解析操作，后续可以直接拿来使用，但是对于动态 SQL 来说，SQL 语句的具体模式取决于用户传递的参数，需要在运行时实时解析和执行。

### 三. Mapper 接口绑定与后置处理

饶了一大圈，看起来我们似乎完成映射文件的加载和解析工作，实际上我们确实完成了对映射文件的解析，但是光解析还是不够的，实际开发中我们对于这些定义在映射文件中的 SQL 的调用一般都是间接通过 Mapper 接口完成，所以还需要建立映射文件与具体 Mapper 接口之间的映射，这一过程位于 `XMLMapperBuilder#bindMapperForNamespace` 方法中：

```java
private void bindMapperForNamespace() {
    // 获取当前映射文件的 namespace 配置
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
        Class<?> boundType = null;
        try {
            // 获取 namespace 的类型
            boundType = Resources.classForName(namespace);
        } catch (ClassNotFoundException e) {
            //ignore, bound type is not required
        }
        if (boundType != null) {
            // 当前 boundType 还未加载
            if (!configuration.hasMapper(boundType)) {
                // 记录当前已经加载的 namespace 标识到 Configuration.loadedResources 属性中
                configuration.addLoadedResource("namespace:" + namespace);
                // 注册对应的 Mapper 接口到 Configuration.mapperRegistry 属性中（对应 MapperRegistry）
                configuration.addMapper(boundType);
            }
        }
    }
}
```

bindMapperForNamespace 首先会获取对应映射文件的命名空间，然后构造命名空间字面量对应的 Class 类型，并记录到 Configuration 对象相应的属性中，这里本质上调用的是 `MapperRegistry#addMapper` 方法执行注册逻辑，MapperRegistry 的实现之前已经分析过，这里就不再重复说明。

在前面分析解析过程时，对于一些解析异常的标签会记录到 Configuration 对象的相应属性中（如下面代码块所示，需要说明的是这些记录的标签不一定全是解析异常所致，有些标签的解析存在依赖关系，如果 A 依赖于 B，在解析 A 时 B 还未被解析，MyBatis 则会将标签 A 记录起来，等到最后再尝试解析，这一点不同于 Spring 的标签解析过程），包括 SQL 语句标签、`<resultMap/>`，以及 `<cache-ref/>`，在映射文件解析过程的最后会再次尝试对这些标签进行解析（见上述代码），如果再度解析失败那就只能忽略了。这些再次触发解析的方法在实现上都是一个思路，就是从 Configuration 对象中获取解析失败的标签对象集合，然后遍历执行相应的解析方法，前面已经对这些标签的解析过程进行了分析，不再重复。

```java
// 处理解析失败的 <resultMap/> 节点
this.parsePendingResultMaps();
// 处理解析失败的 <cache-ref/> 节点
this.parsePendingCacheRefs();
// 处理解析失败的 SQL 语句节点
this.parsePendingStatements();
```

到这里我们算是真正完成了对映射配置的解析，也基本上完成了 MyBatis 框架的初始化过程，接下来我们可以创建会话并执行具体的数据库操作。在下一篇中我们将一起来分析 MyBatis 执行 SQL 的具体过程实现，包括获取 SQL 语句、绑定参数、执行数据库操作，以及结果集映射等操作，敬请期待吧～
