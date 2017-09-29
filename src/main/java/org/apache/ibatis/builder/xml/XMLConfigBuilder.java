/**
 * Copyright 2009-2017 the original author or authors.
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

package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * 用于解析 mybatis-config.xml 配置文件
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    /** 标识配置文件是否已经被解析过 */
    private boolean parsed;

    /** XML配置文件解析器 */
    private final XPathParser parser;

    /** 当前 <environment/> 设置 */
    private String environment;

    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(   // 构造对应的 XPath 解析器
                new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()),
                environment, props);
    }

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

    /**
     * 解析 mybatis-config.xml
     *
     * @return
     */
    public Configuration parse() {
        if (this.parsed) {
            // 配置文件已经被解析过
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        this.parsed = true;
        // 解析 mybatis-config.xml 中的各项配置, 记录到 configuration 对象中
        this.parseConfiguration(parser.evalNode("/configuration")); // <configuration /> 作为根结点
        return this.configuration;
    }

    /**
     * 依次解析各项配置
     *
     * @param root
     */
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

    /**
     * 解析 <settings /> 配置
     * MyBatis 中极为重要的调整设置，它们会改变 MyBatis 的运行时行为。
     *
     * <settings>
     * <setting name="cacheEnabled" value="true"/>
     * <setting name="lazyLoadingEnabled" value="true"/>
     * <setting name="multipleResultSetsEnabled" value="true"/>
     * <setting name="useColumnLabel" value="true"/>
     * <setting name="useGeneratedKeys" value="false"/>
     * <setting name="autoMappingBehavior" value="PARTIAL"/>
     * <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
     * <setting name="defaultExecutorType" value="SIMPLE"/>
     * <setting name="defaultStatementTimeout" value="25"/>
     * <setting name="defaultFetchSize" value="100"/>
     * <setting name="safeRowBoundsEnabled" value="false"/>
     * <setting name="mapUnderscoreToCamelCase" value="false"/>
     * <setting name="localCacheScope" value="SESSION"/>
     * <setting name="jdbcTypeForNull" value="OTHER"/>
     * <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
     * </settings>
     *
     * @param context
     * @return
     */
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

    /**
     * 获取VFS实现
     *
     * @param props
     * @throws ClassNotFoundException
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 解析 <typeAliases /> 配置
     *
     * @param parent
     */
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

    /**
     * 解析 <plugins interceptor=""/> 配置
     *
     * @param parent
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 获取 interceptor 属性
                String interceptor = child.getStringAttribute("interceptor");
                // 获取属性配置
                Properties properties = child.getChildrenAsProperties();
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * 解析 <objectFactory /> 配置
     *
     * @param context
     * @throws Exception
     */
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

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 解析 <properties/> 结点
     *
     * <properties resource="org/mybatis/example/config.properties">
     * <property name="username" value="dev_user"/>
     * <property name="password" value="F2Fa3!33TYyg"/>
     * </properties>
     *
     * @param context
     * @throws Exception
     */
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

    /**
     * http://www.mybatis.org/mybatis-3/zh/configuration.html#settings
     *
     * @param props
     * @throws Exception
     */
    private void settingsElement(Properties props) throws Exception {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析 <environments /> 配置，标记多套配置环境（开发、测试、生产）
     *
     * <environments default="development">
     * <environment id="development">
     * <transactionManager type="JDBC">
     * <property name="..." value="..."/>
     * </transactionManager>
     * <dataSource type="POOLED">
     * <property name="driver" value="${driver}"/>
     * <property name="url" value="${url}"/>
     * <property name="username" value="${username}"/>
     * <property name="password" value="${password}"/>
     * </dataSource>
     * </environment>
     * </environments>
     *
     * @param context
     * @throws Exception
     */
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

    /**
     * 解析 <databaseIdProvider/> 配置
     *
     * <databaseIdProvider type="DB_VENDOR">
     * <property name="SQL Server" value="sqlserver"/>
     * <property name="DB2" value="db2"/>
     * <property name="Oracle" value="oracle" />
     * </databaseIdProvider>
     *
     * @param context
     * @throws Exception
     */
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

    /**
     * 解析 <transactionManager/> 标签，构造对应的 {@link TransactionFactory} 对象
     * 事务管理器类型：JDBC or MANAGED
     *
     * @param context
     * @return
     * @throws Exception
     */
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

    /**
     * 处理 <dataSource/> 子节点，构造对应的 {@link DataSourceFactory} 对象
     *
     * @param context
     * @return
     * @throws Exception
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获取配置的数据源类型：UNPOOLED or POOLED or JNDI
            String type = context.getStringAttribute("type");
            // 获取 <property/> 子节点
            Properties props = context.getChildrenAsProperties();
            // 构造对应的 DataSourceFactory 对象
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            // 设置配置的属性值
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * 解析 <typeHandlers /> 配置
     *
     * @param parent
     * @throws Exception
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    // 如果是 <package name=""> 配置
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    // 如果是 <typeHandler javaType="" jdbcType="" handler=""> 配置
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = this.resolveClass(javaTypeName);
                    JdbcType jdbcType = this.resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = this.resolveClass(handlerTypeName);
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 解析 <mappers /> 配置
     *
     * @param parent
     * @throws Exception
     */
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

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
