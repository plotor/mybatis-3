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

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
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
        this(
            // 构造 XPath 解析器
            new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()),
            environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, // XPath 解析器
                             String environment, // 当前使用的配置文件组 ID
                             Properties props) // 参数指定的配置项
    {
        // 构造 Configuration 对象，并调用父类构造方法
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        // 将参数指定的配置项记录到 Configuration#variables 属性中
        this.configuration.setVariables(props);
        // 标识配置文件还未被解析
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        // 配置文件已经被解析过，避免重复解析
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        // 解析 mybatis-config.xml 中的各项配置, 填充 Configuration 对象
        this.parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    private void parseConfiguration(XNode root) {
        try {
            // 解析 <properties/> 配置
            this.propertiesElement(root.evalNode("properties"));
            // 解析 <settings/> 配置
            Properties settings = this.settingsAsProperties(root.evalNode("settings"));
            // 获取并设置 vfsImpl 属性
            this.loadCustomVfs(settings);
            // 获取并设置 logImpl 属性
            this.loadCustomLogImpl(settings);
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
            // 将 settings 配置设置到 Configuration 对象中
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

    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        // 解析 <setting/> 配置，封装成 Properties 对象
        Properties props = context.getChildrenAsProperties();
        // 构造 Configuration 对应的 MetaClass 对象
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        // 遍历配置项，确保配置项是 MyBatis 可识别的
        for (Object key : props.keySet()) {
            // 属性对应的 setter 方法不存在
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

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

    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = this.resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                /*
                 * 子节点是 <package name=""/>，
                 * 如果指定了一个包名，MyBatis 会在包名下搜索需要的 Java Bean，并处理 @Alias 注解，
                 * 在没有注解的情况下，会使用 Bean 的首字母小写的简单名称作为它的别名。
                 */
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                }
                // 子节点是 <typeAlias alias="" type=""/> 配置
                else {
                    String alias = child.getStringAttribute("alias"); // 别名
                    String type = child.getStringAttribute("type"); // 类型限定名
                    try {
                        // 获取类型对应的 Class 对象
                        Class<?> clazz = Resources.classForName(type);
                        // 未配置 alias，先尝试获取 @Alias 注解，如果没有则使用类的简单名称
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        }
                        // 配置了 alias，使用该 alias 进行注册
                        else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                Interceptor interceptorInstance = (Interceptor) this.resolveClass(interceptor).getDeclaredConstructor().newInstance();
                interceptorInstance.setProperties(properties);
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获取 type 属性配置，对应自定义对象工厂类
            String type = context.getStringAttribute("type");
            // 获取 <property/> 子标签列表，封装成 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // 实例化自定义工厂类对象
            ObjectFactory factory = (ObjectFactory) this.resolveClass(type).getDeclaredConstructor().newInstance();
            // 设置属性配置
            factory.setProperties(properties);
            // 填充 Configuration 对象
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ObjectWrapperFactory factory = (ObjectWrapperFactory) this.resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) this.resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 获取 <property/> 子标签列表，封装成 Properties 对象
            Properties defaults = context.getChildrenAsProperties();
            // 支持通过 resource 或 url 属性指定外部配置文件
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            // 这两种类型的配置是互斥的
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            // 从类路径加载配置文件
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            }
            // 从 url 指定位置加载配置文件
            else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 合并已有的配置项
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            // 填充 XPathParser 和 Configuration 对象
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(this.booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) this.createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(this.booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(this.booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(this.booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(this.booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(this.booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(this.integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(this.integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setDefaultResultSetType(this.resolveResultSetType(props.getProperty("defaultResultSetType")));
        configuration.setMapUnderscoreToCamelCase(this.booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(this.booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(this.stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(this.booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(this.resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(this.resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(this.booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(this.booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(this.booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(this.resolveClass(props.getProperty("configurationFactory")));
    }

    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // 未使用指定 environment 参数，获取 default 属性值
            if (environment == null) {
                environment = context.getStringAttribute("default");
            }
            // 遍历处理 <environment/> 子标签
            for (XNode child : context.getChildren()) {
                // 获取 id 属性配置
                String id = child.getStringAttribute("id");
                // 处理指定的 <environment/> 配置
                if (this.isSpecifiedEnvironment(id)) {
                    // 处理 <transactionManager/> 子标签
                    TransactionFactory txFactory = this.transactionManagerElement(child.evalNode("transactionManager"));
                    // 处理 <dataSource/> 子标签
                    DataSourceFactory dsFactory = this.dataSourceElement(child.evalNode("dataSource"));
                    // 基于解析到的值构造 Environment 对象填充 Configuration 对象
                    DataSource dataSource = dsFactory.getDataSource();
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                        .transactionFactory(txFactory)
                        .dataSource(dataSource);
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获取 <property/> 子标签列表，封装成 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // 构造 DatabaseIdProvider 对象，并填充属性
            databaseIdProvider = (DatabaseIdProvider) this.resolveClass(type).getDeclaredConstructor().newInstance();
            databaseIdProvider.setProperties(properties);
        }

        // 获取当前数据库环境对应的 databaseId，并记录到 Configuration 对象中，以备后用
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获取事务管理器类型配置：JDBC or MANAGED
            String type = context.getStringAttribute("type");
            // 获取 <property/> 子标签列表，封装成 Properties 对象
            Properties props = context.getChildrenAsProperties();
            // 构造对应的 TransactionFactory 对象，并填充属性值
            TransactionFactory factory = (TransactionFactory) this.resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            DataSourceFactory factory = (DataSourceFactory) this.resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    private void typeHandlerElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
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

    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                /*
                 * 配置了 package 属性，从指定包下面扫描注册
                 * <mappers>
                 *      <package name="org.mybatis.builder"/>
                 * </mappers>
                 */
                if ("package".equals(child.getName())) {
                    String mapperPackage = child.getStringAttribute("name");
                    // 调用 MapperRegistry 进行注册
                    configuration.addMappers(mapperPackage);
                }
                // 处理 resource、url，以及 class 配置的场景
                else {
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    /*
                     * <!-- Using classpath relative resources -->
                     * <mappers>
                     *      <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
                     *      <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
                     *      <mapper resource="org/mybatis/builder/PostMapper.xml"/>
                     * </mappers>
                     */
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        // 从类路径获取文件输入流
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 构建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        // 执行映射文件解析
                        mapperParser.parse();
                    }
                    /*
                     * <!-- Using url fully qualified paths -->
                     * <mappers>
                     *      <mapper url="file:///var/mappers/AuthorMapper.xml"/>
                     *      <mapper url="file:///var/mappers/BlogMapper.xml"/>
                     *      <mapper url="file:///var/mappers/PostMapper.xml"/>
                     * </mappers>
                     */
                    else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        // 基于 url 获取配置文件输入流
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        // 构建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        // 执行映射文件解析
                        mapperParser.parse();
                    }
                    /*
                     * <!-- Using mapper interface classes -->
                     * <mappers>
                     *      <mapper class="org.mybatis.builder.AuthorMapper"/>
                     *      <mapper class="org.mybatis.builder.BlogMapper"/>
                     *      <mapper class="org.mybatis.builder.PostMapper"/>
                     * </mappers>
                     */
                    else if (resource == null && url == null && mapperClass != null) {
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
