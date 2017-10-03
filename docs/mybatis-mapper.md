前面有提及过 MyBatis 中的配置文件主要分为 __配置文件__ 和 __映射文件__ 两大类，上一篇中我们详细探究了配置文件的加载和解析过程，在本篇中我们将一起来探究映射文件的加载与解析过程。在探究配置文件解析 <mappers/> 节点时，我们层触及到 XMLMapperBuilder 的 parse 方法，这也真是解析映射文件的入口：

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

方法首先会判断映射文件是否被解析过，对于没有被解析过的文件则会调用 configurationElement 方法解析所有配置项，并注册当前映射文件关联的 Mapper 接口，对于解析过程中处理异常的节点，方法会记录到 Configuration 对象相应的集合中，并在方法最后统一处理。整个方法中 configurationElement 包含了解析的核心步骤，与配置文件解析的实现方式一样，这也是一个调度方法：

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

每个映射文件都关联一个具体的 Mapper 接口，而 <mapper/> 节点的 namespace 属性则用于指定对应的 Mapper 接口限定名。方法首先会获取 namespace 属性，然后调用相应方法对每个子标签逐一解析。

### 映射文件的解析

下面来对各个节点的解析过程进行探究，考虑到 <parameterMap/> 已废弃，所以不再对其多做说明。

#### 解析 <cache/> 配置

MyBatis 在设计上分为一级缓存和二级缓存，这里的配置用于对二级缓存进行配置。在具体解析缓存设置标签之前，我们需要对 MyBatis 的缓存类设计有一个认知，不然可能会云里雾里。MyBatis 的缓存类设计还是非常巧妙的，不管是一级缓存还是二级缓存，都对应一个 Cache 抽象接口：

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

Cache 中声明的缓存操作接口中规中矩，围绕该接口 MyBatis 提供了基于 HashMap 的基本实现 PerpetualCache，该实现类的各项方法实现都是对 HashMap 方法的封装，比较简单。在整个缓存类设计方面，MyBatis 的作者使用了典型的装饰器设计模式，从而为缓存对象增加不同的特性，这些装饰器包括（中文翻译可能不够准确）：

> - BlockingCache：阻塞缓存装饰器
> - FifoCache：先进先出缓存装饰器
> - LruCache：近期最少使用缓存装饰器
> - LoggingCache：日志功能缓存装饰器
> - ScheduledCache：周期性清理缓存装饰器
> - SerializedCache：序列化支持缓存装饰器
> - SoftCache：软引用缓存装饰器
> - WeakCache：弱引用缓存装饰器
> - SynchronizedCache：同步缓存装饰器

BlockingCache 采用一个 ConcurrentHashMap 对象记录每个 key 对应的可重入锁对象，当执行 getObject 操作时会尝试获取 key 对应的锁对象，并尝试带有超时机制的加锁操作，再获取到缓存值之后会释放锁。

FifoCache 采用一个双端队列来记录 key 进入缓存的顺序，队列的大小默认是 1024，当执行 putObject 操作时，如果当前缓存的对象数超过缓存大小，则会触发 FIFO 策略。

LruCache 通过一个 LinkedHashMap 类型的 keyMap 属性记录缓存中每个 key 的使用情况，并使用一个 eldestKey 对象记录当前最少被使用的 key，当缓存达到容量上限时将会移除使用频率最小的缓存项。

LoggingCache 并不是如其字面意思是对缓存增加日志记录功能，该缓存装饰器中增加了两个属性 requests 和 hits，分别用于记录缓存被访问的次数和缓存命中的次数，并提供了 getHitRatio 方法，以获取当前缓存的命中率。

ScheduledCache 用于定期对缓存进行执行 clear 操作，其中定义了两个属性 clearInterval 和 lastClear 分别用来记录执行清理的时间间隔（默认为 1 小时）和最近一次执行清理的时间戳，在每次操作缓存时都会触发对缓存当前清理状态的检查，如果间隔时间达到设置值，就会触发对缓存的清理操作。

SerializedCache 缓存装饰器会对缓存值进行序列化处理后再进行缓存，当我们执行 putObject 操作时，该装饰器会基于 java 的序列化机制对缓存值进行序列化（序列化结果存储在内存），反之，当我们执行 getObject 操作时，如果对应的缓存值存在，则会对该值执行反序列化再返回。

SoftCache 和 WeakCache 在实现上流程上几乎相同，这两个缓存装饰器都会通过相应的 Entry 内部类对缓存值进行修饰，区别在于前者使用的是软引用，后者使用的是弱引用，也就是说这两类缓存所缓存的对象的生命周期受垃圾回收器 GC 操作的影响。

SynchronizedCache 缓存装饰在相应的缓存对象前都增加了 synchronized 关键字修饰，类似于 HashTable 的实现方式。

介绍完了缓存类的基本设计，我们再回过头来继续探究 <cache/> 标签的解析过程，该过程位于 cacheElement 方法中：

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

上述方法首先会获取 <cache/> 标签的相关属性配置，然后调用 MapperBuilderAssistant 的 useNewCache 方法创建对应的缓存对象，并记录到 Configuration 的 caches 属性中。useNewCache 方法中使用了缓存对象构造器 CacheBuilder 创建缓存对象，一起来看一下 build 方法实现：

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

方法首先会判断是否指定了缓存实现类，否则设置默认的缓存实现，即以 PerpetualCache 作为默认实现，以 LruCache 作为默认缓存装饰器，然后选择 String 类型参数的构造方法构造缓存对象，并基于配置对缓存对象进行初始化，最后对依据缓存实现注入相应的缓存装饰器。setCacheProperties 方法中除了设置相应配置属性外，还会判断缓存类是否实现了 InitializingObject 接口，以决定是否调用 initialize 初始化方法。setStandardDecorators 方法中会基于当前的配置为缓存对象注入相应的缓存装饰器，实现如下：

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

#### 解析 <cache-ref/> 配置

<cache-ref/> 标签用于引用其它命名空间中定义的缓存对象，从而能够让一个缓存对象在多个命名空间之间共享，该标签的解析位于 cacheRefElement 方法中：

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

方法首先会在 Configuration#cacheRefMap 中记录一下当前的引用关系，其中 key 是 <cache-ref/> 所在的 namespace，而 value 是引用的缓存对象所在的 namespace，然后从 Configuration#caches 属性中获取引用的缓存对象，在介绍 <cache/> 标签的解析实现时，我们曾提及到最终解析构造的缓存对象会记录到 Configuration 的 caches 属性中，这里则是一个逆过程。

#### 解析 <resultMap/> 配置

<resultMap/> 标签用于建立结果集为 java bean 属性之间的映射关系，这是一个非常有用和高效的配置，如果是纯 JDBC 开发，甚至包括我司自研的 ORM 框架，在处理结果集与 java bean 之间的映射时，还需要手动硬编码注入，对于一张字段较多的表来说，简直写到手抽筋，而 <resultMap/> 配置配合 mybatis-generator 工具的逆向工程则可以解放我们的双手。下面是一个典型的配置，用于建立数据表 t\_user 与 User 实体之间的属性映射关系：

```xml
<resultMap id="BaseResultMap" type="org.zhenchao.mybatis.entity.User">
    <id column="id" jdbcType="BIGINT" property="id" />
    <result column="username" jdbcType="VARCHAR" property="username" />
    <result column="password" jdbcType="CHAR" property="password" />
    <result column="age" jdbcType="INTEGER" property="age" />
    <result column="phone" jdbcType="VARCHAR" property="phone" />
    <result column="email" jdbcType="VARCHAR" property="email" />
</resultMap>
```

在具体介绍 <resultMap/> 标签的解析过程之前，我们需要对该标签涉及到的两个主要的类 ResultMapping 和 ResultMap 进行一个了解，前者用于封装除 <discriminator/> 以外的具体的一子节点配置，而后者则是对整个 <resultMap/> 节点的封装。

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

ResultMap 的中定义的属性如上述代码注释，与 ResultMapping 一样，也是通过内置 Builder 内部构造器类来构造 ResultMap 对象，构造器的实现比较简单，不在此列出。

了解了内部数据结构 ResultMapping 和 ResultMap 的定义以及它们之间的相互关系，接下来一起探究一下 <resultMap/> 标签的解析过程，具体实现位于 resultMapElement 方法中：

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

一个 <resultMap/> 标签包含如下 4 个属性配置，解析过程首先尝试获取标签的 id 标识，如果没有则会调用 `XNode#getValueBasedIdentifier`
基于规则随机生成一个，接着获取 type 属性，指明当前标签所关联的 java bean，支持 type、ofType、resultType，以及 javaType 属性配置，然后获取 extends 属性，用于指定当前标签的继承关系，最后就是获取 autoMapping 属性，这是 boolean 的属性配置项，如果为 true 则表示开启自动映射功能，会自动查找与数据表列名相同的属性名，并调用 setter 方法进行注入，否则就需要在 <resultMap/> 标签中手动指明列名与属性名之间的映射关系。

```xml
<resultMap id="" type="" extends="" autoMapping=""/>
```

获取完对应的属性配置之后，接下来将遍历处理每个子标签配置，<resultMap/> 的子标签包含 <constructor/>、<id/>、<result/>、<association/>、<collection/>、<discriminator/> 五种，具体标签的作用可以参阅官方文档，下面来一起探究一下各个子标签的解析细节：

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

<constructor/> 标签没有属性配置，所以方法直接遍历处理标签的所有子标签，即 idArg  和 arg，并调用 buildResultMappingFromContext 方法创建 ResultMapping：

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

方法首先会获取标签所有的属性配置项，并基于 TypeAliasRegistry  对属性所表示的类型进行解析，最后调用 `MapperBuilderAssistant#buildResultMapping` 方法构造封装配置项的 ResultMapping 对象，这里本质上还是调用 ResultMapping 的构造器进行构造。buildResultMappingFromContext 方法是一个通用方法，除了上面用于封装 <constructor/> 子标签，对于上层标签 <id/>, <result/>, <association/>, <collection/> 来说也都是直接调用该方法进行解析。

剩下就只有 <discriminator/>，这个标签不是直接基于 ResultMapping 对象进行封装，而是采用 Discriminator 类对 ResultMapping 进行了封装，这主要取决于该标签的用途。

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

上述方法用于解析 <discriminator/> 标签配置，具体的步骤与其它标签的解析过程如出一辙，可以参考代码注释。

在将这五类子标签解析成为相应对象，并记录到 resultMappings 集合中之后，下一步就是基于这些解析得到的配置构造 ResultMapResolver 解析器对象，调用 `ResultMapResolver#resolve` 方法对配置进行解析，创建 ResultMap 对象，并记录到 Configuration 独享的 resultMaps 属性中。ResultMapResolver 的 resolve 方法并没有太多逻辑，而是直接调用了 `MapperBuilderAssistant#addResultMap` 方法：

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

#### 解析 <sql/> 配置

在 MyBatis 中，我们可以通过 <sql/> 节点配置一些可以被复用的 SQL 语句片段，当我们在某个 SQL 中需要使用这些片段时，可以通过 <include/> 子标签进行引入，具体可以参考官方文档示例。对于该节点的解析由 sqlElement 方法实现，并最终记录到 Configuration.sqlFragments 集合中，方法实现如下：

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

方法首先会获取该标签的属性配置，即 id 和 databaseId，并对 id 进行格式化处理，然后会判断当前 <sql/> 配置的 databaseId 是否与当前运行的数据库环境相匹配，对于不匹配的 <sql/> 节点则选择忽略。requiredDatabaseId 参数在重载方法中指定，可以看到就是从全局配置对象中获取的 databaseId 值：

```java
private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
        // 获取当前运行数据库环境对应的 databaseId
        this.sqlElement(list, configuration.getDatabaseId());
    }
    this.sqlElement(list, null);
}
```

最终这些解析得到的 <sql/> 节点会被记录到 Configuration 对象的 sqlFragments 属性中（在构造 XMLMapperBuilder 对象时进行初始化）。

#### 解析 <select/>, <insert/>, <update/>, <delete/> 配置

<select/>、<insert/>、<update/>，以及 <delete/> 4 个标签用于配置映射文件中最核心的数据库操作语句（下文统称这 4 个标签为 SQL 语句标签），包括静态 SQL 和动态 SQL。MyBatis 通过 MappedStatement 封装这些 SQL 语句标签的配置，并调用 `XMLStatementBuilder#parseStatementNode` 方法对配置进行解析，构建 MappedStatement 对象并记录到 Configuration 对象的 mappedStatements 属性中。XMLMapperBuilder 的 buildStatementFromContext 方法对于标签的解析主要做了一些统筹调度的工作，具体解析还是交由 XMLStatementBuilder 进行处理，buildStatementFromContext 的实现如下：

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

上述代码的实现比较简单，无非是遍历获取到的所有 SQL 语句标签，然后创建 XMLStatementBuilder 对象，并调用 parseStatementNode 方法对各个 SQL 语句标签进解析，对于解析异常的标签则会记录到 Configuration 对象的 incompleteStatements 属性中，后续会针对性处理，我们来探究一下 XMLStatementBuilder 的 parseStatementNode 的实现细节：

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

    // 获取 <selectKey/> 对应的 SelectKeyGenerator
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    if (configuration.hasKeyGenerator(keyStatementId)) {
        keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
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

方法首先会获取标签的 id 和 databaseId 属性，并判断当前 SQL 语句标签是否适用于当前的数据库环境，对于不适用的 SQL 语句标签则直接忽略。然后会获取标签的属性配置，并对配置的类型字面值进行解析，这些配置的含义代码中已有注释，如果希望更进一步了解各项配置的意义可以参考官方文档。接着会解析 <include/> 子节点和 <selectKey/> 子节点配置，这两个节点的具体解析过程稍后进行详细说明。最后会对 SQL 语句的具体配置进行解析，并封装成 MappedStatement 对象记录到 Configuration 对象的 mappedStatements 属性中，在这个过程中会调用 `LanguageDriver#createSqlSource` 创建 SQL 语句标签对应的 SqlSource 对象，SqlSource 用于封装 SQL 语句标签（或 Mapper 接口方法注解）中配置的 SQL 语句，但是这里的 SQL 并不是最终可以被数据库执行的 SQL，其中可能包含占位符，关于 SqlSource 对象暂时先了解其大概作用即可，后面会对其实现做详细说明，我们来看一下 createSqlSource 方法的实现，这里对应 XMLLanguageDriver 类的方法实现：

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

整个过程主要是遍历当前 SQL 语句标签的所有子节点，并依据当前子节点的类型分而治之，可以对照官方文档的动态 SQL 配置示例进行理解。如果当前子节点是一个具体的字符串或 CDATA 表达式（即 SQL 语句片段），则会获取字面值并依据是否包含未解析的 “${}” 占位符判断是否是动态 SQL，并封装成对应的 SqlNode 对象，SqlNode 是一个接口，用于封装定义的动态 SQL 节点和文本节点，包含多种实现类，该接口及其具体实现类留到后面针对性讲解。如果当前子节点是一个具体的 XML 标签，则必定是一个动态 SQL 配置，这个时候会获取具体的节点名称，并调用 nodeHandlers 方法构造对应的 NodeHandler 对象：

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

##### 解析 <include/> 节点配置

##### 解析 <selectKey/> 节点配置
