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

#### 解析 <sql/> 配置

#### 解析 <select/>, <insert/>, <update/>, <delete/> 配置
