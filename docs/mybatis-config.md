前面我们曾约定 mybatis-config.xml 为配置文件，SQL 配置文件为映射文件，本文我们将沿用上一篇中的示例程序，一起来探究一下 MyBatis 加载和解析配置文件，即 mybatis-config.xml 的过程。

在示例程序中，执行配置文件（包括后面要介绍映射文件）加载与解析的过程位于第一行代码中（如下），其中 Resources 是一个简单的基于类路径获取数据流的工具类，借助该工具类可以获取配置文件 mybatis-config.xml 的 InputStream 对象，然后将其传递给 SqlSessionFactoryBuilder 的 build 方法以构造 SqlSessionFactory。

```java
SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(Resources.getResourceAsStream("mybatis-config.xml"));
```

SqlSessionFactoryBuilder 由名字可知它是一个构造器，用于构造 SqlSessionFactory 对象。按照 MyBatis 的官方文档来说，SqlSessionFactoryBuilder 一旦构造完 SqlSessionFactory 对象便完成了其使命。其实现也比较简单，只有 build 这一个方法及其重载版本，这里选取一个典型的底层重载版本来说明一下：

```java
public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    try {
        // 解析配置文件封装成 Configuration 对象，并构造 SqlSessionFactory 对象返回
        XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
        return this.build(parser.parse());
    } catch (Exception e) {
        throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
        // 执行关闭前的清理工作
        ErrorContext.instance().reset();
        try {
            inputStream.close();
        } catch (IOException e) {
            // Intentionally ignore. Prefer previous error.
        }
    }
}
```

上述代码抛去异常处理逻辑，核心在于下面两行，第一行用来构造 XMLConfigBuilder 对象，XMLConfigBuilder 可以看作是 mybatis-config.xml 配置文件的解析器，第二行则调用该对象的 parse() 方法对配置文件进行解析，并记录相关配置项到 `org.apache.ibatis.session.Configuration` 对象中，然后基于配置对象创建 SqlSessionFactory 对象返回，Configuration 可以看做是 MyBatis 框架内部全局唯一的配置类，用于记录几乎所有的配置和映射，以及运行过程中的中间值，后面我们会经常遇到它，现在可以将其理解为 MyBatis 框架的配置中心。

```java
1. XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
2. return this.build(parser.parse());
```

我们来看一下 XMLConfigBuilder 对象的构造过程，调用的构造方法版本如下：

```java
public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), // 构造对应的 XPath 解析器
            environment, props);
}
```

```java
/**
 * 构造方法
 *
 * @param parser 配置文件解析器
 * @param environment 当前使用的配置文件组ID
 * @param props 参数指定的配置项
 */
private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 调用父类构造函数，并构造 Configuration 对象
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 将参数指定的配置项记录到 Configuration.variables 属性中
    this.configuration.setVariables(props);
    this.parsed = false; // 标记配置文件是否已经被解析过
    this.environment = environment;
    this.parser = parser;
}
```

构造方法各参数的释义见代码注释，这里针对一些比较不太直观的参数进一步说明。首先来看一下 XPathParser 类型的构造参数，首先我们需要知道的一点是 MyBatis 基于 DOM 树对 XML 配置文件进行解析，而操作 DOM 树的方式则是基于 [XPath(XML Path Language)](https://zh.wikipedia.org/wiki/XPath)，它是一种能够极大简化 XML 操作的路径语言，笔者第一次写程序操作 XML 文件的时候就用到了该语言，简单、直观，也很好用，没有接触过的同学可以针对性的学习一下。XPathParser 基于 XPath 语法对 XML 进行解析，其实现比较简单，这里不展开说明。

再来看一下 environment 参数，基于配置的框架一般都允许配置多套环境，以应对开发、测试、灰度，以及生产。除了后面会讲到的 `<environment/>` 配置，MyBatis 也允许我们通过参数指定实际生效的配置环境，我们在调用 `SqlSessionFactoryBuilder#build` 方法时，可以以参数形式指定当前运行的配置环境。

### 一. 基础组件实现

在具体分析配置文件的解析实现之前，先用一节的篇幅介绍一下后续过程依赖的一些运行组件。首先来看一下 BaseBuilder 抽象类，XMLConfigBuilder 从 BaseBuilder 派生而来，包括后面会介绍的 XMLMapperBuilder、XMLStatementBuilder，以及 SqlSourceBuilder 等都继承自该抽象类。先来看一下 BaseBuilder 的字段定义：

```java
/** 全局唯一的配置对象 */
protected final Configuration configuration;
/** 记录别名与类型的映射关系 */
protected final TypeAliasRegistry typeAliasRegistry;
/** 记录类型对应的类型处理器 */
protected final TypeHandlerRegistry typeHandlerRegistry;
```

BaseBuilder 仅定义了三个字段，各字段的作用见代码注释，XMLConfigBuilder 构造方法调用了 BaseBuilder 的构造方法来触发这三个字段的初始化。前面我们提及到的封装全局配置的 Configuration 对象就是在这里定义，接下来我们探究一下属性 typeAliasRegistry 和 typeHandlerRegistry 分别对应的 TypeAliasRegistry 类和 TypeHandlerRegistry 类的作用和实现。

首先来看一下 TypeAliasRegistry 类，我们都知道在编写 SQL 语句时可以为表名或列名定义别名（alias），以减少书写量，而 TypeAliasRegistry 是对别名这一机制的延伸，借助于此，我们可以为任意一个类型定义别名。

TypeAliasRegistry 中仅定义了一个 `Map<String, Class<?>>` 类型的属性，充当内存数据库，蕴含别名与具体类型的映射关系。TypeAliasRegistry 持有一个无参数的构造方法，其中只做一件事，即调用 registerAlias 方法为常用类型注册对应的别名，没什么复杂逻辑，具体实现可以参考源码，我们来一起看一下这里调用的 registerAlias 方法的实现细节：

```java
public void registerAlias(String alias, Class<?> value) {
    if (alias == null) {
        // 别名不能为 null
        throw new TypeException("The parameter alias cannot be null");
    }
    // 将别名转换成小写
    String key = alias.toLowerCase(Locale.ENGLISH);
    if (TYPE_ALIASES.containsKey(key) && TYPE_ALIASES.get(key) != null && !TYPE_ALIASES.get(key).equals(value)) {
        // 防止重复注册
        throw new TypeException("The alias '" + alias + "' is already mapped to the value '" + TYPE_ALIASES.get(key).getName() + "'.");
    }
    // 写入 Map 集合
    TYPE_ALIASES.put(key, value);
}
```

整个方法的过程本质上就是将 (alias, value) 键值对写入 Map 集合中，只是在插入之前需要保证 alias 不为 null，且不允许相同的别名和类型重复注册。除了这里的单个注册，TypeAliasRegistry 还提供了 registerAlias 方法，允许扫描注册一个 package 下面的所有类或指定类型及其子类，在批量扫描注册时，我们可以利用 `@Alias` 注解为类指定别名，否则 MyBatis 将会以当前类的 simple name 作为类型别名。当然，能够注册就能够获取，resolveAlias 提供了获取指定别名对应类型的能力，代码比较简单，无非就是从 Map 集合中获取指定 key 对应的 value，不再多做撰述。

再来看一下 TypeHandlerRegistry 类，在开始分析之前我们必须对 TypeHandler 接口有一个了解。我们都知道 JDBC 定义的类型（枚举类 JdbcType 对已有 JDBC 类型进行了封装）与 JAVA 定义的类型并不是完全匹配的，所以我们就需要在这中间进行相互映射，而 TypeHandler 的职责就在于此。TypeHandler 是一个接口，其中定义了 4 个方法：

```java
public interface TypeHandler<T> {

    /** 为 {@link PreparedStatement} 对象绑定参数（将数据由 JAVA 类型转换成 JDBC 类型） */
    void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

    /** 获取结果集中对应的参数值（将数据由 JDBC 类型转换成 JAVA 类型） */
    T getResult(ResultSet rs, String columnName) throws SQLException;
    T getResult(ResultSet rs, int columnIndex) throws SQLException;

    /** 获取存储过程中输出类型的参数值（将数据由 JDBC 类型转换成 JAVA 类型） */
    T getResult(CallableStatement cs, int columnIndex) throws SQLException;
}
```

围绕 TypeHandler 派生了许多类，基本上都是对特定类型的处理，逻辑都比较简单，不一一说明。对 TypeHandler 有一个基本感知之后，我们回过头来继续来看 TypeHandlerRegistry，由名字我们就可以知道这是一个 TypeHandler 的注册中心。TypeHandlerRegistry 中定义了多个 final 类型 Map 集合属性以记录类型及其类型处理器之间的映射关系，其中最核心的两个属性如下：

```java
/**
 * 记录 JDBC 类型与 {@link TypeHandler} 之间映射关系
 * 用于从结果集读取数据时，将 JDBC 类型转换对应的 JAVA 类型
 */
private final Map<JdbcType, TypeHandler<?>> JDBC_TYPE_HANDLER_MAP = new EnumMap<JdbcType, TypeHandler<?>>(JdbcType.class);

/**
 * 记录 JAVA 类型转 JDBC 类型时所需要的 {@link TypeHandler}
 * 一个 JAVA 类型可能存在多个 JDBC 类型
 */
private final Map<Type, Map<JdbcType, TypeHandler<?>>> TYPE_HANDLER_MAP = new ConcurrentHashMap<Type, Map<JdbcType, TypeHandler<?>>>();
```

与 TypeAliasRegistry 一样，TypeHandlerRegistry 也仅定义了一个无参的构造方法，用于做一件事情，即调用 register 方法注册类型及其对应的类型处理器。register 方法存在多个重载版本，其中最根本的实现如下：

```java
/**
 * 注册类型处理器
 *
 * @param javaType JAVA 类型
 * @param jdbcType JDBC 类型
 * @param handler 对应的 {@link TypeHandler}
 */
private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
    if (javaType != null) {
        // 如果 javaType 不为空，则添加对应的类型处理器到 TYPE_HANDLER_MAP 集合中
        Map<JdbcType, TypeHandler<?>> map = TYPE_HANDLER_MAP.get(javaType);
        if (map == null) {
            map = new HashMap<JdbcType, TypeHandler<?>>();
            TYPE_HANDLER_MAP.put(javaType, map);
        }
        map.put(jdbcType, handler);
    }
    // 记录到 ALL_TYPE_HANDLERS_MAP 集合中
    ALL_TYPE_HANDLERS_MAP.put(handler.getClass(), handler);
}
```

register 方法的核心逻辑就是往对应的 Map 集合中注册类型及其类型处理器。MyBatis 基于该方法封装了多层重载版本，其中大部分实现都比较简单，这里就基于注解 `@MappedJdbcTypes` 和注解 `@MappedTypes` 指定对应类型的版本进行说明。

```java
/**
 * 基于 {@link MappedJdbcTypes} 指定处理的 JDBC 类型
 *
 * @param javaType JAVA 类型
 * @param typeHandler 类型处理器
 */
private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
    MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);
    if (mappedJdbcTypes != null) {
        // 遍历注册当前 typeHandler 能够处理的 JDBC 类型
        for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
            this.register(javaType, handledJdbcType, typeHandler);
        }
        // 如果允许处理 null 值
        if (mappedJdbcTypes.includeNullJdbcType()) {
            this.register(javaType, null, typeHandler);
        }
    } else {
        this.register(javaType, null, typeHandler);
    }
}
```

上述方法首先获取注解 `@MappedJdbcTypes` 指定的 JDBC 类型列表，然后进行遍历注册处理，该注解的定义如下：

```java
/**
 * 用于指定 {@link TypeHandler} 能够处理的 JDBC 类型集合
 *
 * @author Eduardo Macarron
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MappedJdbcTypes {

    /** 当前类型处理器能够处理的 JDBC 类型列表 */
    JdbcType[] value();

    /** 是否允许处理 null 值 */
    boolean includeNullJdbcType() default false;
}
```

该注解还允许通过 includeNullJdbcType 字段指定是否允许当前类型处理器处理 null 值。能够指定 JDBC 类型，当然也就能够指定 JAVA 类型，下面的方法基于注解 `@MappedTypes` 来达到此目的，最终还是调用上面的重载版本完成注册过程：

```java
/**
 * 基于 {@link MappedTypes } 注解指定对应的 JAVA 类型集合
 *
 * @param typeHandler
 * @param <T>
 */
public <T> void register(TypeHandler<T> typeHandler) {
    boolean mappedTypeFound = false;
    MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
    if (mappedTypes != null) {
        // 遍历注册当前 typeHandler 能够处理的 JAVA 类型
        for (Class<?> handledType : mappedTypes.value()) {
            this.register(handledType, typeHandler);
            mappedTypeFound = true;
        }
    }
    // 尝试基于 typeHandler 来发现对应的 JAVA 类型，需要实现 TypeReference 接口（@since 3.1.0）
    if (!mappedTypeFound && typeHandler instanceof TypeReference) {
        try {
            TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
            this.register(typeReference.getRawType(), typeHandler);
            mappedTypeFound = true;
        } catch (Throwable t) {
            // maybe users define the TypeReference with a different type and are not assignable, so just ignore it
        }
    }
    if (!mappedTypeFound) {
        this.register((Class<T>) null, typeHandler);
    }
}
```

上述方法先是获取并遍历处理 `@MappedTypes` 注解指定的 JAVA 类型集合，如果未指定则会尝试自动发现并注册 TypeHandler 能够处理的 JAVA 类型。能够注册也就能够获取，TypeHandlerRegistry 中提供了 getTypeHandler 方法的多种重载实现，比较简单，不再展开。

回过头再来看一下 BaseBuilder 的实现，其中定义了许多方法，但是只要了解上面介绍的 TypeAliasRegistry 和 TypeHandlerRegistry，那么这些方法的作用在理解上应该非常容易，这里就不多做撰述，有兴趣的同学可以参考上面的分析去阅读一下源码。

### 二. 配置文件的解析过程

完成了 XMLConfigBuilder 对象的构造，下一步会调用其 parse 方法执行配置文件的解析，成员变量 parsed 会标记该过程是够被执行，以防止对配置文件的重复解析：

```java
public Configuration parse() {
    if (this.parsed) {
        // 配置文件已经被解析过
        throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    this.parsed = true;
    // 解析 mybatis-config.xml 中的各项配置, 记录到 configuration 对象中
    this.parseConfiguration(parser.evalNode("/configuration")); // <configuration /> 作为根节点
    return this.configuration;
}
```

mybatis-config.xml 文件以 `<configuration/>` 作为配置文件根节点，上述方法的核心在于触发调用 parseConfiguration 方法对配置文件的各个元素进行解析，并封装解析结果到 Configuration 对象中，最终返回该配置对象。

```java
private void parseConfiguration(XNode root) {
    try {
        // 解析 <properties/> 配置
        this.propertiesElement(root.evalNode("properties"));
        // 解析 <settings/> 配置
        Properties settings = this.settingsAsProperties(root.evalNode("settings"));
        // 获取并设置 vfsImpl 属性
        this.loadCustomVfs(settings);
        // 解析 <typeAliases/> 配置
        this.typeAliasesElement(root.evalNode("typeAliases"));
        // 解析 <plugins/> 配置
        this.pluginElement(root.evalNode("plugins"));
        // 解析 <objectFactory/> 配置
        this.objectFactoryElement(root.evalNode("objectFactory"));
        // 解析 <objectWrapperFactory/> 配置
        this.objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
        // 解析 <reflectorFactory/> 配置
        this.reflectorFactoryElement(root.evalNode("reflectorFactory"));
        // 将 settings 设置到 configuration 中
        this.settingsElement(settings);
        // 解析 <environments/> 配置
        this.environmentsElement(root.evalNode("environments"));
        // 解析 <databaseIdProvider/> 配置
        this.databaseIdProviderElement(root.evalNode("databaseIdProvider"));
        // 解析 <typeHandlers/> 配置
        this.typeHandlerElement(root.evalNode("typeHandlers"));
        // 解析 <mappers/> 配置
        this.mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
        throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
}
```

parseConfiguration 在实现上比较直观，各配置项的解析过程都采用专门的方法进行封装，接下来逐一进行分析，其中 `<plugins/>` 标签用于配置自定义插件，以拦截 SQL 语句的执行过程，相应的解析过程暂时先不做说明，留到后面专门解析插件的实现机制时一同讲解。

#### 2.1 标签 <properties/> 的解析机制

先来看一下 `<properties/>` 标签怎么玩，其中的配置项可以在整个配置文件中用来动态替换对应的占位符，可以从外部 properties 文件读取，也可以通过 `<property/>` 子标签指定。假设我们希望通过 `<properties/>` 来指定数据源配置，可以配置如下：

```xml
<properties resource="datasource.properties">
    <property name="driver" value="com.mysql.jdbc.Driver"/>
    <!--为占位符启用默认值配置，默认关闭，需要采用如下方式开启-->
    <property name="org.apache.ibatis.parsing.PropertyParser.enable-default-value" value="true"/>
</properties>
```

```properties
# datasource.properties
url=jdbc:mysql://localhost:3306/test
username=root
password=123456
```

```xml
<dataSource type="POOLED"> <!--or UNPOOLED or JNDI-->
    <property name="driver" value="${driver}"/>
    <property name="url" value="${url}"/>
    <property name="username" value="${username:zhenchao}"/> <!--占位符设置默认值，需要专门开启-->
    <property name="password" value="${password}"/>
</dataSource>
```

然后我们基于 OGNL 表达式在其他配置项中使用这些配置值，其中除了 driver 属性值是从 `<property/>` 中读取的，其余属性值都是从 properties 配置文件中获取，MyBatis 关于属性的读取顺序做如下规定：

> 1. 在 properties 元素体内指定的属性首先被读取
> 2. 然后根据 properties 元素中的 resource 属性读取类路径下属性文件或根据 url 属性指定的路径读取属性文件，并覆盖已读取的同名属性
> 3. 最后读取作为方法参数传递的属性，并覆盖已读取的同名属性

我们来分析一下 `<properties/>` 标签的解析过程，propertiesElement 方法中实现了对该标签的解析：

```java
private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
        // 获取 <property/> 子标签
        Properties defaults = context.getChildrenAsProperties();
        // 支持通过 resource 或 url 字段指定外部配置文件
        String resource = context.getStringAttribute("resource");
        String url = context.getStringAttribute("url");
        if (resource != null && url != null) {
            // 二者不允许同时存在
            throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
        }
        if (resource != null) {
            // 从类路径加载配置文件
            defaults.putAll(Resources.getResourceAsProperties(resource));
        } else if (url != null) {
            // 从 url 指定位置加载配置文件
            defaults.putAll(Resources.getUrlAsProperties(url));
        }
        // 合并已有配置
        Properties vars = configuration.getVariables();
        if (vars != null) {
            defaults.putAll(vars);
        }
        // 记录到 XPathParser.variables 中
        parser.setVariables(defaults);
        // 记录到 Configuration.variables 中
        configuration.setVariables(defaults);
    }
}
```

由 MyBatis 的官方说明文档我们知道 `<properties/>` 标签支持以 resource 属性或 url 属性指定配置文件所在的路径，由上述实现我们可以看到 `<properties/>` 不允许我们同时使用这两个属性元素，否则会抛出异常。在将对应的配置加载成为 Properties 对象之后，方法会合并 Configuration 对象中已有的配置项，并将结果再次记录到 XPathParser 和 Configuration 对象的 variables 中，以备后用。

#### 2.2 标签 <settings/> 的解析机制

再来看一下 `<settings/>` 标签，MyBatis 通过 `<settings/>` 标签来提供一些全局性的配置，这些配置会影响 MyBatis 的运行行为，官方文档对这些配置项进行了详细的说明，下面摘自文档的一个完整配置项，其中各项的含义可以参考文档说明：

```xml
<settings>
  <setting name="cacheEnabled" value="true"/>
  <setting name="lazyLoadingEnabled" value="true"/>
  <setting name="multipleResultSetsEnabled" value="true"/>
  <setting name="useColumnLabel" value="true"/>
  <setting name="useGeneratedKeys" value="false"/>
  <setting name="autoMappingBehavior" value="PARTIAL"/>
  <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
  <setting name="defaultExecutorType" value="SIMPLE"/>
  <setting name="defaultStatementTimeout" value="25"/>
  <setting name="defaultFetchSize" value="100"/>
  <setting name="safeRowBoundsEnabled" value="false"/>
  <setting name="mapUnderscoreToCamelCase" value="false"/>
  <setting name="localCacheScope" value="SESSION"/>
  <setting name="jdbcTypeForNull" value="OTHER"/>
  <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
</settings>
```

MyBatis 对于该标签的解析过程十分简单，首先会调用 settingsAsProperties 方法获取配置项对应的 Properties 对象，同时会检查配置项是否是可识别的：

```java
private Properties settingsAsProperties(XNode context) {
    if (context == null) {
        return new Properties();
    }
    // 解析 <setting/> 封装成 Properties
    Properties props = context.getChildrenAsProperties();
    // 构造 Configuration 对应的 MetaClass 对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    // 遍历配置项，确保配置项是 MyBatis 可识别的
    for (Object key : props.keySet()) {
        if (!metaConfig.hasSetter(String.valueOf(key))) {
            // 属性不存在 setter 方法
            throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
        }
    }
    return props;
}
```

接下来会调用 loadCustomVfs 方法从 Properties 对象中获取 vfsImpl 属性值（自定义 VFS 的实现的类全限定名，以逗号分隔）并进行记录。最后调用 settingsElement 方法将剩余的配置项记录到 Configuration 对象对应的属性中。

#### 2.3 标签 <typeAliases/> 和 <typeHandlers/> 的解析机制

前面我们介绍了 TypeAliasRegistry 和 TypeHandlerRegistry 两个类的作用和实现，本小节介绍的两个标签分别对应相关的配置，前者用于配置注册类型及其别名的映射关系，后者用于配置注册类型及其类型处理器之间的映射关系。二者在实现上基本相同，所以这里我们仅对 `<typeAliases/>` 标签的解析过程进行分析，有兴趣的读者可以自己阅读 `<typeHandlers/>` 的源码实现。

```java
private void typeAliasesElement(XNode parent) {
    if (parent != null) {
        for (XNode child : parent.getChildren()) {
            if ("package".equals(child.getName())) {
                /*
                 * 子节点是 <package name=""/>
                 * 如果指定了一个包名，MyBatis 会在包名下搜索需要的 Java Bean，并处理 @Alias 注解，
                 * 在没有注解的情况下，会使用 Bean 的首字母小写的简单名称作为它的别名。
                 */
                String typeAliasPackage = child.getStringAttribute("name");
                configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
            } else {
                /* 如果子节点是 <typeAlias alias="" type=""/> */
                String alias = child.getStringAttribute("alias"); // 获取别名
                String type = child.getStringAttribute("type"); // 获取对应的类型限定名
                try {
                    // 获取类型对应的 Class 对象
                    Class<?> clazz = Resources.classForName(type);
                    if (alias == null) {
                        // 先尝试获取 @Alias 注解，如果没有则使用类的简单名称
                        typeAliasRegistry.registerAlias(clazz);
                    } else {
                        // 使用配置指定的 alias 进行注册
                        typeAliasRegistry.registerAlias(alias, clazz);
                    }
                } catch (ClassNotFoundException e) {
                    throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                }
            }
        }
    }
}
```

`<typeAliases/>` 具备两种配置方式，单一注册与批量扫描，具体示例可以参考官方文档，对应的实现也需要区分这两种情况，如果是批量扫描，即子标签是 `<package/>`，则会调用 `TypeAliasRegistry#registerAliases` 方法进行扫描注册：

```java
public void registerAliases(String packageName) {
    this.registerAliases(packageName, Object.class);
}

public void registerAliases(String packageName, Class<?> superType) {
    // 获取指定 package 下所有 superType 类型及其子类型
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
    // 遍历处理扫描到的类
    for (Class<?> type : typeSet) {
        // 不包含内部类、接口，以及抽象类
        if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
            // 尝试获取类的 @Alias 注解，如果没有注册则使用类的简单名称的小写形式作为别名进行注册
            this.registerAlias(type);
        }
    }
}
```

如果子节点是 `<typeAlias alias="" type=""/>` 这种形式，则会获取 alias 和 type 属性值，然后基于一定规则进行注册，具体过程如代码注释。

#### 2.4 标签 <objectFactory/> 的解析机制

在具体分析 `<objectFactory/>` 标签的实现细节之前，我们必须先了解与之密切相连的 ObjectFactory 接口，由名字我们可以猜测这是一个工厂类，并且是创建对象的工厂，其定义如下：

```java
public interface ObjectFactory {
    /** 设置配置信息 */
    void setProperties(Properties properties);
    /** 基于无参构造方法创建指定类型对象 */
    <T> T create(Class<T> type);
    /** 基于指定的构造参数（类型）选择对应的构造方法创建目标对象 */
    <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs);
    /** 检测指定类型是否是集合类型 */
    <T> boolean isCollection(Class<T> type);
}
```

各个方法的作用如代码注释，比较简单，DefaultObjectFactory 是该接口的默认实现，我们来重点看一下基于指定构造参数（类型）选择对应的构造方法创建目标对象的实现细节，基于无参构造方法创建对象的方法是对该方法的封装实现：

```java
public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    // 如果传入的是接口类型，则选择具体的实现类型以创建对象，毕竟接口类型不能被实例化
    Class<?> classToCreate = this.resolveInterface(type);
    // 基于入参选择合适的构造方法进行实例化
    return (T) instantiateClass(classToCreate, constructorArgTypes, constructorArgs);
}
```

方法首先会判断当前指定的类型是否是接口类型，因为接口类型无法实例化，所以需要选择相应的实现类代替，例如当我们传递的是一个 List 接口类型时，会返回相应的实现类 ArrayList，再来看一下 instantiateClass 方法实现：

```java
<T> T instantiateClass(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    try {
        Constructor<T> constructor;
        // 如果没有传递构造参数或类型，则使用无参构造方法创建对象
        if (constructorArgTypes == null || constructorArgs == null) {
            constructor = type.getDeclaredConstructor();
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        }
        // 否则选择对应的有参数构造方法创建对象
        constructor = type.getDeclaredConstructor(constructorArgTypes.toArray(new Class[constructorArgTypes.size()]));
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
        return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
    } catch (Exception e) {
        // 省略异常处理细节
    }
}
```

instantiateClass 方法主要基于传递的参数以决策具体创建对象的构造方法版本，并基于反射机制创建对象。所以说 ObjectFactory 接口的作用主要是对我们传递的类型进行实例化，默认的实现版本比较简单，如果现有实现不能满足我们的需求，则可以扩展 ObjectFactory 接口，并将相应的自定义实现通过 `<objectFactory/>` 标签进行注册，具体的使用方式官方文档列举得比较清楚，这里不再画蛇添足，我们继续分析该标签的解析过程。

```java
private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
        // 获取 type 属性配置，对应自定义对象工厂类
        String type = context.getStringAttribute("type");
        // 获取 <property/> 子标签
        Properties properties = context.getChildrenAsProperties();
        // 实例化自定义工厂
        ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
        // 设置属性配置
        factory.setProperties(properties);
        // 记录到 Configuration.objectFactory
        configuration.setObjectFactory(factory);
    }
}
```

上述方法实现了对该标签的解析，具体步骤如代码注释，基本流程就是获取我们在标签中通过 type 指定的自定义对象工厂全限定名和相应属性配置，然后构造自定义对象的工厂对象，并将获取到的属性值注入到对象中，最后将对象工厂实例记录到
Configuration 对象的 objectFactory 属性中。

#### 2.5 标签 <reflectorFactory/> 的解析机制

`<reflectorFactory/>` 标签用于注册自定义 ReflectorFactory 实现，标签的解析过程与 `<objectFactory/>` 基本相同，不再展开，本小节我们将探究一下该标签涉及到相关类的作用与实现。ReflectorFactory 顾名思义是一个 Reflector 工厂，其接口定义如下：

```java
public interface ReflectorFactory {
    /** 是否缓存 {@link Reflector} 对象 */
    boolean isClassCacheEnabled();
    /** 设置是否缓存 {@link Reflector} 对象 */
    void setClassCacheEnabled(boolean classCacheEnabled);
    /** 获取指定类型的 {@link Reflector} 对象 */
    Reflector findForClass(Class<?> type);
}
```

各个方法的作用见代码注释，默认实现 DefaultReflectorFactory 通过一个 boolean 变量 classCacheEnabled 记录是否启用缓存，并通过一个线程安全的 Map 集合 reflectorMap 记录缓存的 Reflector 对象，前两个方法的实现比较简单，我们来看一下稍微复杂一点的 findForClass 默认实现：

```java
public Reflector findForClass(Class<?> type) {
    if (classCacheEnabled) {
        // 启用了缓存， 尝试先从缓存中获取
        Reflector cached = reflectorMap.get(type);
        if (cached == null) {
            // 缓存命中失败，创建新的 Reflector 对象并缓存
            cached = new Reflector(type);
            reflectorMap.put(type, cached);
        }
        return cached;
    } else {
        // 没有启用缓存，直接创建 Reflector 对象
        return new Reflector(type);
    }
}
```

上述方法的目的是获取指定类型的 Reflector 对象，如果启用了缓存，则先尝试从缓存中获取，否则创建新的对象，那么 Reflector 又是什么呢？我们先来看一下 Reflector 的属性和构造方法定义：

```java
public class Reflector {

    /** 隶属的 Class 类型 */
    private final Class<?> type;
    /** 可读属性名称集合 */
    private final String[] readablePropertyNames;
    /** 可写属性名称集合 */
    private final String[] writablePropertyNames;
    /** 属性对应的 setter 方法（封装成 Invoker 对象） */
    private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
    /** 属性对应的 getter 方法（封装成 Invoker 对象） */
    private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
    /** 属性对应 setter 方法的入参类型 */
    private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
    /** 属性对应 getter 方法的返回类型 */
    private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
    /** 记录默认构造方法 */
    private Constructor<?> defaultConstructor;
    /** 记录所有的属性名称 */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

    public Reflector(Class<?> clazz) {
        type = clazz;
        // 解析获取默认构造方法（无参构造方法）
        this.addDefaultConstructor(clazz);
        // 解析获取所有的 getter 方法，并记录到 getMethods 与 getTypes 属性中
        this.addGetMethods(clazz);
        // 解析获取所有的 setter 方法，并记录到 setMethods 与 setTypes 属性中
        this.addSetMethods(clazz);
        // 解析获取所有没有 setter/getter 方法的字段，并添加到相应的集合中
        this.addFields(clazz);
        // 填充可读属性名称数组
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        // 填充可写属性名称数组
        writablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        // 记录所有属性名称到 Map 集合中
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }
    // 省略方法实现
}
```

可以看到 Reflector 是对指定 Class 对象的封装，记录了对应的 Class 类型、属性、getter 和 setter 方法列表等信息，是反射操作的基础，其中的方法实现虽然较长，但是逻辑都比较简单，可以自行阅读源码。

#### 2.6 标签 <objectWrapperFactory/> 的解析机制

`<objectWrapperFactory/>` 标签用于注册自定义 ObjectWrapperFactory 实现，标签的解析过程与 `<objectFactory/>` 基本相同，不再展开，本小节我们将探究一下该标签涉及到相关类的作用与实现。

ObjectWrapperFactory 顾名思义是一个 ObjectWrapper 工厂，其默认实现 DefaultObjectWrapperFactory 并没有编写有用的代码逻辑，所以可以忽略，但是借助于 `<reflectorFactory/>` 标签，我们可以注册自定义的 ObjectWrapperFactory 实现，这里我们重点关注一下 ObjectWrapper 的实现细节。

ObjectWrapper 是一个接口，用于包装和处理一个对象，其中声明了多个操作对象的方法，包括获取、更新对象属性等，接口定义如下：

```java
public interface ObjectWrapper {
    /** 获取对应属性的值（对于集合而言，则是获取对应下标的值） */
    Object get(PropertyTokenizer prop);
    /** 设置对应属性的值（对于集合而言，则是设置对应下标的值）*/
    void set(PropertyTokenizer prop, Object value);
    /** 查找属性表达式对应的属性 */
    String findProperty(String name, boolean useCamelCaseMapping);
    /** 获取可读属性名称集合 */
    String[] getGetterNames();
    /** 获取可写属性名称集合 */
    String[] getSetterNames();
    /** 获取属性表达式指定属性 setter 方法的入参类型 */
    Class<?> getSetterType(String name);
    /** 获取属性表达式指定属性 getter 方法的返回类型 */
    Class<?> getGetterType(String name);
    /** 判断属性是否有 setter 方法 */
    boolean hasSetter(String name);
    /** 判断属性是否有 getter 方法 */
    boolean hasGetter(String name);
    /** 为属性表达式指定的属性创建对应的 {@link MetaObject} 对象 */
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);
    /** 是否是 {@link java.util.Collection} 类型 */
    boolean isCollection();
    /** 调用 {@link java.util.Collection} 对应的 add 方法 */
    void add(Object element);
    /** 调用 {@link java.util.Collection} 对应的 addAll 方法 */
    <E> void addAll(List<E> element);
}
```

由接口定义可以看出 ObjectWrapper 是为了简化调用方对于对象的操作。

#### 2.7 标签 <environments/> 的解析机制

继续来看一下 `<environments/>` 标签，该标签用于配置多套数据库环境，典型的应用场景就是在开发、测试、灰度，以及生产等环境通过该标签分别指定相应的配置，当应用需要同时操作多套数据源时，也可以基于该标签分别配置，具体的使用请参阅官方文档，我们来分析一下该标签的解析过程（建议参考具体配置项进行阅读）：

```java
private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
        if (environment == null) {
            // 未使用指定 environment，获取 default 属性值
            environment = context.getStringAttribute("default");
        }
        // 遍历处理 <environment/> 子节点
        for (XNode child : context.getChildren()) {
            // 获取 <environment/> 的 id 属性
            String id = child.getStringAttribute("id");
            // 处理指定的 <environment/> 节点
            if (this.isSpecifiedEnvironment(id)) {
                // 处理 <transactionManager/> 子节点，返回构造的 TransactionFactory 对象
                TransactionFactory txFactory = this.transactionManagerElement(child.evalNode("transactionManager"));
                // 处理 <dataSource/> 子节点，返回构造的 DataSourceFactory 对象
                DataSourceFactory dsFactory = this.dataSourceElement(child.evalNode("dataSource"));
                // 从工厂中获取 DataSource 对象
                DataSource dataSource = dsFactory.getDataSource();
                // 基于解析到的值构造 Environment 对象，并记录到 Configuration.environment 中
                Environment.Builder environmentBuilder = new Environment.Builder(id)
                        .transactionFactory(txFactory)
                        .dataSource(dataSource);
                configuration.setEnvironment(environmentBuilder.build());
            }
        }
    }
}
```

方法首先会判断是否参数指定了 environment，如果没有的话则尝试获取 `<environments/>` 标签的 default 属性，然后开始遍历寻找指定生效的 `<environment/>` 配置并进行解析，主要是对 `<transactionManager/>` 和 `<dataSource/>` 两个子标签的解析，前者用于指定 MyBatis 的事务管理器，后者用于配置数据源。先来看一下事务管理器配置的解析过程：

```java
private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
        // 获取事务管理器类型配置：JDBC or MANAGED
        String type = context.getStringAttribute("type");
        // 获取 <property/> 子节点
        Properties props = context.getChildrenAsProperties();
        // 构造对应的 TransactionFactory 对象
        TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
        // 设置配置的属性值
        factory.setProperties(props);
        return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
}
```

MyBatis 允许我们配置两种类型的事务管理器，即 JDBC 类型和 MANAGED 类型，引用官方文档的话来理解这二者的区别：

> 在 MyBatis 中有两种类型的事务管理器（也就是 type=”[JDBC|MANAGED]”）：
>
> - JDBC – 这个配置就是直接使用了 JDBC 的提交和回滚设置，它依赖于从数据源得到的连接来管理事务作用域。
> - MANAGED – 这个配置几乎没做什么。它从来不提交或回滚一个连接，而是让容器来管理事务的整个生命周期（比如 JEE 应用服务器的上下文）。 默认情况下它会关闭连接，然而一些容器并不希望这样，因此需要将 closeConnection 属性设置为 false 来阻止它默认的关闭行为。例如:
>
> ```xml
> <transactionManager type="MANAGED">
>  <property name="closeConnection" value="false"/>
> </transactionManager>
> ```
> _如果你正在使用 Spring + MyBatis，则没有必要配置事务管理器， 因为 Spring 模块会使用自带的管理器来覆盖前面的配置。_

Transaction 接口定义了事务，并为 JDBC 类型和 MANAGED 类型提供了相应的实现，即 JdbcTransaction 和 ManagedTransaction。正如上面引用的官方文档所说的那样，MyBatis 的事务操作实现实现的比较简单，考虑实际应用中更多是依赖于 Spring 的事务管理器，这里也就不再深究。

#### 2.8 标签 <databaseIdProvider/> 的解析机制

接着我们来看一下 `<databaseIdProvider/>` 标签，实际生产环境中可能会存在同时操作多套不同类型数据库的场景，我们知道 SQL 不能完全做到数据库无关，且 MyBatis 暂时还不能做到对上层完全屏蔽底层数据库的实现细节，所以在这种情况下执行 SQL 时，我们需要通过 databaseId 指定 SQL 应用的数据库产品，该标签的解析过程如下：

```java
private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
        String type = context.getStringAttribute("type");
        // awful patch to keep backward compatibility
        if ("VENDOR".equals(type)) {
            type = "DB_VENDOR"; // 保持兼容
        }
        // 获取 <property/> 子节点配置
        Properties properties = context.getChildrenAsProperties();
        // 构造 DatabaseIdProvider 对象
        databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
        // 设置配置的属性
        databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
        // 获取当前数据库环境对应的 databaseId，并记录到 Configuration.databaseId 中，已备后用
        String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
        configuration.setDatabaseId(databaseId);
    }
}
```

#### 2.9 标签 <mappers/> 的解析机制

来看最后一个标签，`<mappers/>` 用于指定映射文件，MyBatis 广受欢迎的一个很重要的原因是支持我们自己写 SQL，这样就可以保证 SQL 的优化可控。抛去注解配置 SQL 的形式（注解对于复杂 SQL 的支持较弱，一般仅用于编写简单的 SQL），对于框架自动生成的 SQL 和用户自定义的 SQL 都记录在映射 XML 文件中，`<mappers/>` 标签用于指明映射文件所在的路径，我们可以通过 `<mapper resource="">` 或 `<mapper url="">` 子标签指定映射 XML 文件所在的位置，也可以通过 `<mapper class="">` 子标签指定一个或多个具体的 Mapper 接口，甚至可以通过 `<package name=""/>` 子标签指定映射文件所在的包名，扫描注册。

```java
private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
        for (XNode child : parent.getChildren()) {
            if ("package".equals(child.getName())) {
                /*
                 * 配置了 package 属性，从指定包下面扫描注册
                 * <mappers>
                 *      <package name="org.mybatis.builder"/>
                 * </mappers>
                 */
                String mapperPackage = child.getStringAttribute("name");
                // 调用 MapperRegistry 进行注册
                configuration.addMappers(mapperPackage);
            } else {
                // 处理 resource, url, class 配置的场景
                String resource = child.getStringAttribute("resource");
                String url = child.getStringAttribute("url");
                String mapperClass = child.getStringAttribute("class");
                if (resource != null && url == null && mapperClass == null) {
                    /*
                     * <!-- Using classpath relative resources -->
                     * <mappers>
                     *      <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
                     *      <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
                     *      <mapper resource="org/mybatis/builder/PostMapper.xml"/>
                     * </mappers>
                     */
                    ErrorContext.instance().resource(resource);
                    // 从类路径获取文件输入流
                    InputStream inputStream = Resources.getResourceAsStream(resource);
                    // 构建 XMLMapperBuilder 对象
                    XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                    // 执行映射文件解析
                    mapperParser.parse();
                } else if (resource == null && url != null && mapperClass == null) {
                    /*
                     * <!-- Using url fully qualified paths -->
                     * <mappers>
                     *      <mapper url="file:///var/mappers/AuthorMapper.xml"/>
                     *      <mapper url="file:///var/mappers/BlogMapper.xml"/>
                     *      <mapper url="file:///var/mappers/PostMapper.xml"/>
                     * </mappers>
                     */
                    ErrorContext.instance().resource(url);
                    // 基于 url 获取配置文件输入流
                    InputStream inputStream = Resources.getUrlAsStream(url);
                    // 构建 XMLMapperBuilder 对象
                    XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                    // 执行映射文件解析
                    mapperParser.parse();
                } else if (resource == null && url == null && mapperClass != null) {
                    /*
                     * <!-- Using mapper interface classes -->
                     * <mappers>
                     *      <mapper class="org.mybatis.builder.AuthorMapper"/>
                     *      <mapper class="org.mybatis.builder.BlogMapper"/>
                     *      <mapper class="org.mybatis.builder.PostMapper"/>
                     * </mappers>
                     */
                    // 获取指定接口 Class 对象
                    Class<?> mapperInterface = Resources.classForName(mapperClass);
                    // 调用 MapperRegistry 进行注册
                    configuration.addMapper(mapperInterface);
                } else {
                    throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                }
            }
        }
    }
}
```

上述方法用于解析 `<mappers/>` 标签配置，流程首先会判断当前是否是 package 配置，如果是的话则会获取配置的 package 名称，然后执行扫描注册逻辑。如果是 resource 或 url 配置，则会先获取指定路径映射文件的输入流，然后构造 XMLMapperBuilder 对象对映射文件进行解析。对于 class 配置而言，则会构建接口限定名对应的 Class 对象，并调用 `MapperRegistry#addMapper` 方法执行注册。整个方法的运行逻辑还是比较直观的，其中涉及到对映射文件的解析注册过程，即 XMLMapperBuilder 相关类实现，将留到下一篇介绍映射文件加载与解析时做专门介绍。

下面来重点介绍一下 MapperRegistry 类及其周边类的作用和实现，我们在使用 MyBatis 框架时需要实现相应数据表对应的 Mapper 接口（以后统称为 Mapper 接口），其中声明了一系列数据库操作方法，我们可以通过注解的方式在方法上编写 SQL 语句，也可以通过映射 XML 文件的方式编写和关联对应的 SQL。上面解析 `<mappers/>` 标签实现时我们看到方法通过调用 MapperRegistry 的 addMapper 方法注册相应的 Mapper 接口，包括以 package 配置的方式，在扫描获取到相应的 Mapper 接口之后，也需要通过调用 MapperRegistry 的 addMapper 方法进行注册。MapperRegistry 中定义了两个属性：

```java
/** 全局唯一配置对象 */
private final Configuration config;

/** 记录Mapper接口（Class对象）与 {@link MapperProxyFactory} 之间的映射关系 */
private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<Class<?>, MapperProxyFactory<?>>();
```

上面我们调用的 addMapper 方法的实现如下;

```java
public <T> void addMapper(Class<T> type) {
    // 仅处理接口
    if (type.isInterface()) {
        if (this.hasMapper(type)) {
            // 对应 Mapper 接口已经注册
            throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
        }
        boolean loadCompleted = false; // 标记整个过程是否成功完成
        try {
            // 注册Mapper接口CLass对象及其对应的MapperProxyFactory对象
            knownMappers.put(type, new MapperProxyFactory<T>(type));
            MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
            // 解析Mapper接口中的注解SQL配置
            parser.parse();
            loadCompleted = true;
        } finally {
            if (!loadCompleted) {
                knownMappers.remove(type);
            }
        }
    }
}
```

Mapper 方法必须是一个接口才会被注册，这主要是为了配合 jdk 内置的动态代理机制。上一篇介绍 MyBatis 的基本运行原理时我们曾说过 MyBatis 通过为 Mapper 接口创建相应的动态代理类以执行具体的数据库操作，这一块的详细实现留到后面介绍 SQL 执行机制时再细讲，这里先知道这样一个概念即可。如果当前 Mapper 接口还没有被注册，则会创建对应的 MapperProxyFactory 对象并记录到 knownMappers 属性中，然后解析 Mapper 接口中注解的 SQL 配置，这一过程留到后面分析映射文件解析过程时再一并介绍，

到此，我们完成了对配置文件 mybatis-config.xml 解析实现的探究，在下一篇，我们将一起来探究映射文件的加载和解析过程。
